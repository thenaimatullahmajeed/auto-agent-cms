package com.autoagent.cms

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.autoagent.cms.data.AgentDatabase
import com.autoagent.cms.data.AgentRepository
import com.autoagent.cms.data.TaskEntity
import com.autoagent.cms.data.TaskStatus
import com.autoagent.cms.llm.KeyManager
import com.autoagent.cms.llm.LlmProvider
import com.autoagent.cms.service.AgentAccessibilityService
import com.autoagent.cms.service.AgentNotificationListener
import com.autoagent.cms.service.AgentService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var repository: AgentRepository
    private lateinit var keyManager: KeyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AgentRepository(AgentDatabase.get(applicationContext))
        keyManager = KeyManager(repository)
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
        setContent {
            MaterialTheme {
                AgentApp()
            }
        }
    }

    @Composable
    private fun AgentApp() {
        val scope = rememberCoroutineScope()
        val a11yConnected by AgentAccessibilityService.connected.collectAsState()
        val notifConnected by AgentNotificationListener.connected.collectAsState()
        val tasks by repository.observeTasks().collectAsState(initial = emptyList())
        val keys by keyManager.observeKeys().collectAsState(initial = emptyList())

        var taskInput by rememberSaveable { mutableStateOf("") }
        var taskMessage by remember { mutableStateOf<String?>(null) }
        var providerName by rememberSaveable { mutableStateOf("GEMINI") }
        var nickname by rememberSaveable { mutableStateOf("") }
        var keyText by rememberSaveable { mutableStateOf("") }
        var keyMessage by remember { mutableStateOf<String?>(null) }

        val activeTask = tasks.firstOrNull {
            it.status == TaskStatus.RUNNING || it.status == TaskStatus.WAITING_FOR_CONFIRMATION
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Auto Agent CMS", style = MaterialTheme.typography.headlineMedium)

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusLine("Accessibility", a11yConnected)
                        StatusLine("Notification listener", notifConnected)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }) {
                                Text("Accessibility")
                            }
                            Button(onClick = {
                                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                            }) {
                                Text("Notifications")
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Naya Task", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = taskInput,
                            onValueChange = { taskInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Task likho (Urdu / Roman Urdu / English)...") },
                            minLines = 3
                        )
                        taskMessage?.let { Text(it, color = Color.Red) }
                        Button(
                            onClick = {
                                scope.launch {
                                    val input = taskInput.trim()
                                    when {
                                        input.isEmpty() -> taskMessage = "Pehle task likho"
                                        keys.isEmpty() -> taskMessage = "API key add karo"
                                        !a11yConnected -> taskMessage = "Pehle Accessibility on karo"
                                        activeTask != null -> taskMessage = "Ek task pehle se chal raha hai"
                                        else -> {
                                            taskMessage = null
                                            val task = repository.createTask(
                                                source = "manual",
                                                sourceMessageId = null,
                                                title = input.take(48),
                                                goal = input,
                                                targetPackage = null
                                            )
                                            taskInput = ""
                                            runTaskService(task.id)
                                        }
                                    }
                                }
                            },
                            enabled = activeTask == null
                        ) {
                            Text("Task Chalao")
                        }
                    }
                }

                if (activeTask != null) {
                    ActiveTaskCard(
                        task = activeTask,
                        onConfirm = { approved -> confirmTaskService(activeTask.id, approved) },
                        onResume = { runTaskService(activeTask.id) },
                        onStop = { stopAgentService() }
                    )
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("API Keys", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProviderButton("Gemini", "GEMINI", providerName) { providerName = it }
                            ProviderButton("Groq", "GROQ", providerName) { providerName = it }
                        }
                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { nickname = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Naam (optional)") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = keyText,
                            onValueChange = { keyText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("API Key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        keyMessage?.let { Text(it, color = Color.Red) }
                        Button(onClick = {
                            scope.launch {
                                val secret = keyText.trim()
                                if (secret.isEmpty()) {
                                    keyMessage = "Key khaali hai"
                                } else {
                                    try {
                                        keyManager.addKey(
                                            LlmProvider.valueOf(providerName),
                                            nickname.trim(),
                                            secret
                                        )
                                        nickname = ""
                                        keyText = ""
                                        keyMessage = "Key save ho gayi"
                                    } catch (e: Exception) {
                                        keyMessage = "Key save nahi hui"
                                    }
                                }
                            }
                        }) {
                            Text("Save Key")
                        }
                        HorizontalDivider()
                        if (keys.isEmpty()) {
                            Text("Koi key nahi hai — pehle Gemini ya Groq ki key add karo.")
                        } else {
                            keys.forEach { key ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("${key.provider} - ${key.nickname}")
                                    TextButton(onClick = {
                                        scope.launch { keyManager.deleteKey(key.id) }
                                    }) {
                                        Text("Delete", color = Color.Red)
                                    }
                                }
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Task History", style = MaterialTheme.typography.titleMedium)
                        if (tasks.isEmpty()) {
                            Text("Abhi koi task nahi hai.")
                        } else {
                            tasks.take(20).forEach { task ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(task.title, style = MaterialTheme.typography.titleSmall)
                                    Text("Status: ${task.status}")
                                    task.lastError?.takeIf { it.isNotBlank() }?.let {
                                        Text(it.take(120), color = Color.Red)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusLine(label: String, connected: Boolean) {
        Text(
            "$label: ${if (connected) "Connected" else "Not connected"}",
            color = if (connected) Color(0xFF2E7D32) else Color.Red
        )
    }

    @Composable
    private fun ProviderButton(
        label: String,
        value: String,
        selected: String,
        onSelect: (String) -> Unit
    ) {
        if (selected == value) {
            Button(onClick = { onSelect(value) }) { Text(label) }
        } else {
            OutlinedButton(onClick = { onSelect(value) }) { Text(label) }
        }
    }

    @Composable
    private fun ActiveTaskCard(
        task: TaskEntity,
        onConfirm: (Boolean) -> Unit,
        onResume: () -> Unit,
        onStop: () -> Unit
    ) {
        val events by repository.observeTaskEvents(task.id).collectAsState(initial = emptyList())
        val lastEvent = events.lastOrNull()
        val awaitingConfirm = task.status == TaskStatus.WAITING_FOR_CONFIRMATION &&
            lastEvent?.eventType == "CONFIRM_REQUEST"

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Chal Raha Task", style = MaterialTheme.typography.titleMedium)
                Text(task.title, style = MaterialTheme.typography.titleSmall)
                Text("Status: ${task.status}")
                Text("Steps: ${task.stepCount}")
                task.lastActionSummary?.takeIf { it.isNotBlank() }?.let {
                    Text("Aakhri action: ${it.take(140)}")
                }
                if (task.status == TaskStatus.FAILED) {
                    task.lastError?.takeIf { it.isNotBlank() }?.let {
                        Text(it.take(140), color = Color.Red)
                    }
                }
                if (awaitingConfirm && lastEvent != null) {
                    Text("Sawal: ${lastEvent.message.take(200)}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onConfirm(true) }) { Text("Haan") }
                        OutlinedButton(onClick = { onConfirm(false) }) { Text("Nahi") }
                    }
                } else if (task.status == TaskStatus.WAITING_FOR_CONFIRMATION) {
                    lastEvent?.message?.takeIf { it.isNotBlank() }?.let {
                        Text(it.take(200))
                    }
                    Button(onClick = onResume) { Text("Dobara Chalao") }
                }
                OutlinedButton(onClick = onStop) { Text("Stop") }
                HorizontalDivider()
                Text("Progress:", style = MaterialTheme.typography.titleSmall)
                events.takeLast(6).reversed().forEach { event ->
                    Text("${event.eventType}: ${event.message.take(100)}")
                }
            }
        }
    }

    private fun runTaskService(taskId: String) {
        val intent = Intent(this, AgentService::class.java).apply {
            action = AgentService.ACTION_RUN_TASK
            putExtra(AgentService.EXTRA_TASK_ID, taskId)
        }
        startForegroundService(intent)
    }

    private fun confirmTaskService(taskId: String, approved: Boolean) {
        val intent = Intent(this, AgentService::class.java).apply {
            action = AgentService.ACTION_CONFIRM
            putExtra(AgentService.EXTRA_TASK_ID, taskId)
            putExtra(AgentService.EXTRA_APPROVED, approved)
        }
        startService(intent)
    }

    private fun stopAgentService() {
        val intent = Intent(this, AgentService::class.java).apply {
            action = AgentService.ACTION_STOP
        }
        startService(intent)
    }
}
