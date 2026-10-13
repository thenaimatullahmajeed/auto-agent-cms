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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.autoagent.cms.data.AgentDatabase
import com.autoagent.cms.data.AgentRepository
import com.autoagent.cms.service.AgentAccessibilityService
import com.autoagent.cms.service.AgentNotificationListener
import com.autoagent.cms.service.AgentService

class MainActivity : ComponentActivity() {
    private lateinit var repository: AgentRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AgentRepository(AgentDatabase.get(applicationContext))
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
        setContent {
            MaterialTheme {
                val tasks by repository.observeTasks()
                    .collectAsState(initial = emptyList())
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Auto Agent CMS", style = MaterialTheme.typography.headlineMedium)
                        Text("Accessibility: ${if (AgentAccessibilityService.connected.value) "Connected" else "Not connected"}")
                        Text("Notification listener: ${if (AgentNotificationListener.connected) "Connected" else "Not connected"}")
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { startAgent() }) {
                                Text("Start")
                            }
                            OutlinedButton(onClick = { stopAgent() }) {
                                Text("Stop")
                            }
                        }
                        Text("Task history", style = MaterialTheme.typography.titleLarge)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(tasks, key = { it.id }) { task ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(task.title, style = MaterialTheme.typography.titleMedium)
                                    Text("Status: ${task.status}")
                                    Text(task.lastError.orEmpty())
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startAgent() {
        val intent = Intent(this, AgentService::class.java).apply {
            action = AgentService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopAgent() {
        val intent = Intent(this, AgentService::class.java).apply {
            action = AgentService.ACTION_STOP
        }
        startService(intent)
    }
}
