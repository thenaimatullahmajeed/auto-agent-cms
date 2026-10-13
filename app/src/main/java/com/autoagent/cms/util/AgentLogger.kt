package com.autoagent.cms.util

import android.util.Log
import com.autoagent.cms.data.AgentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AgentLogger(private val repository: AgentRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun info(taskId: String, message: String) = record(taskId, "INFO", message)
    fun warn(taskId: String, message: String) = record(taskId, "WARN", message)
    fun error(taskId: String, message: String) = record(taskId, "ERROR", message)

    private fun record(taskId: String, level: String, message: String) {
        val safeMessage = redact(message).take(1000)
        when (level) {
            "ERROR" -> Log.e(TAG, safeMessage)
            "WARN" -> Log.w(TAG, safeMessage)
            else -> Log.i(TAG, safeMessage)
        }
        scope.launch {
            repository.appendEvent(taskId, level, safeMessage)
        }
    }

    private fun redact(value: String): String {
        var result = value
        result = result.replace(Regex("(?i)(api[_ -]?key|authorization|bearer)\\s*[:=]?\\s*\\S+"), "[REDACTED]")
        result = result.replace(Regex("\\b\\d{4,8}\\b"), "[REDACTED_NUMBER]")
        return result
    }

    companion object {
        private const val TAG = "AutoAgentCMS"
    }
}
