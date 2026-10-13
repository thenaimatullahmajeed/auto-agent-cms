package com.autoagent.cms.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.autoagent.cms.automation.AccessibilityUiActionExecutor
import com.autoagent.cms.automation.AgentLoop
import com.autoagent.cms.automation.ScreenObserver
import com.autoagent.cms.automation.TaskReporter
import com.autoagent.cms.automation.UiExecutor
import com.autoagent.cms.data.AgentDatabase
import com.autoagent.cms.data.AgentRepository
import com.autoagent.cms.data.TaskEntity
import com.autoagent.cms.data.TaskStatus
import com.autoagent.cms.llm.GeminiClient
import com.autoagent.cms.llm.GroqClient
import com.autoagent.cms.llm.KeyManager
import com.autoagent.cms.llm.LlmRouter
import com.autoagent.cms.security.SensitiveScreenGuard
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AgentService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private lateinit var repository: AgentRepository
    private lateinit var keyManager: KeyManager
    private val uiExecutor = UiExecutor()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val confirmations = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    override fun onCreate() {
        super.onCreate()
        repository = AgentRepository(AgentDatabase.get(applicationContext))
        keyManager = KeyManager(repository)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Agent taiyar hai"))
        serviceScope.launch {
            repository.recoverAfterProcessDeath()
            repository.appendEvent("system", "SERVICE_STARTED", "Service started.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RUN_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (taskId != null) launchTask(taskId)
            }
            ACTION_CONFIRM -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                val approved = intent.getBooleanExtra(EXTRA_APPROVED, false)
                if (taskId != null) {
                    serviceScope.launch { answerConfirmation(taskId, approved) }
                }
            }
            ACTION_STOP -> serviceScope.launch { stopAll() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun launchTask(taskId: String) {
        if (runningJobs.containsKey(taskId)) return
        val job = serviceScope.launch {
            try {
                runTaskInternal(taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    repository.recordError(taskId, "Start nahi ho saka: ${e.javaClass.simpleName}")
                    repository.setStatus(taskId, TaskStatus.PAUSED, "Start nahi ho saka.")
                } catch (_: Exception) {
                }
            } finally {
                runningJobs.remove(taskId)
                confirmations.remove(taskId)
            }
        }
        runningJobs[taskId] = job
    }

    private suspend fun runTaskInternal(taskId: String) {
        val task = repository.getTask(taskId) ?: return
        if (!keyManager.hasAnyKey()) {
            repository.recordError(taskId, "API key add karo")
            repository.setStatus(taskId, TaskStatus.PAUSED, "API key add karo")
            return
        }
        val service = AgentAccessibilityService.currentService
        if (service == null) {
            repository.recordError(taskId, "Accessibility service on karo")
            repository.setStatus(taskId, TaskStatus.PAUSED, "Accessibility service on karo")
            return
        }
        val (geminiPool, groqPool) = keyManager.loadPools()
        val router = LlmRouter(
            gemini = GeminiClient(),
            groq = GroqClient(),
            geminiKeys = geminiPool,
            groqKeys = groqPool,
            geminiModel = "gemini-2.0-flash",
            groqTextModel = "llama-3.3-70b-versatile"
        )
        val observer = ScreenObserver(service, SensitiveScreenGuard())
        val actionExecutor = AccessibilityUiActionExecutor(applicationContext, observer)
        val loop = AgentLoop(
            repository = repository,
            observer = observer,
            router = router,
            actionExecutor = actionExecutor,
            reporter = ServiceTaskReporter(),
            uiExecutor = uiExecutor,
            maxSteps = 30
        )
        updateNotification("Chal raha hai: ${task.title.take(40)}")
        try {
            loop.run(taskId)
        } finally {
            updateNotification("Agent taiyar hai")
        }
    }

    private suspend fun answerConfirmation(taskId: String, approved: Boolean) {
        val deferred = confirmations[taskId]
        if (deferred != null) {
            deferred.complete(approved)
        } else {
            repository.appendEvent(taskId, "CONFIRM_RESULT", "Koi pending confirmation nahi mili.")
        }
    }

    private suspend fun stopAll() {
        runningJobs.values.toList().forEach { it.cancel() }
        updateNotification("Agent rok diya gaya")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private inner class ServiceTaskReporter : TaskReporter {
        override suspend fun report(task: TaskEntity, message: String) {
            repository.appendEvent(task.id, "REPORT", message)
        }

        override suspend fun askForConfirmation(task: TaskEntity, question: String): Boolean {
            val deferred = CompletableDeferred<Boolean>()
            confirmations[task.id] = deferred
            repository.setStatus(task.id, TaskStatus.WAITING_FOR_CONFIRMATION, question)
            repository.appendEvent(task.id, "CONFIRM_REQUEST", question)
            try {
                val approved = deferred.await()
                repository.appendEvent(
                    task.id,
                    "CONFIRM_RESULT",
                    if (approved) "User ne approve kiya." else "User ne reject kiya."
                )
                return approved
            } finally {
                confirmations.remove(task.id)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Auto Agent", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setContentTitle("Auto Agent CMS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_RUN_TASK = "com.autoagent.cms.RUN_TASK"
        const val ACTION_CONFIRM = "com.autoagent.cms.CONFIRM"
        const val ACTION_STOP = "com.autoagent.cms.STOP"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_APPROVED = "extra_approved"
        private const val CHANNEL_ID = "auto_agent_service"
        private const val NOTIFICATION_ID = 1001
    }
}
