package com.example.deepsight

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

object OverlayBridge {
    enum class OverlayCommand { SHOW, HIDE }
    
    val overlayCommandFlow = MutableSharedFlow<OverlayCommand>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
}
