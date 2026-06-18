package com.supportbubble.app.services

import android.content.Context
import android.util.Log
import com.supportbubble.app.database.AppDatabase
import com.supportbubble.app.models.Message
import com.supportbubble.app.socket.SocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "OverlayChatState"

/**
 * State holder for the overlay chat panel.
 *
 * Mirrors the patterns used in [com.supportbubble.app.ui.ChatViewModel] but runs
 * outside of any Android ViewModel — it is owned directly by [OverlayService]
 * and lives for the duration of that service.
 *
 * ## Offline queue
 * Handled entirely by [ChatRepository]. When the device is offline, messages are
 * stored in Room with `pending = true` and flushed automatically on reconnect.
 *
 * ## Shared Room DB
 * The Room database and its message tables are shared with the main chat screen,
 * so the message history is always consistent between the two entry points.
 */
class OverlayChatState(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val deviceId: String = DeviceInfoService.getDeviceId(context)

    private val db = AppDatabase.getInstance(context)
    private val messageDao = db.messageDao()

    // ── Connection state ──────────────────────────────────────────────────────

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // ── Admin typing ──────────────────────────────────────────────────────────

    private val _adminTyping = MutableStateFlow(false)
    val adminTyping: StateFlow<Boolean> = _adminTyping.asStateFlow()

    // ── Input ─────────────────────────────────────────────────────────────────

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    // ── Socket ────────────────────────────────────────────────────────────────

    /**
     * [SocketManager.onConnected] is set here; [ChatRepository] intercepts it
     * (via callback chaining in its `init` block) to add the offline-flush +
     * history-sync on every reconnect.
     */
    private val socketManager = SocketManager(deviceId).apply {
        onConnected = {
            _connected.value = true
            Log.d(TAG, "Overlay socket connected")
        }
        onDisconnected = {
            _connected.value = false
            Log.d(TAG, "Overlay socket disconnected")
        }
        onError = { err ->
            _connected.value = false
            Log.w(TAG, "Overlay socket error: $err")
        }
        onAdminTyping = { isTyping ->
            _adminTyping.value = isTyping
        }
    }

    // ── Repository ────────────────────────────────────────────────────────────

    private val repository = ChatRepository(
        deviceId = deviceId,
        messageDao = messageDao,
        socketManager = socketManager,
        scope = scope,
    )

    // ── Active per-app thread (Bug 5) ─────────────────────────────────────────

    /**
     * The package whose thread this overlay panel currently shows. Set by
     * [OverlayService] (via [setActiveApp]) to the foreground app the bubble was
     * tapped in, so the overlay only ever shows that app's conversation.
     */
    private val _activePackage = MutableStateFlow("")
    val activePackage: StateFlow<String> = _activePackage.asStateFlow()
    private var activeAppName: String = ""

    // ── Messages (filtered to the active thread) ──────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> = _activePackage
        .flatMapLatest { pkg -> repository.messagesForPackage(pkg) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    // ── Manual refresh state ──────────────────────────────────────────────────

    /** True while a manual / pull-to-refresh sync is in flight (drives the spinner). */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * Current soft-keyboard (IME) height in pixels, fed by [OverlayService]'s
     * keyboard tracking. The panel turns this into animated bottom padding so the
     * input field and send button always stay above the keyboard in the overlay
     * window (where Compose's own `imePadding()` does not receive insets). 0 when
     * the keyboard is closed → panel keeps its normal size.
     */
    private val _imeHeightPx = MutableStateFlow(0)
    val imeHeightPx: StateFlow<Int> = _imeHeightPx.asStateFlow()

    /** Called by [OverlayService] whenever the detected keyboard height changes. */
    fun setImeHeight(px: Int) {
        _imeHeightPx.value = px.coerceAtLeast(0)
    }

    /**
     * Serializes ALL refresh + mark-read work so the panel-open flow is strictly
     * "fetch complete → read persisted", and a concurrent manual refresh can
     * never interleave a history upsert between them (which would re-show the dot).
     */
    private val refreshMutex = Mutex()

    /**
     * True when the active thread has unread admin messages — drives the red dot
     * on the Refresh button. Reactive: updates the instant Room changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hasUnread: StateFlow<Boolean> = _activePackage
        .flatMapLatest { pkg -> repository.unreadCountForPackage(pkg) }
        .map { it > 0 }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), false)

    /**
     * Binds the panel to a specific app thread. Called before the panel is shown.
     */
    fun setActiveApp(packageName: String, appName: String) {
        _activePackage.value = packageName
        activeAppName = appName
        Log.d(TAG, "Overlay thread → pkg='$packageName' app='$appName'")
    }

    // ── Typing debounce ───────────────────────────────────────────────────────

    private var typingJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        socketManager.connect()
        // Device registration (idempotent — updates lastSeen / lastApp on server)
        scope.launch(Dispatchers.IO) {
            repository.registerDevice(DeviceInfoService.getDeviceInfo(context))
        }
        // History is fetched by ChatRepository on every successful connect —
        // no need to call it here separately.
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun onInputChanged(text: String) {
        _inputText.value = text
        typingJob?.cancel()
        if (text.isNotBlank()) {
            repository.emitTyping(true)
            typingJob = scope.launch {
                delay(1_500L)
                repository.emitTyping(false)
            }
        } else {
            repository.emitTyping(false)
        }
    }

    /**
     * Delegates fully to [ChatRepository.sendMessage].
     * The repository handles the Room insert and socket emit / offline queuing.
     */
    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isBlank()) return
        _inputText.value = ""
        typingJob?.cancel()
        repository.emitTyping(false)
        repository.sendMessage(text, _activePackage.value, activeAppName)
    }

    /** Mark admin messages as read — call when the chat panel becomes visible. */
    fun onChatVisible() {
        repository.markAsRead(_activePackage.value)
    }

    /**
     * Panel-open sequence: pull the latest messages, THEN mark the thread read —
     * in a single coroutine so the mark-read always lands AFTER the history
     * upsert. If they were not ordered, a late [ChatRepository.fetchHistory]
     * could write the server's `read = false` back over the just-applied local
     * read state and re-show the red dot (race fixed here).
     */
    fun onChatOpened() {
        val pkg = _activePackage.value
        scope.launch(Dispatchers.IO) {
            // withLock WAITS for any in-flight manual refresh to finish first,
            // then runs our own refresh and persists the read state — all inside
            // the lock, so no other fetch can land between them.
            refreshMutex.withLock {
                _refreshing.value = true
                try {
                    val ok = repository.refreshMessages()
                    Log.d(TAG, "onChatOpened refresh: success=$ok")
                } finally {
                    _refreshing.value = false
                }
                // Awaitable mark-read, ordered strictly AFTER the fetch upsert.
                repository.markAsReadAwait(pkg)
            }
        }
    }

    /**
     * Manual refresh — pulls the latest messages from the server (socket if
     * connected, REST history otherwise) into Room. The [messages] Flow then
     * re-emits automatically. Safe to call from the Refresh button or pull-to-
     * refresh. Skips if a refresh is already running.
     *
     * Manual refresh deliberately does NOT mark messages read — the red dot
     * stays until the user actually opens/reads the thread.
     *
     * Works even with MainActivity fully closed: the repository and Room DB are
     * owned by [OverlayService], independent of any Activity.
     */
    fun refreshMessages() {
        scope.launch(Dispatchers.IO) {
            if (!refreshMutex.tryLock()) {
                Log.d(TAG, "refreshMessages: already running — skipped")
                return@launch
            }
            _refreshing.value = true
            try {
                val ok = repository.refreshMessages()
                Log.d(TAG, "refreshMessages: success=$ok")
            } finally {
                _refreshing.value = false
                refreshMutex.unlock()
            }
        }
    }

    /** Disconnect the socket and cancel pending work. Call from [OverlayService.onDestroy]. */
    fun destroy() {
        typingJob?.cancel()
        socketManager.disconnect()
        Log.d(TAG, "OverlayChatState destroyed")
    }
}
