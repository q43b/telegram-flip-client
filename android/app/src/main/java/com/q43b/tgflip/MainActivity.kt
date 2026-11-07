package com.q43b.tgflip

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var settings: ServerSettings
    private lateinit var cache: LocalCache
    private lateinit var connection: TextView
    private lateinit var status: TextView
    private lateinit var chatList: ListView
    private lateinit var chatAdapter: ArrayAdapter<CharSequence>
    private lateinit var photos: PhotoLoader
    private var previewColor = Color.GRAY  // the theme's secondary text color, set in onCreate
    private lateinit var softKeys: SoftKeyBar
    private var chats: List<Chat> = emptyList()  // as shown (hidden folders' chats left out)
    private var loadedChats: List<Chat> = emptyList()  // as last loaded
    // The chats of all folders, fetched together each refresh: the unread counts on the
    // folders row and menu come from these, and inside a folder its chats too
    private var folderChats: List<Chat> = emptyList()
    private var chatLimit = PAGE_SIZE  // grows by a page with each "Show more chats"
    private var settingsDialog: AlertDialog? = null

    // The open folder (the app's own, see Folders.kt); null = all chats
    private lateinit var folders: Folders
    private var folderId: Long? = null
    private val folder: Folder? get() = folderId?.let { folders.find(it) }
    // Inside a folder, Back returns to all chats instead of leaving the app
    private val leaveFolderOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = switchFolder(null)
    }

    private var api: ApiClient? = null
    private var live: LiveConnection? = null
    private var wasConnected = false
    // Between onStart and onStop. Android's own lifecycle state only becomes STARTED
    // after onStart returns, too late for code that runs during onStart.
    private var visible = false
    private var askedLocalNetwork = false  // asked once per screen, so a refusal doesn't loop

    // Android 17+: "Allow TG Flip to access local network devices?"; allowed = connect now
    private val askLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) start() else showStatus(getString(R.string.local_network_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        settings = ServerSettings(this)
        cache = LocalCache(this)
        // Gray that suits both light and dark theme
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        previewColor = attrs.getColor(0, Color.GRAY)
        attrs.recycle()
        connection = findViewById(R.id.connection)
        status = findViewById(R.id.status)
        chatList = findViewById(R.id.chat_list)
        // One adapter for the screen's lifetime, so refreshes keep the d-pad selection.
        // A photo that arrives redraws the rows.
        photos = PhotoLoader(this, lifecycleScope) { chatAdapter.notifyDataSetChanged() }
        chatAdapter = ChatRowAdapter(this) { position -> chatAt(position)?.let(photos::drawable) }
        chatList.adapter = chatAdapter
        // Row 0 is the folders row, then the chats, then maybe "Show more chats"
        chatList.setOnItemClickListener { _, _, position, _ ->
            val chat = chatAt(position)
            when {
                position == 0 -> showFoldersMenu()
                chat != null -> openChat(chat)
                else -> showMoreChats()
            }
        }
        folders = Folders(this)
        onBackPressedDispatcher.addCallback(this, leaveFolderOnBack)
        chatList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                updateSoftKey()

            override fun onNothingSelected(parent: AdapterView<*>?) = updateSoftKey()
        }

        softKeys = SoftKeyBar(this)
        softKeys.onRightTap { selectedChat()?.let(::openGeneral) }

        setUpTabBar(Tab.CHATS) { SettingsActivity.start(this) }
    }

    // Right soft key on a topic group opens its General topic directly.
    // D-pad Right opens Settings, like swiping open the official app's side menu
    // (the list only uses Up and Down, so Right is free here).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isRightSoftKey()) {
            if (event.action == KeyEvent.ACTION_UP) selectedChat()?.let(::openGeneral)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.action == KeyEvent.ACTION_UP) SettingsActivity.start(this)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // The folders row shifts the chats down by one
    private fun chatAt(position: Int): Chat? = chats.getOrNull(position - 1)

    private fun selectedChat(): Chat? = chatAt(chatList.selectedItemPosition)

    private fun updateSoftKey() {
        softKeys.setRight(if (selectedChat()?.forum == true) getString(R.string.soft_key_general) else null)
    }

    private fun openChat(chat: Chat) {
        if (chat.forum) TopicsActivity.start(this, chat) else ChatActivity.start(this, chat)
    }

    private fun openGeneral(chat: Chat) {
        if (!chat.forum) return
        ChatActivity.start(this, chat, Topic(Topic.GENERAL_ID, getString(R.string.general_topic), 0))
    }

    private fun showMoreChats() {
        chatLimit += PAGE_SIZE
        loadChats(quiet = false)
    }

    // The app is visible: connect. Hidden: disconnect, to save battery.
    override fun onStart() {
        super.onStart()
        visible = true
        showSettingsDot()  // back from Settings, where they may have been read
        start()
    }

    override fun onStop() {
        visible = false
        live?.stop()
        super.onStop()
    }

    private fun start() {
        live?.stop()
        live = null
        api = null
        photos.api = null
        val address = settings.getAddress()
        val token = settings.getToken()
        // Why this mode cannot connect, if it cannot
        val problem = when {
            address == null || token == null -> getString(R.string.server_not_set, getString(settings.getMode().label))
            address.toHttpUrlOrNull() == null -> getString(R.string.error_bad_address)
            else -> null
        }

        lifecycleScope.launch {
            // The saved copy shows in every case, also when this mode has no server yet
            showSavedChats()
            if (problem != null) {
                connection.showNoServer()
                showStatus(problem)  // after the saved chats, which hide the status line
                // A hint, not a forced dialog: the user may just have switched modes.
                // Only the very first launch (no token yet) opens the dialog by itself.
                if (token == null) askForSettings()
                return@launch
            }
            // The screen may have been hidden meanwhile; onStart will run start() again
            if (!visible) return@launch
            if (LocalNetworkAccess.missing(this@MainActivity, settings.getMode())) {
                connection.showNoServer()
                if (askedLocalNetwork) {
                    showStatus(getString(R.string.local_network_needed))
                } else {
                    askedLocalNetwork = true
                    askLocalNetwork.launch(LocalNetworkAccess.PERMISSION)
                }
                return@launch
            }
            val api = ApiClient(address, token!!)
            this@MainActivity.api = api
            photos.api = api
            wasConnected = false
            live = LiveConnection(api, lifecycleScope, ::showConnection, ::onServerEvent).also { it.start() }
            loadChats(quiet = false)
        }
    }

    // On a fresh screen, show the chats saved last time while the real ones load.
    // In a folder: those of the saved chats that are in it.
    private suspend fun showSavedChats() {
        if (chats.isNotEmpty()) return
        val saved = withContext(Dispatchers.IO) { cache.loadChats() } ?: return
        val folder = folder
        if (folder != null) {
            showChats(saved.filter { it.id in folder.chatIds })
            return
        }
        // Ask for as many pages as were saved, so the list does not shrink on refresh
        chatLimit = ((saved.size + PAGE_SIZE - 1) / PAGE_SIZE * PAGE_SIZE).coerceAtLeast(PAGE_SIZE)
        showChats(saved)
    }

    // quiet = background refresh: no "Loading" text, and network errors are left to the status bar.
    // With folders, one more request fetches the chats of all folders: for their unread counts,
    // and inside a folder for its chats too.
    private fun loadChats(quiet: Boolean) {
        val api = api ?: return
        if (!quiet) showStatus(getString(R.string.loading))
        val folder = folder
        val inAnyFolder = folders.load().flatMap { it.chatIds }.toSet()
        lifecycleScope.launch {
            try {
                val (inFolders, fresh) = withContext(Dispatchers.IO) {
                    val inFolders = api.getChatsByIds(inAnyFolder)  // no request without folders
                    // Only the full list is saved offline
                    val fresh = if (folder == null) api.getChats(chatLimit).also(cache::saveChats)
                    else inFolders.filter { it.id in folder.chatIds }
                    inFolders to fresh
                }
                if (folderId != folder?.id) return@launch  // switched folders meanwhile
                folderChats = inFolders
                showChats(fresh)
            } catch (e: BadTokenException) {
                showError(getString(R.string.error_bad_token))
            } catch (e: IOException) {
                when {
                    quiet -> {}
                    // Saved chats are on screen: say so, no settings dialog
                    chats.isNotEmpty() -> showStatus(getString(R.string.offline_saved))
                    else -> showError(getString(R.string.error_no_server))
                }
            }
        }
    }

    // loaded: as fetched. In All chats, chats of hidden folders are left out.
    private fun showChats(loaded: List<Chat>) {
        loadedChats = loaded
        val hiddenIds = if (folderId == null) {
            folders.load().filter { it.hidden }.flatMap { it.chatIds }.toSet()
        } else {
            emptySet()
        }
        chats = loaded.filter { it.id !in hiddenIds }
        chatAdapter.clear()
        chatAdapter.add(foldersRow())
        chatAdapter.addAll(chats.map(::chatRow))
        // A full page means there may be more. The selection stays on this row's position,
        // so after loading more it lands on the first newly loaded chat. Folders are complete.
        if (folderId == null && loaded.size >= chatLimit) chatAdapter.add(getString(R.string.show_more_chats))
        when {
            chats.isNotEmpty() -> status.visibility = View.GONE
            folderId != null -> showStatus(getString(R.string.folder_empty))
            else -> showStatus(getString(R.string.no_chats))
        }
        chatList.requestFocus()
        updateSoftKey()
    }

    // After a folder setting changed: the same chats, shown with the new settings
    private fun redraw() = showChats(loadedChats)

    // "📁 Folders… (12)" at the top of all chats; inside a folder "📁 Work · Folders… (12)".
    // The number is the total unread across the folders that count.
    private fun foldersRow(): CharSequence {
        val name = folder?.name
        val base = if (name == null) getString(R.string.folders_row) else getString(R.string.folders_row_in, name)
        val total = totalUnread()
        val text = SpannableString(if (total > 0) "$base ($total)" else base)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, 0)
        return text
    }

    private fun unreadIn(folder: Folder) = folderChats.filter { it.id in folder.chatIds }.sumOf { it.unread }

    // A chat in several counting folders counts once
    private fun totalUnread(): Int {
        val counted = folders.load().filter { it.countUnread }.flatMap { it.chatIds }.toSet()
        return folderChats.filter { it.id in counted }.sumOf { it.unread }
    }

    private fun folderLabel(folder: Folder): String {
        val unread = unreadIn(folder)
        return if (unread > 0) "📁 ${folder.name} ($unread)" else "📁 ${folder.name}"
    }

    // Opens a folder (null = all chats): its saved chats at once, then the fresh ones
    private fun switchFolder(id: Long?) {
        folderId = id
        leaveFolderOnBack.isEnabled = id != null
        chats = emptyList()
        lifecycleScope.launch {
            showSavedChats()
            if (chats.isEmpty()) showChats(emptyList())  // nothing saved: at least the folders row
            chatList.setSelection(0)
            loadChats(quiet = false)
        }
    }

    // OK on the folders row: switch to a folder (or back to all chats), or Folder settings
    private fun showFoldersMenu() {
        val current = folder
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        if (current != null) entries += getString(R.string.all_chats) to { switchFolder(null) }
        for (f in folders.load()) {
            if (f.id != current?.id) entries += folderLabel(f) to { switchFolder(f.id) }
        }
        entries += getString(R.string.folder_settings) to ::showFolderSettings
        val title = current?.name ?: getString(R.string.folders)
        MessageMenu.choose(this, title, entries.map { it.first }) { entries[it].second() }
    }

    private fun showFolderSettings() {
        val items = listOf(getString(R.string.create_folder), getString(R.string.edit_folder))
        MessageMenu.choose(this, getString(R.string.folder_settings_title), items) { which ->
            if (which == 0) newFolder() else pickFolderToEdit()
        }
    }

    // Name, then the chats to put in it, then it opens
    private fun newFolder() {
        MessageMenu.askText(this, getString(R.string.new_folder_title), "") { name ->
            val created = folders.create(name)
            addChats(created) { switchFolder(created.id) }
        }
    }

    private fun pickFolderToEdit() {
        val all = folders.load()
        if (all.isEmpty()) {
            showStatus(getString(R.string.no_folders))
            return
        }
        MessageMenu.choose(this, getString(R.string.edit_folder), all.map { "📁 ${it.name}" }) { editFolder(all[it].id) }
    }

    // One folder's settings. After a change it opens again, so several edits are quick.
    private fun editFolder(id: Long) {
        val f = folders.find(id) ?: return
        val again = { editFolder(id) }
        val entries = listOf<Pair<String, () -> Unit>>(
            getString(R.string.folder_rename) to { renameFolder(f, again) },
            getString(R.string.folder_add_chats) to { addChats(f, then = again) },
            getString(R.string.folder_remove_chats) to { removeChats(f, again) },
            getString(R.string.folder_hidden, onOff(f.hidden)) to {
                folders.setHidden(id, !f.hidden)
                redraw()
                again()
            },
            getString(R.string.folder_count_unread, onOff(f.countUnread)) to {
                folders.setCountUnread(id, !f.countUnread)
                redraw()
                again()
            },
            getString(R.string.folder_delete) to { deleteFolder(f) },
        )
        MessageMenu.choose(this, "📁 ${f.name}", entries.map { it.first }) { entries[it].second() }
    }

    private fun onOff(on: Boolean) = getString(if (on) R.string.on else R.string.off)

    private fun renameFolder(target: Folder, then: () -> Unit) {
        MessageMenu.askText(this, getString(R.string.folder_rename), target.name) { name ->
            folders.rename(target.id, name)
            redraw()  // the folders row may show the new name
            then()
        }
    }

    // A checklist of the saved full chat list, minus chats already in the folder.
    // "Load more chats" fetches another page and opens it again, keeping the ticks.
    private fun addChats(target: Folder, ticked: Set<Long> = emptySet(), then: () -> Unit) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { cache.loadChats() }.orEmpty()
            val options = saved.filter { it.id !in target.chatIds }.map { FolderChat(it.id, it.name) }
            // A full page means there may be more, as with "Show more chats"
            val loadMore = if (saved.size >= chatLimit) {
                { nowTicked: Set<Long> -> loadMoreChats { addChats(target, nowTicked, then) } }
            } else {
                null
            }
            pickChats(getString(R.string.add_chats_title, target.name), options, ticked, loadMore) { picked ->
                folders.addChats(target.id, picked)
                loadChats(quiet = false)
                then()
            }
        }
    }

    // One more page of the full chat list, saved like "Show more chats" does, so the
    // main list grows too; then `then` (e.g. reopening the checklist)
    private fun loadMoreChats(then: () -> Unit) {
        val api = api ?: run {
            showStatus(getString(R.string.error_no_server))
            return
        }
        showStatus(getString(R.string.loading))
        lifecycleScope.launch {
            try {
                val more = withContext(Dispatchers.IO) { api.getChats(chatLimit + PAGE_SIZE).also(cache::saveChats) }
                chatLimit += PAGE_SIZE
                if (folderId == null) showChats(more) else status.visibility = View.GONE
                then()
            } catch (e: IOException) {
                showStatus(getString(R.string.error_no_server))
            }
        }
    }

    // A checklist of the folder's chats: the ticked ones are removed
    private fun removeChats(target: Folder, then: () -> Unit) {
        if (target.chats.isEmpty()) {
            showStatus(getString(R.string.folder_empty))
            return
        }
        pickChats(getString(R.string.remove_chats_title, target.name), target.chats) { picked ->
            folders.removeChats(target.id, picked.map { it.id }.toSet())
            loadChats(quiet = false)
            then()
        }
    }

    // OK ticks or unticks a chat; the dialog's OK hands over the ticked ones,
    // Cancel / Back changes nothing. ticked: chats ticked from the start.
    // loadMore: if given, a "Load more chats" button that gets the currently ticked ids.
    private fun pickChats(
        title: String,
        options: List<FolderChat>,
        ticked: Set<Long> = emptySet(),
        loadMore: ((Set<Long>) -> Unit)? = null,
        onDone: (List<FolderChat>) -> Unit,
    ) {
        if (options.isEmpty() && loadMore == null) {
            showStatus(getString(R.string.no_chats_to_pick))
            return
        }
        val checked = BooleanArray(options.size) { options[it].id in ticked }
        val tickedNow = { options.filterIndexed { i, _ -> checked[i] } }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(options.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(tickedNow()) }
            .setNegativeButton(android.R.string.cancel, null)
        if (loadMore != null) {
            builder.setNeutralButton(R.string.load_more_chats) { _, _ ->
                loadMore(tickedNow().map { it.id }.toSet())
            }
        }
        // Down on the last chat goes on to the buttons, so only Up wraps
        builder.show().listView.wrapAround(wrapsDown = false)
    }

    // Only the folder goes; its chats stay in Telegram as they are
    private fun deleteFolder(target: Folder) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.folder_delete_title, target.name))
            .setMessage(R.string.folder_delete_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                folders.delete(target.id)
                if (folderId == target.id) switchFolder(null) else redraw()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // "Name (3)"; topic groups get a "›" because they open a list of topics.
    // Below it, smaller and grayed, the newest message: "14:05 Anna: hi there…"
    private fun chatRow(chat: Chat): CharSequence {
        val name = if (chat.forum) "${chat.name} ›" else chat.name
        val title = if (chat.unread > 0) "$name (${chat.unread})" else name
        val last = chat.last ?: return title
        val preview = SpannableString(previewLine(last))
        preview.setSpan(RelativeSizeSpan(0.8f), 0, preview.length, 0)
        preview.setSpan(ForegroundColorSpan(previewColor), 0, preview.length, 0)
        return TextUtils.concat(title, "\n", preview)
    }

    private fun previewLine(last: LastMessage): String {
        val call = last.call
        val body = when {
            last.kind == "call" && call != null -> MessageFormat.call(this, call, last.out)
            last.kind == "voice" && last.voice != null -> MessageFormat.voice(this, last.voice)
            last.kind == "photo" -> MessageFormat.snippet(MessageFormat.photo(this, last.text), PREVIEW_LENGTH)
            last.kind == "video" -> MessageFormat.snippet(MessageFormat.video(this, null, last.text), PREVIEW_LENGTH)
            last.kind == "round" -> MessageFormat.round(this, null)
            last.kind == "sticker" -> MessageFormat.sticker(this, last.text)
            last.kind == "media" -> getString(R.string.media)
            last.kind == "event" -> getString(R.string.event)
            else -> MessageFormat.snippet(last.text, PREVIEW_LENGTH)
        }
        // A call line already says who called ("Incoming" / "Outgoing")
        val who = when {
            last.kind == "call" -> ""
            last.out -> "${getString(R.string.you)}: "
            last.sender != null -> "${MessageFormat.snippet(last.sender, SENDER_LENGTH)}: "
            else -> ""
        }
        return "${MessageFormat.time(last.date)} $who$body"
    }

    private fun showConnection(state: LiveConnection.Status) {
        connection.showConnection(state)

        // After a reconnect we may have missed events, so refetch
        if (state == LiveConnection.Status.CONNECTED) {
            if (wasConnected) loadChats(quiet = true)
            wasConnected = true
            // Admin news: a dot on the Settings tab leads the way to them
            api?.let { AdminMessages.check(this, it, lifecycleScope) { showSettingsDot() } }
        }
    }

    private fun onServerEvent(event: JSONObject) {
        if (IncomingCallAlert.handle(this, event)) return
        // A new message or a read elsewhere changes unread counts and order;
        // edits and deletes can change the newest message shown in the preview
        when (event.optString("type")) {
            "message", "read", "edited", "deleted" -> loadChats(quiet = true)
        }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun showError(text: String) {
        showStatus(text)
        askForSettings()
    }

    // First launch or a connection error. Always closable (Back or Cancel): the status line
    // keeps the hint, and Settings (Right) has the same fields.
    private fun askForSettings() {
        if (settingsDialog?.isShowing == true) return
        settingsDialog = ServerSettingsDialog.show(this, settings, settings.getMode(), cancelable = true) { start() }
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val PREVIEW_LENGTH = 32  // about one line of the small preview text
        const val SENDER_LENGTH = 12
    }
}
