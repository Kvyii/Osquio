package com.kvi.osquio.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object SummonEvents {
    private val _summonClosed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val summonClosed = _summonClosed.asSharedFlow()

    fun notifyClosed() {
        _summonClosed.tryEmit(Unit)
    }
}
