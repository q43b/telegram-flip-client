package com.q43b.tgflip

import android.Manifest
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

// Shows one chat's latest messages and adds new ones live.
// Opening a chat does not mark it as read: that sends a read receipt, so it stays a deliberate action.
class ChatActivity : AppCompatActivity() {
    private lateinit var connection: TextView
    private lateinit var status: TextView
    private lateinit var messageList: OkListView
    private lateinit var messageAdapter: ArrayAdapter<CharSequence>
    private val rows = mutableListOf<CharSequence>()  // the adapter's own list, for updating one row
    private lateinit var messageInput: EditText
    private lateinit var softKeys: SoftKeyBar
    private val messages = mutableListOf<Message>()
    private var messageLimit = PAGE_SIZE  // grows by a page with each "Load older messages"
    private var hasOlder = false  // the "Load older messages" row is shown
    private var unread = 0
    private var sending = false
    private var markingRead = false

    // Entries of the menu that OK on a message opens. New message features go here.
    private val messageActions = listOf(
        MessageAction(R.string.action_view_photo, isAvailable = { it.image != null }) {
            ImageActivity.start(this, chatId, it.id)
        },
        MessageAction(R.string.action_play_video, isAvailable = { it.video != null }) {
            VideoActivity.start(this, chatId, it.id)
        },
        MessageAction(R.string.action_play, isAvailable = { it.voice != null && voice.currentId != it.id }) {
            voice.play(chatId, it.id)
        },
        MessageAction(R.string.action_stop, isAvailable = { voice.currentId == it.id }) { voice.stop() },
        // Call records can only be deleted: Telegram refuses replies to them and forwarding them
        MessageAction(R.string.action_reply, isAvailable = { it.call == null }, perform = ::startReply),
        MessageAction(R.string.action_go_to_original, isAvailable = { it.replyToId != null }, perform = ::goToOriginal),
        // Own text messages only; Telegram itself refuses edits after its time limit
        MessageAction(R.string.action_edit, isAvailable = { it.out && it.text.isNotEmpty() }, perform = ::startEdit),
        MessageAction(R.string.action_forward, isAvailable = { it.call == null }, perform = ::startForward),
        MessageAction(R.string.action_delete, perform = ::startDelete),
        // Other people's messages: in groups senderId says whose, in a private chat it's the chat's person
        MessageAction(R.string.action_view_profile, isAvailable = { senderOf(it) != null }, perform = ::viewSenderProfile),
        // Groups only: a private chat is already that
        MessageAction(R.string.action_message_privately, isAvailable = { it.senderId != null }, perform = ::messageSender),
    )

