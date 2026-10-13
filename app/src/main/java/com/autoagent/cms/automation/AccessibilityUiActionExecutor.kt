package com.autoagent.cms.automation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.autoagent.cms.service.AgentAccessibilityService
import kotlinx.coroutines.delay

class AccessibilityUiActionExecutor(
    private val context: Context,
    private val observer: ScreenObserver
) : UiActionExecutor {
    override suspend fun execute(action: UiAction, screen: ObservedScreen): StepResult {
        val service = AgentAccessibilityService.currentService
            ?: return StepResult.Failure("Accessibility service disconnected.")
        val fresh = observer.observe()
        val current = fresh.screen
            ?: return StepResult.NeedsHuman("Cannot safely inspect the current screen.")
        if (fresh.blocked) {
            return StepResult.NeedsHuman("Sensitive or inaccessible screen detected.")
        }
        if (current.packageName != screen.packageName || current.screenHash != screen.screenHash) {
            return StepResult.NeedsHuman("Screen changed since planning. Re-observe before acting.")
        }
        return try {
            when (action.action) {
                UiActionType.TAP -> tap(service, action, current)
                UiActionType.TYPE -> type(service, action, current)
                UiActionType.SCROLL -> scroll(service, action, current)
                UiActionType.BACK -> {
                    val ok = service.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                    )
                    if (ok) StepResult.Success("Navigated back.")
                    else StepResult.Failure("Back action failed.")
                }
                UiActionType.HOME -> {
                    val ok = service.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                    )
                    if (ok) StepResult.Success("Returned to home.")
                    else StepResult.Failure("Home action failed.")
                }
                UiActionType.WAIT -> {
                    delay(action.waitMs.coerceIn(0, 5000))
                    StepResult.Success("Wait completed.")
                }
                UiActionType.LAUNCH -> launch(action)
                UiActionType.INTENT -> StepResult.NeedsHuman(
                    "Generic intent execution is disabled until its destination is reviewed."
                )
                UiActionType.DONE -> StepResult.Completed
                UiActionType.ASK_HUMAN -> StepResult.NeedsHuman(
                    action.text ?: "Human review required."
                )
            }
        } catch (_: Exception) {
            StepResult.NeedsHuman("Action outcome is uncertain. Inspect the phone before continuing.")
        }
    }

    private fun tap(
        service: AgentAccessibilityService,
        action: UiAction,
        screen: ObservedScreen
    ): StepResult {
        val target = resolveNode(service, action, screen)
            ?: return StepResult.Failure("Tap target could not be resolved.")
        return try {
            if (target.isClickable) {
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    StepResult.Success("Tapped matched accessibility element.")
                } else {
                    StepResult.Failure("Accessibility click was rejected.")
                }
            } else {
                StepResult.NeedsHuman("Matched element is not clickable.")
            }
        } finally {
            target.recycle()
        }
    }

    private fun type(
        service: AgentAccessibilityService,
        action: UiAction,
        screen: ObservedScreen
    ): StepResult {
        val target = resolveNode(service, action, screen)
            ?: return StepResult.Failure("Text field could not be resolved.")
        return try {
            if (!target.isEditable || target.isPassword) {
                return StepResult.NeedsHuman("Target is not a permitted ordinary text field.")
            }
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    action.text.orEmpty()
                )
            }
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                StepResult.Success("Text entry action submitted.")
            } else {
                StepResult.Failure("Text entry was rejected.")
            }
        } finally {
            target.recycle()
        }
    }

    private fun scroll(
        service: AgentAccessibilityService,
        action: UiAction,
        screen: ObservedScreen
    ): StepResult {
        val target = resolveNode(service, action, screen)
            ?: return StepResult.Failure("Scroll target could not be resolved.")
        return try {
            if (!target.isScrollable) {
                return StepResult.NeedsHuman("Target is not scrollable.")
            }
            val forward = action.direction?.lowercase() != "backward"
            val scrollAction = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (target.performAction(scrollAction)) {
                StepResult.Success("Scroll action submitted.")
            } else {
                StepResult.Failure("Scroll action was rejected.")
            }
        } finally {
            target.recycle()
        }
    }

    private fun resolveNode(
        service: AgentAccessibilityService,
        action: UiAction,
        screen: ObservedScreen
    ): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val candidate = screen.elements.firstOrNull { element ->
            val idMatches = !action.targetViewId.isNullOrBlank() &&
                element.viewId == action.targetViewId
            val textMatches = !action.targetText.isNullOrBlank() &&
                element.text == action.targetText
            val descriptionMatches = !action.targetDescription.isNullOrBlank() &&
                element.description == action.targetDescription
            idMatches || textMatches || descriptionMatches
        } ?: return null
        return findNodeByIdentity(root, candidate, 0)
    }

    private fun findNodeByIdentity(
        node: AccessibilityNodeInfo,
        target: UiElement,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 30) return null
        val text = try { node.text?.toString() } catch (_: Exception) { null }
        val description = try {
            node.contentDescription?.toString()
        } catch (_: Exception) { null }
        val viewId = try { node.viewIdResourceName } catch (_: Exception) { null }
        val matches = (!target.viewId.isNullOrBlank() && viewId == target.viewId) ||
            (!target.text.isNullOrBlank() && text == target.text) ||
            (!target.description.isNullOrBlank() && description == target.description)
        if (matches) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount.coerceAtMost(100)) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val found = findNodeByIdentity(child, target, depth + 1)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun launch(action: UiAction): StepResult {
        val packageName = action.packageName
            ?: return StepResult.Failure("No package name provided.")
        if (packageName !in setOf(
                "com.google.android.keep",
                "com.whatsapp",
                "com.whatsapp.w4b"
            )
        ) {
            return StepResult.NeedsHuman("Package is not on the explicit launch allowlist.")
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return StepResult.Failure("Target app is not installed.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return StepResult.Success("Launch request sent.")
    }
}
