package com.autoagent.cms.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AgentAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        currentService = this
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString().orEmpty()
        _activePackage.value = packageName
    }

    override fun onInterrupt() {
        _connected.value = false
    }

    override fun onDestroy() {
        if (currentService === this) currentService = null
        _connected.value = false
        super.onDestroy()
    }

    companion object {
        @Volatile
        var currentService: AgentAccessibilityService? = null
            private set
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected
        private val _activePackage = MutableStateFlow("")
        val activePackage: StateFlow<String> = _activePackage
    }
}
