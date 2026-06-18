package com.supportbubble.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.supportbubble.app.AppState
import com.supportbubble.app.socket.AppSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AppMonitorService"
private const val LOG_ACCESSIBILITY = "LOG_ACCESSIBILITY"
private const val LOG_CURRENT_APP = "LOG_CURRENT_APP"
private const val LOG_CURRENT_APP_CHANGED = "LOG_CURRENT_APP_CHANGED"
private const val LOG_WINDOW_EVENT = "LOG_WINDOW_EVENT"

/**
 * Debounce window: wait this many ms after the last event before treating
 * the change as a stable foreground app switch. This filters out transient
 * system-overlay windows that flash between apps.
 */
private const val DEBOUNCE_MS = 800L

/**
 * System-owned packages whose window changes we always ignore.
 * Also filtered: packages that start with "android." (framework windows).
 */
private val IGNORED_PACKAGES = setOf(
    "com.android.systemui",
    "com.android.launcher",
    "com.android.launcher2",
    "com.android.launcher3",
    "com.google.android.apps.nexuslauncher",
    "com.google.android.apps.taskbar",
    "com.huawei.android.launcher",
    "com.miui.home",
    "com.sec.android.app.launcher",
    "com.oppo.launcher",
    "com.vivo.launcher",
    "com.oneplus.launcher",
    "com.android.inputmethod.latin",
    "com.google.android.inputmethod.latin",
    "com.samsung.android.app.spage",
    "com.android.settings",
)

class AppMonitorService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var debounceJob: Job? = null

    /** Last confirmed foreground package (after debounce). */
    private var currentApp: String = ""

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.also { info ->
            // FLICKER FIX: subscribe to TYPE_WINDOW_STATE_CHANGED ONLY.
            //
            // TYPE_WINDOWS_CHANGED and TYPE_WINDOW_CONTENT_CHANGED are deliberately
            // NOT subscribed. Both fire when our OWN floating bubble overlay is
            // shown/hidden (a window-list / content change). Handling them
            // re-resolved the foreground package via rootInActiveWindow, which could
            // report a different/own package and flip currentApp — toggling the
            // bubble, which fired another window event, and so on. That
            // self-sustaining feedback loop made the bubble blink (~1s visible /
            // ~3s hidden) even while the user did nothing. TYPE_WINDOW_STATE_CHANGED
            // fires only on a genuine activity/app transition — the only thing we
            // react to.
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            // We no longer read window content (no rootInActiveWindow recovery), so
            // FLAG_RETRIEVE_INTERACTIVE_WINDOWS is intentionally omitted.
            info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }

        // Initialise the shared socket so this service can emit events
        AppSocket.init(applicationContext)

        Log.d(TAG, "AppMonitorService connected")
        Log.d(LOG_ACCESSIBILITY, "AccessibilityService connected — monitoring foreground apps")
    }

    override fun onInterrupt() {
        Log.d(TAG, "AppMonitorService interrupted")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "AppMonitorService destroyed")
    }

    // ── Event handling ────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // FLICKER FIX: react ONLY to genuine foreground transitions. We subscribe to
        // TYPE_WINDOW_STATE_CHANGED exclusively (see onServiceConnected); guard here
        // too in case the platform delivers anything else.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // The event's own packageName is the authoritative foreground signal for
        // WINDOW_STATE_CHANGED. We never read rootInActiveWindow anymore — that was
        // the path that could echo our own overlay window and cause the blink.
        val pkg = event.packageName?.toString()?.trim()
        if (pkg.isNullOrBlank()) return

        Log.d(LOG_WINDOW_EVENT, "WINDOW_STATE_CHANGED → pkg=$pkg")

        // Same app still in front — no real transition. Do nothing (no toggle).
        if (pkg == currentApp) return

        // CRITICAL: never let OUR OWN package overwrite currentApp from accessibility.
        // Our overlay windows (the bubble, the chat panel) can emit
        // WINDOW_STATE_CHANGED with our own package the instant they appear. If we
        // acted on it we'd flip currentApp → own → the FSM would hide the bubble a
        // split-second after showing it (the reported "blinks once then gone" bug).
        // SupportBubble's REAL UI being foreground (Bug 6) is detected separately via
        // the Activity lifecycle (AppState.ownAppForeground), not here.
        if (shouldIgnore(pkg)) return

        // Debounce: a quick succession of transitions settles to the last one.
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(DEBOUNCE_MS)
            handleAppChange(pkg)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun shouldIgnore(pkg: String): Boolean {
        if (pkg == applicationContext.packageName) return true
        if (pkg.startsWith("android.")) return true
        return IGNORED_PACKAGES.any { pkg.startsWith(it) }
    }

    private fun handleAppChange(packageName: String) {
        currentApp = packageName

        // 1. Notify OverlayService (updates the bubble icon in real time)
        AppState.updateCurrentApp(packageName)

        // 2. Persist locally so DeviceInfo carries the latest value on next launch
        DeviceInfoService.saveLastApp(applicationContext, packageName)

        // 3. Emit to server via Socket.io
        AppSocket.emitAppChange(packageName)

        Log.i(TAG, "Foreground app → $packageName")
        Log.i(LOG_ACCESSIBILITY, "Foreground app → $packageName")
        Log.i(LOG_CURRENT_APP, "currentApp updated → $packageName")
        Log.i(LOG_CURRENT_APP_CHANGED, "currentApp → $packageName (real transition)")
    }
}
