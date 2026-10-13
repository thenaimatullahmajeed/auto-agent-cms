package com.autoagent.cms.automation

import kotlinx.serialization.Serializable

@Serializable
enum class UiActionType {
    TAP, TYPE, SCROLL, BACK, HOME, WAIT, LAUNCH, INTENT, DONE, ASK_HUMAN
}

@Serializable
data class UiAction(
    val action: UiActionType,
    val targetText: String? = null,
    val targetDescription: String? = null,
    val targetViewId: String? = null,
    val text: String? = null,
    val direction: String? = null,
    val packageName: String? = null,
    val waitMs: Long = 500
)

data class UiElement(
    val index: Int,
    val text: String?,
    val description: String?,
    val viewId: String?,
    val className: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val password: Boolean
)

data class ObservedScreen(
    val packageName: String?,
    val elements: List<UiElement>,
    val screenHash: String,
    val observedAt: Long = System.currentTimeMillis()
)

sealed interface StepResult {
    data class Success(val message: String) : StepResult
    data class Failure(val message: String) : StepResult
    data class NeedsHuman(val question: String) : StepResult
    data object Completed : StepResult
}
