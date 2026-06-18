package com.supportbubble.app.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.supportbubble.app.AllowedAppsManager
import com.supportbubble.app.AppState
import com.supportbubble.app.BubbleAppSettings
import com.supportbubble.app.DEFAULT_BUBBLE_SETTINGS
import com.supportbubble.app.R
import com.supportbubble.app.ui.overlay.OverlayChatPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TAG = "OverlayService"
private const val LOG_OVERLAY = "LOG_OVERLAY"
private const val LOG_OVERLAY_CREATE = "LOG_OVERLAY_CREATE"
private const val LOG_OVERLAY_REMOVE = "LOG_OVERLAY_REMOVE"
private const val LOG_OVERLAY_SHOW = "LOG_OVERLAY_SHOW"
private const val LOG_OVERLAY_HIDE = "LOG_OVERLAY_HIDE"
private const val LOG_BUBBLE = "LOG_BUBBLE"
// ── Finite-state-machine log tags ──────────────────────────────────────────
private const val LOG_CURRENT_PACKAGE = "LOG_CURRENT_PACKAGE"
private const val LOG_ALLOWED_APP = "LOG_ALLOWED_APP"
private const val LOG_SHOW_BUBBLE = "LOG_SHOW_BUBBLE"
private const val LOG_HIDE_BUBBLE = "LOG_HIDE_BUBBLE"
private const val LOG_IS_VISIBLE = "LOG_IS_VISIBLE"
private const val LOG_CURRENT_BUBBLE_PACKAGE = "LOG_CURRENT_BUBBLE_PACKAGE"
private const val SIZE_MIN_DP = 60
private const val SIZE_MAX_DP = 120

/**
 * Manages two overlay windows:
 *
 * 1. **Bubble** — a draggable circle whose appearance (text, icon, colour, size) is
 *    driven by [AllowedAppsManager.settings] in real time.
 *    Hidden when the current foreground package has `enabled = false`.
 *
 * 2. **Chat panel** — a Compose bottom sheet for the full support conversation.
 *
 * Requires [android.Manifest.permission.SYSTEM_ALERT_WINDOW].
 */
class OverlayService : Service() {

    // ── WindowManager ─────────────────────────────────────────────────────────
    private lateinit var windowManager: WindowManager

    // ── Bubble views (kept as fields so appearance can be updated at runtime) ──
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var bubbleRoot: LinearLayout
    private lateinit var appIconView: ImageView
    private lateinit var bubbleLabelView: TextView
    private lateinit var bubbleBackground: GradientDrawable

    // ── Chat panel ────────────────────────────────────────────────────────────
    private var chatPanelView: ComposeView? = null
    private var chatPanelParams: WindowManager.LayoutParams? = null
    private var chatLifecycleOwner: ServiceLifecycleOwner? = null

    // ── Coroutines + chat state ───────────────────────────────────────────────
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var chatState: OverlayChatState

    // ── Bubble visibility FSM state ───────────────────────────────────────────
    // The bubble is created ONCE; these two fields capture its logical state so
    // show/hide happen only on real disallowed↔allowed transitions (no timers).
    private var isBubbleVisible: Boolean = false
    private var currentBubblePackage: String? = null

    // Real (third-party) app underneath the chat panel, captured when the panel
    // opens so the bubble can be restored for it after the panel closes.
    private var chatUnderlyingPackage: String = ""

    // ── IME (soft-keyboard) tracking for the chat panel ───────────────────────
    // Overlay windows do NOT reliably deliver IME insets to Compose, so the
    // keyboard would cover the input field. We detect its height here and feed
    // it to OverlayChatState, which the panel turns into animated bottom padding.
    // Primary source: WindowInsets (Type.ime()). Fallback: OnGlobalLayoutListener
    // comparing the visible display frame against the real screen height.
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var screenHeightPx: Int = 0
    // Becomes true once WindowInsets reports a real IME height on this device;
    // from then on insets are authoritative and the layout fallback stands down.
    private var insetsImeWorks: Boolean = false

