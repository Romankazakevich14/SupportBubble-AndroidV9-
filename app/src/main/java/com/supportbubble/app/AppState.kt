package com.supportbubble.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide observable state shared between background services.
 *
 * [currentApp] holds the package name of the most recently detected
 * foreground application, updated by [AppMonitorService] and observed
 * by [OverlayService] to keep the floating bubble icon current.
 */
object AppState {

    private val _currentApp = MutableStateFlow("")

    /** The package name of the currently active foreground app. */
    val currentApp: StateFlow<String> = _currentApp.asStateFlow()

    fun updateCurrentApp(packageName: String) {
        _currentApp.value = packageName
    }

    private val _ownAppForeground = MutableStateFlow(false)

    /**
     * True while SupportBubble's own UI ([MainActivity]) is in the foreground.
     *
     * Driven by the Activity lifecycle (onResume/onPause) — NOT by accessibility
     * events. This is what lets [com.supportbubble.app.services.OverlayService]
     * hide the bubble inside our own app, WITHOUT relying on an own-package
     * WINDOW_STATE_CHANGED (which also fires spuriously when our overlay windows
     * appear and used to hide the bubble a split-second after showing it).
     */
    val ownAppForeground: StateFlow<Boolean> = _ownAppForeground.asStateFlow()

    fun setOwnAppForeground(foreground: Boolean) {
        _ownAppForeground.value = foreground
    }
}
