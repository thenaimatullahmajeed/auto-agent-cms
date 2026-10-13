package com.autoagent.cms.automation

import com.autoagent.cms.data.AgentRepository
import com.autoagent.cms.data.TaskEntity
import com.autoagent.cms.data.TaskStatus
import com.autoagent.cms.llm.LlmFailure
import com.autoagent.cms.llm.userMessage
import com.autoagent.cms.llm.LlmMessage
import com.autoagent.cms.llm.LlmRequest
import com.autoagent.cms.llm.LlmRouter
import com.autoagent.cms.llm.RequestPurpose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

interface UiActionExecutor {
    suspend fun execute(action: UiAction, screen: ObservedScreen): StepResult
}

interface TaskReporter {
    suspend fun report(task: TaskEntity, message: String)
    suspend fun askForConfirmation(task: TaskEntity, question: String): Boolean
}

class AgentLoop(
    private val repository: AgentRepository,
    private val observer: ScreenObserver,
    private val router: LlmRouter,
    private val actionExecutor: UiActionExecutor,
    private val reporter: TaskReporter,
    private val uiExecutor: UiExecutor,
    private val maxSteps: Int = 30
) {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    suspend fun run(taskId: String) {
        var task = repository.getTask(taskId) ?: return
        repository.setStatus(taskId, TaskStatus.RUNNING, "Task started.")
        try {
            for (step in 1..maxSteps) {
                task = repository.getTask(taskId) ?: return
                if (task.status == TaskStatus.CANCELLED) return

                val observation = observer.observe()
                if (observation.blocked || observation.screen == null) {
                    repository.setStatus(
                        taskId, TaskStatus.PAUSED,
                        "Screen unavailable or blocked: ${observation.reason}"
                    )
                    reporter.report(task, "Task paused: ${observation.reason}")
                    return
                }
                val screen = observation.screen
                val action = decide(task, screen, step)

                if (action.action == UiActionType.ASK_HUMAN) {
                    repository.setStatus(
                        taskId, TaskStatus.WAITING_FOR_CONFIRMATION,
                        "Human assistance required."
                    )
                    reporter.report(task, action.text ?: "Please inspect the phone and continue manually.")
                    return
                }
                if (action.action == UiActionType.DONE) {
                    repository.setStatus(taskId, TaskStatus.COMPLETED, "Agent reported completion.")
                    reporter.report(task, "Done: ${task.title}")
                    return
                }
                if (requiresConfirmation(action)) {
                    repository.setStatus(
                        taskId, TaskStatus.WAITING_FOR_CONFIRMATION,
                        "Action requires user confirmation."
                    )
                    val approved = reporter.askForConfirmation(
                        task, "Approve action: ${action.action} ${action.targetText.orEmpty()}?"
                    )
                    if (!approved) {
                        repository.setStatus(taskId, TaskStatus.PAUSED, "Confirmation not received.")
                        return
                    }
                }

                validateAction(action, screen)
                val result = uiExecutor.execute {
                    actionExecutor.execute(action, screen)
                }
                when (result) {
                    is StepResult.Success -> repository.recordStep(taskId, screen.screenHash, result.message)
                    is StepResult.Failure -> {
                        repository.recordError(taskId, result.message)
                        repository.setStatus(taskId, TaskStatus.PAUSED, "Action failed; re-observation required.")
                        reporter.report(task, "Paused safely: ${result.message}")
                        return
                    }
                    is StepResult.NeedsHuman -> {
                        repository.setStatus(taskId, TaskStatus.PAUSED, result.question)
                        reporter.report(task, result.question)
                        return
                    }
                    StepResult.Completed -> {
                        repository.setStatus(taskId, TaskStatus.COMPLETED, "Action executor verified completion.")
                        reporter.report(task, "Done: ${task.title}")
                        return
                    }
                }
            }
            repository.setStatus(taskId, TaskStatus.PAUSED, "Step limit reached; manual review required.")
            reporter.report(task, "Paused: reached the $maxSteps-step limit.")
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                repository.setStatus(taskId, TaskStatus.CANCELLED, "Task cancelled.")
            }
            throw e
        } catch (e: Exception) {
            val message = (e as? LlmFailure)?.userMessage() ?: "Task failed: ${e.javaClass.simpleName}"
            repository.recordError(taskId, message)
            repository.setStatus(taskId, TaskStatus.PAUSED, "Unexpected failure; no automatic action replay.")
        }
    }

    private suspend fun decide(task: TaskEntity, screen: ObservedScreen, step: Int): UiAction {
        val elements = screen.elements.take(250).joinToString("\n") {
            "index=${it.index}, text=${it.text.orEmpty().take(100)}, " +
                "desc=${it.description.orEmpty().take(100)}, " +
                "id=${it.viewId.orEmpty()}, class=${it.className.orEmpty()}, " +
                "clickable=${it.clickable}, editable=${it.editable}, " +
                "scrollable=${it.scrollable}, password=${it.password}"
        }
        val prompt = """
            Task goal: ${task.goal}
            Task title: ${task.title}
            Target package: ${task.targetPackage ?: "not specified"}
            Current package: ${screen.packageName ?: "unknown"}
            Step: $step of $maxSteps
            Screen hash: ${screen.screenHash}
            Accessible elements:
            $elements

            Return exactly one JSON object matching UiAction.
            Allowed actions: TAP, TYPE, SCROLL, BACK, HOME, WAIT, LAUNCH, INTENT, DONE, ASK_HUMAN.
            Never perform payment, purchase, account deletion, password, OTP, or banking actions.
            If uncertain, choose ASK_HUMAN.
            Only target elements visible in the current accessibility tree.
        """.trimIndent()

        val response = router.generate(
            LlmRequest(
                purpose = RequestPurpose.SCREEN_DECISION,
                systemPrompt = "You are a cautious Android UI automation planner.",
                messages = listOf(LlmMessage("user", prompt)),
                model = "provider-selected",
                maxTokens = 400,
                temperature = 0.0,
                jsonMode = true
            )
        )
        return try {
            json.decodeFromString<UiAction>(response.content)
        } catch (_: Exception) {
            throw IllegalStateException("LLM returned an invalid action.")
        }
    }

    private fun validateAction(action: UiAction, screen: ObservedScreen) {
        if (action.action == UiActionType.TAP || action.action == UiActionType.TYPE) {
            val targetExists = screen.elements.any { element ->
                (!action.targetViewId.isNullOrBlank() && element.viewId == action.targetViewId) ||
                    (!action.targetText.isNullOrBlank() && element.text == action.targetText) ||
                    (!action.targetDescription.isNullOrBlank() && element.description == action.targetDescription)
            }
            require(targetExists) { "Action target not found in current screen." }
        }
        if (action.action == UiActionType.LAUNCH) {
            require(!action.packageName.isNullOrBlank()) { "Launch action requires a package name." }
        }
        if (action.action == UiActionType.TYPE) {
            require(action.text != null) { "Type action requires text." }
        }
        require(action.waitMs in 0..5000) { "Wait duration is outside the permitted range." }
    }

    private fun requiresConfirmation(action: UiAction): Boolean {
        val text = listOfNotNull(action.targetText, action.targetDescription, action.text)
            .joinToString(" ").lowercase()
        val riskyTerms = listOf(
            "send", "publish", "delete", "remove", "payment",
            "purchase", "transfer", "post", "share"
        )
        return action.action == UiActionType.INTENT || riskyTerms.any(text::contains)
    }
}