    // ── dp helper ─────────────────────────────────────────────────────────────
    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    // ── Static helpers ────────────────────────────────────────────────────────
    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        chatState = OverlayChatState(applicationContext, serviceScope)
        createAndShowBubble()
        observeVisibility()
        Log.d(TAG, "OverlayService started")
        Log.d(LOG_OVERLAY, "OverlayService.onCreate — bubble created, observing visibility")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        safeRemoveBubble()
        hideChatPanel()
        chatLifecycleOwner?.onDestroy()
        chatState.destroy()
        super.onDestroy()
        Log.d(TAG, "OverlayService destroyed")
    }

    // ── Bubble creation ───────────────────────────────────────────────────────

    private fun createAndShowBubble() {
        val initialSize = DEFAULT_BUBBLE_SETTINGS.bubbleSize.dp

        bubbleBackground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(1.dp, Color.parseColor("#E0E0E0"))
        }

        appIconView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp).apply {
                topMargin = 8.dp
                gravity = Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "App icon"
            setImageDrawable(ContextCompat.getDrawable(this@OverlayService, R.drawable.ic_notification))
        }

        bubbleLabelView = TextView(this).apply {
            text = DEFAULT_BUBBLE_SETTINGS.bubbleText
            textSize = 9.5f
            setTextColor(Color.WHITE)
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 2.dp
                bottomMargin = 7.dp
            }
        }

        bubbleRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = bubbleBackground
            elevation = 14f
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            // Hidden by default — observeVisibility() shows it only when the current
            // foreground app is allow-listed. Avoids a one-off flash on service start.
            visibility = View.GONE
            addView(appIconView)
            addView(bubbleLabelView)
        }

        bubbleParams = WindowManager.LayoutParams(
            initialSize, initialSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 250.dp
        }

        // Apply initial colour to background
        applyBubbleColor(DEFAULT_BUBBLE_SETTINGS.bubbleColor)

        windowManager.addView(bubbleRoot, bubbleParams)
        setupDrag()
        Log.i(LOG_OVERLAY_CREATE, "Bubble overlay view created ONCE (visibility-only toggling hereafter)")
    }

    // ── Drag ─────────────────────────────────────────────────────────────────

    private fun setupDrag() {
        var initX = 0; var initY = 0
        var initTouchX = 0f; var initTouchY = 0f
        var isDragging = false

        bubbleRoot.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = bubbleParams.x; initY = bubbleParams.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initTouchX).toInt()
                    val dy = (event.rawY - initTouchY).toInt()
                    if (abs(dx) > 8.dp || abs(dy) > 8.dp) isDragging = true
                    if (isDragging) {
                        bubbleParams.x = initX + dx
                        bubbleParams.y = initY + dy
                        windowManager.updateViewLayout(bubbleRoot, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) showChatPanel()
                    true
                }
                else -> false
            }
        }
    }

    // ── Visibility + appearance ────────────────────────────────────────────────

    /**
     * Combines [AppState.currentApp] with [AllowedAppsManager.settings] so that
     * any change in either — foreground app switch OR admin settings push — triggers
     * an immediate update to the bubble's appearance and visibility.
     */
    private fun observeVisibility() {
        serviceScope.launch {
            combine(
                AppState.currentApp,
                AllowedAppsManager.settings,
                AppState.ownAppForeground,
            ) { pkg, settingsMap, ownForeground ->
                Triple(pkg, settingsMap?.get(pkg), ownForeground)
            }
                // Only act when the (package, effective-settings, own-foreground)
                // tuple actually changes. BubbleAppSettings is a data class, so this
                // compares structurally and prevents churn from equal settings pushes.
                .distinctUntilChanged()
                .collect { (pkg, settings, ownForeground) ->
                    handleStateTransition(pkg, settings, ownForeground)
                }
        }
    }

    /**
     * Finite-state machine for bubble visibility — the SINGLE place that decides
     * show vs. hide. It is driven only by real changes to (foreground package,
     * settings); there are NO timers and NO polling.
     *
     * Logical state = [isBubbleVisible] + [currentBubblePackage]. The only two
     * side-effecting transitions are:
     *   • disallowed → allowed   ⇒ showBubble()   (the ONLY call site)
     *   • allowed   → disallowed ⇒ hideBubble()   (the ONLY call site)
     * Switching between two different allowed apps only refreshes the appearance —
     * the bubble view is never recreated.
     *
     * "Allowed" = a real third-party app (non-blank, not SupportBubble itself) that
     * the admin has explicitly enabled. Blank / own-app / unconfigured / disabled
     * all map to the disallowed state.
     */
    private fun handleStateTransition(
        pkg: String,
        settings: BubbleAppSettings?,
        ownAppForeground: Boolean,
    ) {
        Log.d(LOG_CURRENT_PACKAGE, "currentPackage = '$pkg' (ownAppForeground=$ownAppForeground)")

        // While the chat panel is open the bubble is intentionally hidden; the FSM
        // must not fight it. hideChatPanel() re-runs this function afterwards.
        if (chatPanelView?.isAttachedToWindow == true) {
            Log.d(LOG_OVERLAY, "Chat panel open — bubble state deferred")
            return
        }

        // "Allowed" = a real third-party app the admin enabled, AND SupportBubble's
        // own UI is NOT foreground (Bug 6, detected via Activity lifecycle).
        val isAllowed = !ownAppForeground &&
            pkg.isNotBlank() &&
            pkg != packageName &&
            settings != null &&
            settings.enabled
        Log.d(
            LOG_ALLOWED_APP,
            "pkg='$pkg' allowed=$isAllowed (configured=${settings != null}, ownFg=$ownAppForeground)",
        )

        if (isAllowed) {
            when {
                // Transition: disallowed → allowed ⇒ SHOW
                !isBubbleVisible -> {
                    updateBubbleAppearance(pkg, settings!!)
                    currentBubblePackage = pkg
                    showBubble()
                    isBubbleVisible = true
                    Log.i(LOG_SHOW_BUBBLE, "showBubble(): disallowed→allowed for '$pkg'")
                }
                // Transition: allowed → another allowed ⇒ refresh only, NO recreate
                pkg != currentBubblePackage -> {
                    updateBubbleAppearance(pkg, settings!!)
                    currentBubblePackage = pkg
                    Log.i(
                        LOG_CURRENT_BUBBLE_PACKAGE,
                        "allowed→allowed: refreshed to '$pkg' (no recreate)",
                    )
                }
                // Same allowed app still in front ⇒ do nothing
                else -> {
                    Log.d(LOG_CURRENT_BUBBLE_PACKAGE, "same allowed app '$pkg' — no-op")
                }
            }
        } else {
            if (isBubbleVisible) {
                // Transition: allowed → disallowed ⇒ HIDE
                hideBubble()
                isBubbleVisible = false
                currentBubblePackage = null
                Log.i(LOG_HIDE_BUBBLE, "hideBubble(): allowed→disallowed (now '$pkg')")
            } else {
                Log.d(LOG_HIDE_BUBBLE, "disallowed '$pkg' — already hidden, no-op")
            }
        }

        Log.d(
            LOG_IS_VISIBLE,
            "isBubbleVisible=$isBubbleVisible, currentBubblePackage=$currentBubblePackage",
        )
    }

    /**
     * Applies [settings] to the bubble view — no-ops if the bubble isn't attached yet.
     * Safe to call from the Main dispatcher (all UI mutations happen here).
     */
    private fun updateBubbleAppearance(packageName: String, settings: BubbleAppSettings) {
        // 1. Label text
        bubbleLabelView.text = settings.bubbleText

        // 2. Background colour
        applyBubbleColor(settings.bubbleColor)

        // 3. Icon
        applyBubbleIcon(packageName, settings.bubbleIcon)

        // 4. Size
        applyBubbleSize(settings.bubbleSize)
    }

    private fun applyBubbleColor(hex: String) {
        try {
            bubbleBackground.setColor(Color.parseColor(hex))
            // Ensure the stroke stays visible
            bubbleBackground.setStroke(1.dp, Color.parseColor("#33000000"))
        } catch (e: Exception) {
            Log.w(TAG, "Invalid bubble colour: $hex")
        }
    }

    private fun applyBubbleIcon(packageName: String, iconSpec: String) {
        when {
            iconSpec == "app_icon" || iconSpec.isBlank() -> {
                // Default: use the foreground app's own icon from PackageManager
                try {
                    appIconView.setImageDrawable(packageManager.getApplicationIcon(packageName))
                } catch (e: PackageManager.NameNotFoundException) {
                    appIconView.setImageDrawable(
                        ContextCompat.getDrawable(this, R.drawable.ic_notification)
                    )
                }
            }
            iconSpec.startsWith("data:image") -> {
                // Custom uploaded PNG — decode base64 and display as bitmap
                try {
                    val base64Part = iconSpec.substringAfter(",")
                    val bytes = Base64.decode(base64Part, Base64.DEFAULT)
                    val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    appIconView.setImageBitmap(bmp)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode custom icon", e)
                    appIconView.setImageDrawable(
                        ContextCompat.getDrawable(this, R.drawable.ic_notification)
                    )
                }
            }
            else -> {
                // Built-in named icon key (support, chat, help, telegram, …)
                // Fallback to the notification icon; the bubble colour + text provide context.
                appIconView.setImageDrawable(
                    ContextCompat.getDrawable(this, R.drawable.ic_notification)
                )
            }
        }
    }

    private fun applyBubbleSize(sizeDp: Int) {
        val clamped = min(SIZE_MAX_DP, max(SIZE_MIN_DP, sizeDp))
        val sizePx = clamped.dp
        if (bubbleParams.width == sizePx && bubbleParams.height == sizePx) return
        bubbleParams.width = sizePx
        bubbleParams.height = sizePx
        if (bubbleRoot.isAttachedToWindow) {
            windowManager.updateViewLayout(bubbleRoot, bubbleParams)
        }
    }

    // ── Show / Hide ───────────────────────────────────────────────────────────

    private fun showBubble() {
        if (chatPanelView?.isAttachedToWindow == true) return // chat is open
        if (bubbleRoot.visibility != View.VISIBLE) {
            bubbleRoot.visibility = View.VISIBLE
            Log.d(TAG, "Bubble shown")
            Log.d(LOG_BUBBLE, "Bubble shown")
            Log.i(LOG_OVERLAY_SHOW, "Bubble visibility → VISIBLE (no view recreation)")
        }
    }

    private fun hideBubble() {
        if (bubbleRoot.visibility != View.GONE) {
            bubbleRoot.visibility = View.GONE
            Log.d(TAG, "Bubble hidden")
            Log.d(LOG_BUBBLE, "Bubble hidden")
            Log.i(LOG_OVERLAY_HIDE, "Bubble visibility → GONE (no view recreation)")
        }
    }

    private fun safeRemoveBubble() {
        if (::bubbleRoot.isInitialized && bubbleRoot.isAttachedToWindow) {
            try {
                windowManager.removeView(bubbleRoot)
                Log.i(LOG_OVERLAY_REMOVE, "Bubble overlay view removed (service destroy only)")
            } catch (e: Exception) {
                Log.w(TAG, "Error removing bubble", e)
            }
        }
    }

    // ── Chat panel ────────────────────────────────────────────────────────────

    private fun showChatPanel() {
        if (chatPanelView?.isAttachedToWindow == true) return

        // Bind the panel to the foreground app's thread (Bug 5). The bubble is only
        // visible over an enabled app, so AppState.currentApp is that app here.
        val pkg = AppState.currentApp.value
        chatUnderlyingPackage = pkg
        chatState.setActiveApp(pkg, resolveAppName(pkg))

        val panelHeight = (resources.displayMetrics.heightPixels * 0.65f).toInt()

        // One owner provides Lifecycle, ViewModelStore, and SavedStateRegistry for the
        // ComposeView. ServiceLifecycleOwner implements all three (ViewModelStoreOwner
        // cannot be created via a SAM lambda — it exposes a viewModelStore property).
        val lifecycleOwner = ServiceLifecycleOwner().also { it.onCreate(); it.onResume() }
        chatLifecycleOwner = lifecycleOwner

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            // ADJUST_NOTHING: the system must NOT resize/pan this overlay window —
            // we lift the input above the keyboard ourselves via measured IME
            // padding. (ADJUST_RESIZE would double-shift on OEMs where it works.)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        chatPanelParams = params

        val composeView = ComposeView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent { OverlayChatPanel(state = chatState, onMinimize = ::hideChatPanel) }
        }
        chatPanelView = composeView

        bubbleRoot.visibility = View.GONE
        windowManager.addView(composeView, params)

        // Start tracking the soft keyboard so the panel can lift its input above it.
        startImeTracking(composeView)
        Log.d(TAG, "Chat panel shown (height=${panelHeight}px)")
    }

    // ── IME tracking ──────────────────────────────────────────────────────────

    /** Full physical screen height (incl. system bars) — matches the coordinate
     *  space of [View.getWindowVisibleDisplayFrame] used by the layout fallback. */
    private fun currentScreenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            metrics.heightPixels
        }
    }

    /**
     * Registers keyboard-height detection on the chat-panel view.
     *
     * Primary: a WindowInsets listener reading [WindowInsetsCompat.Type.ime].
     * Fallback: an [ViewTreeObserver.OnGlobalLayoutListener] that infers the
     * keyboard height from the visible display frame — used only until/unless
     * WindowInsets proves it reports a real IME height on this device/OEM.
     */
    private fun startImeTracking(view: View) {
        screenHeightPx = currentScreenHeight()
        insetsImeWorks = false

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (ime > 0) {
                insetsImeWorks = true
                chatState.setImeHeight(ime)
            } else if (insetsImeWorks) {
                // Insets are reliable here — honor the keyboard-closed event too.
                chatState.setImeHeight(0)
            }
            insets
        }

        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (insetsImeWorks) return@OnGlobalLayoutListener
            val v = chatPanelView ?: return@OnGlobalLayoutListener
            chatState.setImeHeight(keyboardHeightFromFrame(v))
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        globalLayoutListener = listener
    }

    /** Fallback keyboard-height estimate from the visible display frame. */
    private fun keyboardHeightFromFrame(view: View): Int {
        val frame = Rect()
        view.getWindowVisibleDisplayFrame(frame)
        val navBar = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val height = screenHeightPx - frame.bottom - navBar
        // Ignore noise (status bar etc.); only treat >20% of screen as a keyboard.
        return if (height > screenHeightPx / 5) height else 0
    }

    private fun stopImeTracking() {
        val view = chatPanelView
        if (view != null) {
            globalLayoutListener?.let { l ->
                val vto = view.viewTreeObserver
                if (vto.isAlive) vto.removeOnGlobalLayoutListener(l)
            }
            ViewCompat.setOnApplyWindowInsetsListener(view, null)
        }
        globalLayoutListener = null
        insetsImeWorks = false
        chatState.setImeHeight(0)
    }

    /** Resolves a human-readable app label from a package name (best-effort). */
    private fun resolveAppName(packageName: String): String {
        if (packageName.isBlank()) return ""
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun hideChatPanel() {
        val view = chatPanelView ?: return

        // Detach keyboard listeners first — safe even if the view is already
        // detached (stopImeTracking guards viewTreeObserver.isAlive).
        stopImeTracking()

        // onDestroy() also clears the owner's ViewModelStore.
        chatLifecycleOwner?.onDestroy()
        chatLifecycleOwner = null

        // Only remove from the window if still attached; either way, drop refs so a
        // stale listener/view can never be retained across show/hide cycles.
        if (view.isAttachedToWindow) {
            try { windowManager.removeView(view) }
            catch (e: Exception) { Log.w(TAG, "Error removing chat panel", e) }
        }
        chatPanelView = null
        chatPanelParams = null

        // The bubble was force-hidden while the panel was open. Reset the FSM state
        // and re-evaluate for the REAL underlying app. We restore currentApp
        // explicitly because closing a (focusable) overlay panel does NOT reliably
        // emit a foreground WINDOW_STATE_CHANGED on every device — without this the
        // bubble could stay hidden forever after the first chat. No timers/polling.
        isBubbleVisible = false
        currentBubblePackage = null
        val underlying = chatUnderlyingPackage.ifBlank { AppState.currentApp.value }
        AppState.updateCurrentApp(underlying)
        handleStateTransition(
            underlying,
            AllowedAppsManager.settings.value?.get(underlying),
            AppState.ownAppForeground.value,
        )
        Log.d(TAG, "Chat hidden — re-evaluated bubble for '$underlying'")
    }
}