    // What the message box does next: a plain message, a reply, or an edit.
    // The bar above the box says which; Back cancels it instead of leaving the chat.
    private lateinit var composeBar: TextView
    private var replyingTo: Message? = null
    private var editing: Message? = null
    private val cancelComposeOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = cancelCompose()
    }

    private lateinit var api: ApiClient
    private lateinit var cache: LocalCache
    private lateinit var voice: VoicePlayer
    private lateinit var recorder: VoiceRecorder

    // Android asks the user once; after a yes, recording starts right away
    private val askMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recorder.start() else showStatus(getString(R.string.voice_no_microphone))
    }
    // The phone's picture picker (Kyocera's Data Folder on the flip phone); its answer is
    // the chosen picture, or nothing when Back was pressed
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let(::chooseHowToSend)
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let(::chooseHowToSendVideo)
    }
    private var chatId = 0L
    private var topicId: Long? = null  // set when this is one topic of a forum group
    private var live: LiveConnection? = null
    private var wasConnected = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chat)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            // Also make room for an on-screen keyboard, so it never covers the message box
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // The list screen only opens this after settings are saved, but be safe
        // Without a server for the current mode the screen still opens from the saved copy;
        // sending and the other actions then fail with their usual messages
        val settings = ServerSettings(this)
        api = ApiClient(settings.getAddress(), settings.getToken().orEmpty())
        cache = LocalCache(this)
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)
        if (intent.hasExtra(EXTRA_TOPIC_ID)) topicId = intent.getLongExtra(EXTRA_TOPIC_ID, 0)
        voice = VoicePlayer(this, lifecycleScope, ::refreshRow).also { it.api = api }
        recorder = VoiceRecorder(this, ::sendVoice)
        volumeControlStream = AudioManager.STREAM_MUSIC  // the volume keys set how loud voice messages play

        connection = findViewById(R.id.connection)
        status = findViewById(R.id.status)
        messageList = findViewById(R.id.message_list)
        messageList.wrapsDown = false  // Down on the newest message goes to the message box
        findViewById<TextView>(R.id.chat_title).text = intent.getStringExtra(EXTRA_TITLE)
        messageAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        messageList.adapter = messageAdapter
        // OK on a message opens its menu (OkListView takes OK before the keyboard app);
        // the click listener covers touch taps
        messageList.onOk = ::onRowChosen
        messageList.setOnItemClickListener { _, _, position, _ -> onRowChosen(position) }
        composeBar = findViewById(R.id.compose_bar)
        onBackPressedDispatcher.addCallback(this, cancelComposeOnBack)

        // OK on the keypad sends. We act on key-up and swallow key-down too,
        // so one press can never send twice.
        messageInput = findViewById(R.id.message_input)
        messageInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) {
                return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_UP) sendCurrentText()
            true
        }
        // Soft key labels: "Mark as read (N)" above F1 only while there is something to
        // mark; "Menu" above F2 always. Tapping them works too on a touchscreen.
        softKeys = SoftKeyBar(this)
        softKeys.onLeftTap(::markAsRead)
        softKeys.onRightTap(::showSoftKeyMenu)
        softKeys.setRight(getString(R.string.soft_key_menu))
        unread = intent.getIntExtra(EXTRA_UNREAD, 0)
        updateReadKey()

        // The send button of an on-screen keyboard, if the phone shows one
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentText()
                true
            } else {
                false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        wasConnected = false
        if (api.hasServer) {
            live = LiveConnection(api, lifecycleScope, ::showConnection, ::onServerEvent).also { it.start() }
        } else {
            connection.showNoServer()
        }
        lifecycleScope.launch {
            // On a fresh screen, show the messages saved last time while the real ones load
            if (messages.isEmpty()) {
                withContext(Dispatchers.IO) { cache.loadMessages(chatId, topicId) }?.let(::showMessages)
            }
            loadMessages(quiet = false)
        }
        refreshUnread()  // may have changed while the app was in the background
    }

    override fun onStop() {
        voice.stop()
        recorder.cancel()
        live?.stop()
        live = null
        super.onStop()
    }

    // Left soft key = mark as read, right soft key = the soft key menu (F1 / F2, or
    // SOFT_LEFT / SOFT_RIGHT, depending on the phone). The menu is on the right because the
    // phone's keyboard app uses F1 while typing. Caught here, before the list sees them;
    // we act on key-up, like sending.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isLeftSoftKey()) {
            if (event.action == KeyEvent.ACTION_UP) markAsRead()
            return true
        }
        if (event.isRightSoftKey()) {
            if (event.action == KeyEvent.ACTION_UP) showSoftKeyMenu()
            return true
        }
        // D-pad Right in a group (or channel, or one of its topics) opens the group info.
        // Only from the messages: in the text box Right keeps moving the cursor.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !isPrivateChat() && !messageInput.hasFocus()) {
            if (event.action == KeyEvent.ACTION_UP) {
                GroupInfoActivity.start(this, chatId, intent.getStringExtra(EXTRA_CHAT_NAME).orEmpty())
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // People's ids are positive, groups' and channels' negative (Telethon's peer ids)
    private fun isPrivateChat() = chatId > 0

    // Whose profile "View profile" opens: the sender in a group, the other person in a
    // private chat; null for my own messages and channel posts
    private fun senderOf(message: Message): Long? = when {
        message.senderId != null -> message.senderId
        isPrivateChat() && !message.out -> chatId
        else -> null
    }

    private fun viewSenderProfile(message: Message) {
        val id = senderOf(message) ?: return
        // In a private chat, the name is the chat's; "Send message" only from groups
        val name = message.sender ?: intent.getStringExtra(EXTRA_CHAT_NAME).orEmpty()
        ProfileActivity.start(this, id, name, canMessage = !isPrivateChat())
    }

    // Opens the private chat with the sender; if there is none yet, Telegram creates it
    // with the first message sent there
    private fun messageSender(message: Message) {
        val id = message.senderId ?: return
        start(this, Chat(id, message.sender.orEmpty(), unread = 0, forum = false))
    }

    // F2: Media and Stickers are placeholders for version 2; Quick phrases works
    private fun showSoftKeyMenu() {
        val items = listOf(R.string.quick_phrases, R.string.menu_stickers, R.string.menu_media, R.string.menu_record_voice)
        MessageMenu.choose(this, getString(R.string.soft_key_menu), items.map(::getString)) { which ->
            when (items[which]) {
                R.string.quick_phrases -> showQuickPhrases()
                R.string.menu_record_voice -> startRecording()
                R.string.menu_media -> showMediaMenu()
                else -> showComingLater()
            }
        }
    }

    // Send media: a photo, or (later) a video
    private fun showMediaMenu() {
        val items = listOf(R.string.menu_send_photo, R.string.menu_send_video)
        MessageMenu.choose(this, getString(R.string.menu_media_title), items.map(::getString)) { which ->
            when (items[which]) {
                R.string.menu_send_photo -> startPick(pickPhoto, "image/*")
                R.string.menu_send_video -> startPick(pickVideo, "video/*")
                else -> showComingLater()
            }
        }
    }

    private fun showComingLater() {
        val text = getString(R.string.coming_later)
        showStatus(text)
        hideStatusLater(text)
    }

    private fun startRecording() {
        if (!recorder.isSupported) {
            showStatus(getString(R.string.voice_record_needs_10))
            return
        }
        voice.stop()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            recorder.start()
        } else {
            askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ACTION_PICK goes straight to the phone's own picker (Kyocera's Data Folder, for photos
    // and videos alike); phones without one get the general file picker
    private fun startPick(launcher: ActivityResultLauncher<Intent>, type: String) {
        val pick = Intent(Intent.ACTION_PICK).setType(type)
        val getContent = Intent(Intent.ACTION_GET_CONTENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE)
        try {
            launcher.launch(pick)
        } catch (e: ActivityNotFoundException) {
            try {
                launcher.launch(getContent)
            } catch (e: ActivityNotFoundException) {
                showStatus(getString(R.string.photo_no_picker))
            }
        }
    }

    private fun chooseHowToSendVideo(uri: Uri) {
        val items = listOf(R.string.video_send_optimized, R.string.video_send_original)
        MessageMenu.choose(this, getString(R.string.video_send_title), items.map(::getString)) { which ->
            if (which == 0) optimizeAndSend(uri) else sendVideo(uri)
        }
    }

    // Shrinks the video on the phone first (VideoOptimizer, into a file in cacheDir that is
    // deleted once sent or given up), then sends that
    private fun optimizeAndSend(uri: Uri) {
        if (sending) return
        sending = true
        showStatus(getString(R.string.video_optimizing, 0))
        val optimized = File(cacheDir, "optimized.mp4")
        lifecycleScope.launch {
            try {
                VideoOptimizer.optimize(this@ChatActivity, uri, optimized) { percent ->
                    showStatus(getString(R.string.video_optimizing, percent))
                }
                sending = false
                sendVideo(Uri.fromFile(optimized), cleanup = optimized)
            } catch (e: IOException) {
                sending = false
                optimized.delete()
                showStatus(getString(R.string.video_optimize_failed))
            }
        }
    }

    // Sends the video file, as a reply if one is being written; text in the message box
    // becomes its caption. The status line counts the upload to the server, then says the
    // server is passing it on to Telegram (the long part). If sending fails, a dialog offers
    // to try again. cleanup: a temporary file (an optimized copy) to delete when done.
    private fun sendVideo(uri: Uri, cleanup: File? = null) {
        if (sending) return
        sending = true
        showStatus(getString(R.string.video_preparing))
        val caption = if (editing == null) messageInput.text.toString().trim() else ""
        val replyToId = replyingTo?.id
        var lastProgress = 0L
        lifecycleScope.launch {
            try {
                val (id, video) = withContext(Dispatchers.IO) {
                    val video = VideoSender.prepare(this@ChatActivity, uri)
                    val id = api.sendVideo(
                        chatId, topicId, video, { VideoSender.open(this@ChatActivity, uri) }, caption, replyToId,
                        onProgress = { done, total ->
                            val now = System.currentTimeMillis()
                            if (now - lastProgress >= 300) {
                                lastProgress = now
                                runOnUiThread {
                                    showStatus(getString(R.string.video_sending,
                                        Formatter.formatShortFileSize(this@ChatActivity, done),
                                        Formatter.formatShortFileSize(this@ChatActivity, total)))
                                }
                            }
                        },
                        onSent = { runOnUiThread { showStatus(getString(R.string.video_server_sending)) } },
                    )
                    VideoSender.keepCopy(this@ChatActivity, uri, chatId, id)
                    id to video
                }
                cleanup?.delete()
                if (caption.isNotEmpty()) messageInput.text.clear()
                if (replyToId != null) cancelCompose()
                status.visibility = View.GONE
                val sent = Message(id, caption, out = true, date = MessageFormat.now(), replyToId = replyToId,
                    video = VideoInfo(video.seconds, video.width, video.height, video.size))
                addMessage(sent, follow = true)
            } catch (e: VideoSender.TooLargeException) {
                cleanup?.delete()
                showStatus(getString(R.string.video_send_too_big))
            } catch (e: ApiClient.TooBigException) {
                cleanup?.delete()
                showStatus(getString(R.string.video_send_too_big))
            } catch (e: IOException) {
                status.visibility = View.GONE
                AlertDialog.Builder(this@ChatActivity)
                    .setMessage(R.string.video_send_failed)
                    .setPositiveButton(R.string.voice_retry) { _, _ -> sendVideo(uri, cleanup) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> cleanup?.delete() }
                    .setCancelable(false)
                    .show()
            } finally {
                sending = false
            }
        }
    }

    private fun chooseHowToSend(uri: Uri) {
        val items = listOf(R.string.photo_send_optimized, R.string.photo_send_original)
        MessageMenu.choose(this, getString(R.string.photo_send_title), items.map(::getString)) { which ->
            sendPhoto(uri, optimized = which == 0)
        }
    }

    // Sends the picture, as a reply if one is being written; text in the message box becomes
    // its caption. If sending fails, a dialog offers to try again.
    private fun sendPhoto(uri: Uri, optimized: Boolean) {
        if (sending) return
        sending = true
        showStatus(getString(R.string.photo_sending))
        val caption = if (editing == null) messageInput.text.toString().trim() else ""
        val replyToId = replyingTo?.id
        lifecycleScope.launch {
            try {
                val (id, prepared) = withContext(Dispatchers.IO) {
                    val prepared = PhotoSender.prepare(this@ChatActivity, uri, optimized)
                    api.sendPhoto(chatId, topicId, prepared.bytes, prepared.mimeType, caption, replyToId) to prepared
                }
                if (caption.isNotEmpty()) messageInput.text.clear()
                if (replyToId != null) cancelCompose()
                status.visibility = View.GONE
                val sent = Message(id, caption, out = true, date = MessageFormat.now(), replyToId = replyToId,
                    image = ImageInfo(prepared.width, prepared.height))
                addMessage(sent, follow = true)
            } catch (e: PhotoSender.TooLargeException) {
                showStatus(getString(R.string.photo_too_large))
            } catch (e: IOException) {
                status.visibility = View.GONE
                AlertDialog.Builder(this@ChatActivity)
                    .setMessage(R.string.photo_send_failed)
                    .setPositiveButton(R.string.voice_retry) { _, _ -> sendPhoto(uri, optimized) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } finally {
                sending = false
            }
        }
    }

    // Sends the recording, as a reply if one is being written. If that fails, a dialog
    // offers to try again, so a recording is not lost to a bad connection.
    private fun sendVoice(file: File, seconds: Int) {
        sending = true
        showStatus(getString(R.string.voice_sending))
        val replyToId = replyingTo?.id
        lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) {
                    val id = api.sendVoice(chatId, topicId, file.readBytes(), seconds, replyToId)
                    // Keep it as the saved copy of the new message, so playing it needs no download
                    val saved = File(MediaCache.folder(this@ChatActivity, "voice").also { it.mkdirs() }, "${chatId}_$id.ogg")
                    if (!file.renameTo(saved)) file.delete()
                    id
                }
                if (replyToId != null) cancelCompose()
                status.visibility = View.GONE
                val sent = Message(id, "", out = true, date = MessageFormat.now(), replyToId = replyToId,
                    voice = VoiceInfo(seconds))
                addMessage(sent, follow = true)
            } catch (e: IOException) {
                status.visibility = View.GONE
                AlertDialog.Builder(this@ChatActivity)
                    .setMessage(R.string.voice_send_failed)
                    .setPositiveButton(R.string.voice_retry) { _, _ -> sendVoice(file, seconds) }
                    .setNegativeButton(R.string.voice_discard) { _, _ -> file.delete() }
                    .setCancelable(false)
                    .show()
            } finally {
                sending = false
            }
        }
    }

    private fun showQuickPhrases() {
        val phrases = QuickPhrases(this).load()
        if (phrases.isEmpty()) {
            showStatus(getString(R.string.no_quick_phrases))
            return
        }
        val items = phrases.map { MessageFormat.snippet(it, 40) }
        MessageMenu.choose(this, getString(R.string.quick_phrases), items) { insertPhrase(phrases[it]) }
    }

    // Puts the phrase into the message box at the cursor (replacing a selection), with a
    // space in front if it would otherwise stick to the previous word, then focuses the box
    private fun insertPhrase(phrase: String) {
        val text = messageInput.text
        val start = minOf(messageInput.selectionStart, messageInput.selectionEnd).coerceAtLeast(0)
        val end = maxOf(messageInput.selectionStart, messageInput.selectionEnd).coerceAtLeast(0)
        val needsSpace = start > 0 && !text[start - 1].isWhitespace()
        text.replace(start, end, if (needsSpace) " $phrase" else phrase)
        // On the next frame: the menu dialog is still closing now and could take focus back
        messageInput.post { messageInput.requestFocus() }
    }

    private fun showMessageMenu(message: Message) =
        MessageMenu.show(this, snippet(message), message, messageActions)

    // Does nothing while there is nothing unread, like the web version
    private fun markAsRead() {
        if (markingRead || unread == 0) return
        markingRead = true
        showStatus(getString(R.string.marking_read))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { api.markRead(chatId, topicId) }
                unread = 0
                updateReadKey()
                showStatus(getString(R.string.marked_read))
                hideStatusLater(getString(R.string.marked_read))
            } catch (e: IOException) {
                showStatus(getString(R.string.error_mark_read))
            } finally {
                markingRead = false
            }
        }
    }

    private fun updateReadKey() {
        softKeys.setLeft(if (unread > 0) getString(R.string.soft_key_mark_read, unread) else null)
    }

    // Asks the server for this chat's (or topic's) unread count. Only the chat and topic
    // lists have it; if the chat is not among the first 20, keep what we have.
    private fun refreshUnread() {
        val topicId = topicId
        lifecycleScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    if (topicId == null) api.getChats().find { it.id == chatId }?.unread
                    else api.getTopics(chatId).find { it.id == topicId }?.unread
                } ?: return@launch
                unread = fresh
                updateReadKey()
            } catch (e: IOException) {
                // The connection bar already shows network trouble
            }
        }
    }

    // Hides the status after a moment, unless something else has replaced it meanwhile
    private fun hideStatusLater(text: String) {
        lifecycleScope.launch {
            delay(2_000)
            if (status.text.toString() == text) status.visibility = View.GONE
        }
    }

    // The "Load older messages" row, when shown, sits above the messages and shifts them by one
    private val headerRows get() = if (hasOlder) 1 else 0

    private fun messageAt(position: Int) = messages.getOrNull(position - headerRows)

    private fun onRowChosen(position: Int) {
        if (hasOlder && position == 0) loadOlder() else messageAt(position)?.let(::showMessageMenu)
    }

    // Asks for one more page, then keeps the highlight on the message that was at the top,
    // so Up continues into the newly loaded older ones
    private fun loadOlder() {
        messageLimit += PAGE_SIZE
        loadMessages(quiet = false, keepId = messages.firstOrNull()?.id)
    }

    // From the message menu, on a reply: highlight the message it replies to. If it isn't
    // loaded yet, load older pages until it shows up (at most MAX_SEARCH_PAGES), keeping
    // the highlight on the reply meanwhile.
    private fun goToOriginal(reply: Message) {
        val originalId = reply.replyToId ?: return
        if (highlight(originalId)) return
        lifecycleScope.launch {
            showStatus(getString(R.string.searching_original))
            for (page in 1..MAX_SEARCH_PAGES) {
                messageLimit += PAGE_SIZE
                val loaded = try {
                    withContext(Dispatchers.IO) {
                        api.getMessages(chatId, topicId, messageLimit).also { cache.saveMessages(chatId, topicId, it) }
                    }
                } catch (e: IOException) {
                    showStatus(getString(R.string.error_no_server))
                    return@launch
                }
                val found = loaded.any { it.id == originalId }
                showMessages(loaded, keepId = if (found) originalId else reply.id)
                if (found) return@launch  // showMessages also hid the status line
                if (!hasOlder) break  // reached the start of the chat
            }
            showStatus(getString(R.string.original_not_found))
        }
    }

    // Moves the highlight to a loaded message; false if it isn't loaded
    private fun highlight(id: Long): Boolean {
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0) return false
        messageList.setSelection(index + headerRows)
        messageList.requestFocus()
        return true
    }

    // keepId: the message to highlight afterwards; null = the newest, at the bottom
    private fun loadMessages(quiet: Boolean, keepId: Long? = null) {
        if (!quiet) showStatus(getString(R.string.loading_messages))
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    api.getMessages(chatId, topicId, messageLimit).also { cache.saveMessages(chatId, topicId, it) }
                }
                showMessages(loaded, keepId)
            } catch (e: BadTokenException) {
                showStatus(getString(R.string.error_bad_token))
            } catch (e: NoServerException) {
                showStatus(getString(R.string.server_not_set_short))
            } catch (e: IOException) {
                if (!quiet) {
                    val text = if (messages.isNotEmpty()) R.string.offline_saved else R.string.error_no_server
                    showStatus(getString(text))
                }
            }
        }
    }

    private fun showMessages(loaded: List<Message>, keepId: Long? = null) {
        messages.clear()
        messages.addAll(loaded)
        // A full page means the chat may go further back
        hasOlder = loaded.size >= messageLimit
        refreshRows()
        if (loaded.isEmpty()) showStatus(getString(R.string.no_messages)) else status.visibility = View.GONE
        // Newest message is at the bottom; start there, unless asked to keep a message in view
        val keepIndex = messages.indexOfFirst { it.id == keepId }
        messageList.setSelection(if (keepIndex >= 0) keepIndex + headerRows else messageAdapter.count - 1)
        messageList.requestFocus()
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun showConnection(state: LiveConnection.Status) {
        connection.showConnection(state)
        // After a reconnect we may have missed messages, so refetch
        if (state == LiveConnection.Status.CONNECTED) {
            if (wasConnected) {
                loadMessages(quiet = true)
                refreshUnread()
            }
            wasConnected = true
        }
    }

    private fun onServerEvent(event: JSONObject) {
        if (IncomingCallAlert.handle(this, event)) return
        val type = event.optString("type")
        if (type == "deleted") {
            onLiveDelete(event)  // may come without a chat_id
            return
        }
        if (event.optLong("chat_id") != chatId) return
        when (type) {
            "message" -> if (isInThisTopic(event)) onLiveMessage(event)
            "edited" -> onLiveEdit(event)
            "read" -> refreshUnread()  // read on another device, maybe only partly
        }
    }

    // Edited here or on another device. Only messages on screen are updated.
    private fun onLiveEdit(event: JSONObject) {
        val id = event.optLong("id")
        val old = messages.find { it.id == id } ?: return
        replaceMessage(old.copy(text = event.optString("text"), edited = event.optBoolean("edited")))
    }

    // Telegram names the chat only for channels and supergroups. Without a chat_id the
    // ids belong to private chats or basic groups, where message ids are unique across
    // the whole account, but they could clash with a channel's own numbering.
    private fun onLiveDelete(event: JSONObject) {
        val inThisChat = if (event.isNull("chat_id")) !isChannel() else event.optLong("chat_id") == chatId
        if (!inThisChat) return
        val ids = event.optJSONArray("ids") ?: return
        removeMessages(List(ids.length()) { ids.getLong(it) }.toSet())
    }

    // Telegram ("marked") ids of channels and supergroups start at -1000000000000
    private fun isChannel() = chatId <= -1_000_000_000_000L

    // The server sends topic_id null for ordinary chats and for a forum's General topic
    private fun isInThisTopic(event: JSONObject): Boolean {
        val topicId = topicId ?: return true
        val eventTopic = if (event.isNull("topic_id")) null else event.optLong("topic_id")
        return if (topicId == Topic.GENERAL_ID) eventTopic == null else eventTopic == topicId
    }

    private fun onLiveMessage(event: JSONObject) {
        val message = try {
            Message.fromJson(event)
        } catch (e: JSONException) {
            return
        }
        // Follow new messages only if the user is reading the bottom of the chat
        val selected = messageList.selectedItemPosition
        val atBottom = selected == AdapterView.INVALID_POSITION || selected >= messageAdapter.count - 1
        if (addMessage(message, follow = atBottom) && !message.out) {
            unread++
            updateReadKey()
        }
    }

    // Our own sent messages arrive twice: from /send and as a live event. The id check keeps one.
    // Returns false if the message was already shown.
    private fun addMessage(message: Message, follow: Boolean): Boolean {
        if (messages.any { it.id == message.id }) return false
        messages.add(message)
        messageAdapter.add(formatMessage(message))
        if (follow) messageList.setSelection(messageAdapter.count - 1)
        saveMessages()
        return true
    }

    private fun replaceMessage(updated: Message) {
        val index = messages.indexOfFirst { it.id == updated.id }
        if (index < 0) return
        messages[index] = updated
        refreshRows()  // also refreshes replies quoting it
        saveMessages()
    }

    private fun removeMessages(ids: Set<Long>) {
        if (messages.none { it.id in ids }) return
        messages.removeAll { it.id in ids }
        if (replyingTo?.id in ids || editing?.id in ids) cancelCompose()
        refreshRows()
        saveMessages()
    }

    // Rebuilds every row; the list keeps the highlight on the same position
    private fun refreshRows() {
        messageAdapter.clear()
        if (hasOlder) messageAdapter.add(getString(R.string.load_older))
        messageAdapter.addAll(messages.map(::formatMessage))
    }

    // Only this message's line, e.g. while its voice message plays
    private fun refreshRow(messageId: Long) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        rows[index + headerRows] = formatMessage(messages[index])
        messageAdapter.notifyDataSetChanged()
    }

    // Keeps the saved copy current; saves a snapshot, since the list keeps changing
    private fun saveMessages() {
        val snapshot = messages.toList()
        lifecycleScope.launch(Dispatchers.IO) { cache.saveMessages(chatId, topicId, snapshot) }
    }

    // OK in the box: saves the edit, or sends a new message (maybe as a reply)
    private fun sendCurrentText() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty() || sending) return
        val editing = editing
        if (editing != null) {
            saveEdit(editing, text)
            return
        }

        sending = true
        showStatus(getString(R.string.sending))
        val replyToId = replyingTo?.id
        lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { api.sendMessage(chatId, topicId, text, replyToId) }
                messageInput.text.clear()
                cancelCompose()
                status.visibility = View.GONE
                val sent = Message(id, text, out = true, date = MessageFormat.now(), replyToId = replyToId)
                addMessage(sent, follow = true)
            } catch (e: IOException) {
                // The text stays in the box, so OK again retries
                showStatus(getString(R.string.error_send))
            } finally {
                sending = false
            }
        }
    }

    private fun saveEdit(message: Message, text: String) {
        sending = true
        showStatus(getString(R.string.saving_edit))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { api.editMessage(chatId, message.id, text) }
                cancelCompose()
                status.visibility = View.GONE
                // The live "edited" event will confirm this; showing it now feels instant
                replaceMessage(message.copy(text = text, edited = true))
            } catch (e: IOException) {
                // Text stays in the box: fix it and OK again, or Back to give up
                showStatus(getString(R.string.error_edit))
            } finally {
                sending = false
            }
        }
    }

    // From the message menu: the next message sent from the box replies to this one
    private fun startReply(message: Message) {
        cancelCompose()
        replyingTo = message
        showComposeBar(getString(R.string.replying_to, snippet(message)))
    }

    // From the message menu: the box gets the message's text; OK saves the change
    private fun startEdit(message: Message) {
        cancelCompose()
        editing = message
        messageInput.setText(message.text)
        messageInput.setSelection(messageInput.text.length)  // cursor at the end
        showComposeBar(getString(R.string.editing, snippet(message)))
    }

    private fun showComposeBar(text: String) {
        composeBar.text = text
        composeBar.visibility = View.VISIBLE
        cancelComposeOnBack.isEnabled = true
        // On the next frame: the menu dialog is still closing now and could take focus back
        messageInput.post { messageInput.requestFocus() }
    }

    private fun cancelCompose() {
        if (editing != null) messageInput.text.clear()  // it held the old text, not a draft
        replyingTo = null
        editing = null
        composeBar.visibility = View.GONE
        cancelComposeOnBack.isEnabled = false
    }

    // From the message menu: pick a chat from the list, then forward there
    private fun startForward(message: Message) {
        lifecycleScope.launch {
            // Fresh list if the server answers, otherwise the saved one
            val targets = withContext(Dispatchers.IO) {
                try {
                    api.getChats()
                } catch (e: IOException) {
                    cache.loadChats()
                }
            }.orEmpty()
            if (targets.isEmpty()) {
                showStatus(getString(R.string.error_no_server))
                return@launch
            }
            MessageMenu.choose(this@ChatActivity, getString(R.string.forward_title), targets.map { it.name }) {
                forward(message, targets[it])
            }
        }
    }

    // Into a topic group, Telegram puts it in General
    private fun forward(message: Message, target: Chat) {
        showStatus(getString(R.string.forwarding))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { api.forwardMessage(chatId, message.id, target.id) }
                val done = getString(R.string.forwarded_to, target.name)
                showStatus(done)
                hideStatusLater(done)
            } catch (e: IOException) {
                showStatus(getString(R.string.error_forward))
            }
        }
    }

    // From the message menu: ask the server which deletes Telegram allows, then offer
    // only those. Nothing is deleted until one is picked; Back cancels.
    private fun startDelete(message: Message) {
        showStatus(getString(R.string.checking_delete))
        lifecycleScope.launch {
            val options = try {
                withContext(Dispatchers.IO) { api.getDeleteOptions(chatId, message.id) }
            } catch (e: IOException) {
                showStatus(getString(R.string.error_delete))
                return@launch
            }
            status.visibility = View.GONE
            val choices = buildList {
                if (options.forMe) add(R.string.delete_for_me to false)
                if (options.forEveryone) add(R.string.delete_for_everyone to true)
            }
            if (choices.isEmpty()) {
                showStatus(getString(R.string.cant_delete))
                return@launch
            }
            MessageMenu.choose(this@ChatActivity, getString(R.string.delete_title), choices.map { getString(it.first) }) {
                delete(message, forEveryone = choices[it].second)
            }
        }
    }

    private fun delete(message: Message, forEveryone: Boolean) {
        showStatus(getString(R.string.deleting))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { api.deleteMessage(chatId, message.id, forEveryone) }
                removeMessages(setOf(message.id))
                val done = getString(R.string.deleted)
                showStatus(done)
                hideStatusLater(done)
            } catch (e: IOException) {
                showStatus(getString(R.string.error_delete))
            }
        }
    }

    // "14:05 You: hi" / "14:05 Anna: hi" (others in groups, name in bold) / "14:05 hi" (private).
    // A reply gets a first line quoting the start of the original, if it is loaded.
    // Edited messages get "(edited)" after the time. Calls get their own colored line.
    private fun formatMessage(message: Message): CharSequence {
        val call = message.call
        if (call != null) return formatCall(message, call)
        val text = voiceLine(message)?.let { if (message.text.isEmpty()) it else "$it ${message.text}" }
            ?: message.image?.let { MessageFormat.photo(this, message.text) }
            ?: message.video?.let { MessageFormat.video(this, it, message.text) }
            ?: message.text.ifEmpty { getString(R.string.media) }
        val time = if (message.edited) "${formatDate(message.date)} ${getString(R.string.edited)}" else formatDate(message.date)
        val line = SpannableStringBuilder("$time ")
        val sender = message.sender
        when {
            message.out -> line.append("${getString(R.string.you)}: ")
            sender != null -> {
                val start = line.length
                line.append("${MessageFormat.snippet(sender, SENDER_LENGTH)}: ")
                line.setSpan(StyleSpan(Typeface.BOLD), start, line.length, 0)
            }
        }
        line.append(text)
        val replyToId = message.replyToId ?: return line
        val quoted = messages.find { it.id == replyToId }?.let(::snippet) ?: "…"
        return SpannableStringBuilder("↪ $quoted\n").append(line)
    }

    // "14:05 ☎ Missed call" in italics: red when someone's call went unanswered, else blue
    private fun formatCall(message: Message, call: CallInfo): CharSequence {
        val line = SpannableString("${formatDate(message.date)} ${MessageFormat.call(this, call, message.out)}")
        val color = if (MessageFormat.isUnanswered(call, message.out)) R.color.call_unanswered else R.color.call_answered
        line.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, color)), 0, line.length, 0)
        line.setSpan(StyleSpan(Typeface.ITALIC), 0, line.length, 0)
        return line
    }

    // "🎤 Voice message 0:12", counting along while it plays; null for other messages
    private fun voiceLine(message: Message): String? {
        val info = message.voice ?: return null
        return when {
            voice.isLoading(message.id) -> getString(R.string.voice_loading)
            voice.currentId == message.id -> MessageFormat.voicePlaying(info, voice.position)
            else -> MessageFormat.voice(this, info)
        }
    }

    // The start of a message on one line, for quotes, the reply bar and menu titles
    private fun snippet(message: Message): String {
        val call = message.call
        val text = if (call != null) MessageFormat.call(this, call, message.out)
        else message.voice?.let { MessageFormat.voice(this, it) }
            ?: message.image?.let { MessageFormat.photo(this, message.text) }
            ?: message.video?.let { MessageFormat.video(this, it, message.text) }
            ?: message.text.ifEmpty { getString(R.string.media) }
        return MessageFormat.snippet(text, SNIPPET_LENGTH)
    }

    private fun formatDate(date: String) = MessageFormat.time(date)

    companion object {
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_TOPIC_ID = "topic_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CHAT_NAME = "chat_name"  // without the topic
        private const val EXTRA_UNREAD = "unread"
        private const val SNIPPET_LENGTH = 30
        private const val SENDER_LENGTH = 16
        private const val PAGE_SIZE = 20
        private const val MAX_SEARCH_PAGES = 10  // "Go to original" looks at most this far back

        // topic = null for an ordinary chat; otherwise one topic of a forum group
        fun start(context: Context, chat: Chat, topic: Topic? = null) {
            val intent = Intent(context, ChatActivity::class.java)
            intent.putExtra(EXTRA_CHAT_ID, chat.id)
            intent.putExtra(EXTRA_CHAT_NAME, chat.name)
            if (topic == null) {
                intent.putExtra(EXTRA_TITLE, chat.name)
                intent.putExtra(EXTRA_UNREAD, chat.unread)
            } else {
                intent.putExtra(EXTRA_TOPIC_ID, topic.id)
                intent.putExtra(EXTRA_TITLE, "${chat.name} › ${topic.title}")
                intent.putExtra(EXTRA_UNREAD, topic.unread)
            }
            context.startActivity(intent)
        }
    }
}
