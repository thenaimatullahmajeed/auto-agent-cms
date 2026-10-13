package com.autoagent.cms.automation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UiExecutor {
    private val mutex = Mutex()

    suspend fun <T> execute(action: suspend () -> T): T = mutex.withLock {
        action()
    }

    val isBusy: Boolean get() = mutex.isLocked
}
