package com.q43b.tgflip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// "Local cache" in Under the hood (since 1.3.0): what the app keeps on the phone, in levels.
// ROOT: saved chats and messages, saved sticker lists, media files, each with its size.
// CHATS: the chat list, each group's topics and each chat's saved messages (OK opens CHAT: the
// messages as text, as they show offline). PACKS: the pack list and each opened pack (OK opens
// PACK: its stickers). MEDIA: one row per kind of file; OK on one lists its files (FILES): OK
// on a picture or sticker opens it in the viewer (ImageActivity.startFile), F2 deletes the file.
// F2 ("Delete") on any row deletes just that item, after asking; screens fetch it again the next time they reach the server, and
// media downloads again when shown. Settings > Media and Cache keeps the limits and the
// delete-all button. Left or Back goes up a level.
class LocalCacheActivity : AppCompatActivity() {
    private enum class Level { ROOT, CHATS, CHAT, PACKS, PACK, MEDIA, FILES }

    private class Delete(val question: String, val perform: () -> Unit)

    // open = what OK does (null: nothing), delete = what F2 does (null: nothing)
    private class Row(val label: String, val open: (() -> Unit)? = null, val delete: Delete? = null)

    private lateinit var level: Level
    private lateinit var cache: LocalCache
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var list: WrapListView
    private lateinit var softKeys: SoftKeyBar
    private lateinit var titleView: TextView
    private var rows = emptyList<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_local_cache)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        level = Level.entries[intent.getIntExtra(EXTRA_LEVEL, 0)]
        cache = LocalCache(this)
        titleView = findViewById(R.id.cache_title)
        list = findViewById(R.id.cache_list)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> rows.getOrNull(position)?.open?.invoke() }
        list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showDeleteKey()
            override fun onNothingSelected(parent: AdapterView<*>?) = showDeleteKey()
        }
        softKeys = SoftKeyBar(this)
        softKeys.onRightTap { askDelete() }
        softKeys.setLeft(null)
        softKeys.setRight(null)
        list.requestFocus()
    }

    // Also on coming back from a deeper level, where something may have been deleted
    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val (title, built) = withContext(Dispatchers.IO) { build() }
            val selected = list.selectedItemPosition
            titleView.text = title
            rows = built.ifEmpty { listOf(Row(getString(R.string.local_cache_empty))) }
            adapter.clear()
            adapter.addAll(rows.map { it.label })
            if (selected > 0) list.setSelection(selected.coerceAtMost(rows.size - 1))
            showDeleteKey()
        }
    }

    private fun showDeleteKey() {
        val deletable = rows.getOrNull(list.selectedItemPosition)?.delete != null
        softKeys.setRight(if (deletable) getString(R.string.local_cache_delete) else null)
    }

    private fun askDelete() {
        val delete = rows.getOrNull(list.selectedItemPosition)?.delete ?: return
        AlertDialog.Builder(this)
            .setMessage(delete.question)
            .setPositiveButton(R.string.cache_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { delete.perform() }
                    load()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { Themer.apply(it) }
    }

    // The title and rows of this level (on a background thread: it reads files)
    private fun build(): Pair<String, List<Row>> = when (level) {
        Level.ROOT -> getString(R.string.local_cache_title) to rootRows()
        Level.CHATS -> getString(R.string.local_cache_chats_title) to chatRows()
        Level.CHAT -> chatTitle() to messageRows()
        Level.PACKS -> getString(R.string.local_cache_stickers_title) to packRows()
        Level.PACK -> packName(intent.getStringExtra(EXTRA_PACK).orEmpty(), cache.loadStickerPacks().orEmpty()) to stickerRows()
        Level.MEDIA -> getString(R.string.local_cache_media_title) to mediaRows()
        Level.FILES -> kindName(intent.getStringExtra(EXTRA_KIND).orEmpty()) to fileRows(intent.getStringExtra(EXTRA_KIND).orEmpty())
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes)

    private fun rootRows(): List<Row> {
        val saved = cache.saved()
        val chatFiles = saved.filter { it.name == "chats" || it.name.startsWith("topics_") || it.name.startsWith("messages_") }
        val packFiles = saved.filter { it.name == "sticker_packs" || it.name.startsWith("stickers_") }
        val mediaBytes = MediaCache.kindFolders(this).sumOf { folder -> folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        return listOf(
            Row(getString(R.string.local_cache_chats, chatFiles.size, size(chatFiles.sumOf { it.bytes })), open = { open(Level.CHATS) }),
            Row(getString(R.string.local_cache_stickers, packFiles.size, size(packFiles.sumOf { it.bytes })), open = { open(Level.PACKS) }),
            Row(getString(R.string.local_cache_media, size(mediaBytes)), open = { open(Level.MEDIA) }),
        )
    }

    // The chat list, each group's topics, each chat's messages: the biggest first
    private fun chatRows(): List<Row> {
        val saved = cache.saved().sortedByDescending { it.bytes }
        val chats = cache.loadChats().orEmpty()
        val rows = mutableListOf<Row>()
        saved.firstOrNull { it.name == "chats" }?.let {
            rows += Row(getString(R.string.local_cache_chat_list, chats.size, size(it.bytes)),
                delete = Delete(getString(R.string.local_cache_q_chat_list)) { cache.delete("chats") })
        }
        for (file in saved) {
            if (file.name.startsWith("topics_")) {
                val chatId = file.name.removePrefix("topics_").toLongOrNull() ?: continue
                val name = chatName(chatId, chats)
                rows += Row(getString(R.string.local_cache_topics, name, cache.loadTopics(chatId)?.size ?: 0, size(file.bytes)),
                    delete = Delete(getString(R.string.local_cache_q_topics, name)) { cache.delete(file.name) })
            } else if (file.name.startsWith("messages_")) {
                val (chatId, topicId) = messagesIds(file.name) ?: continue
                val label = chatLabel(chatId, topicId, chats)
                rows += Row(getString(R.string.local_cache_messages, label, cache.loadMessages(chatId, topicId)?.size ?: 0, size(file.bytes)),
                    open = { open(Level.CHAT, chat = chatId, topic = topicId) },
                    delete = Delete(getString(R.string.local_cache_q_messages, label)) { cache.delete(file.name) })
            }
        }
        return rows
    }

    private fun messageRows(): List<Row> {
        val chatId = intent.getLongExtra(EXTRA_CHAT, 0)
        val topicId = if (intent.hasExtra(EXTRA_TOPIC)) intent.getLongExtra(EXTRA_TOPIC, 0) else null
        return cache.loadMessages(chatId, topicId).orEmpty().map { Row(line(it)) }
    }

    private fun chatTitle(): String {
        val chatId = intent.getLongExtra(EXTRA_CHAT, 0)
        val topicId = if (intent.hasExtra(EXTRA_TOPIC)) intent.getLongExtra(EXTRA_TOPIC, 0) else null
        return chatLabel(chatId, topicId, cache.loadChats().orEmpty())
    }

    // "14:05 Anna: hi", or what the app shows instead of a text for media and calls
    private fun line(m: Message): String {
        val who = when {
            m.out -> getString(R.string.local_cache_me)
            else -> m.sender.orEmpty()
        }
        val body = when {
            m.call != null -> MessageFormat.call(this, m.call, m.out)
            m.sticker != null -> MessageFormat.sticker(this, m.sticker.emoji)
            m.voice != null -> MessageFormat.voice(this, m.voice)
            m.round != null -> MessageFormat.round(this, m.round)
            m.video != null -> MessageFormat.video(this, m.video, m.text)
            m.image != null -> MessageFormat.photo(this, m.text)
            else -> m.text
        }
        return "${MessageFormat.time(m.date)} ${if (who.isEmpty()) "" else "$who: "}$body"
    }

    private fun packRows(): List<Row> {
        val saved = cache.saved().sortedByDescending { it.bytes }
        val packs = cache.loadStickerPacks().orEmpty()
        val rows = mutableListOf<Row>()
        saved.firstOrNull { it.name == "sticker_packs" }?.let {
            rows += Row(getString(R.string.local_cache_pack_list, packs.size, size(it.bytes)),
                delete = Delete(getString(R.string.local_cache_q_pack_list)) { cache.delete("sticker_packs") })
        }
        for (file in saved) {
            if (!file.name.startsWith("stickers_")) continue
            val pack = file.name.removePrefix("stickers_")
            val name = packName(pack, packs)
            rows += Row(getString(R.string.local_cache_stickers_row, name, cache.loadStickers(pack)?.size ?: 0, size(file.bytes)),
                open = { open(Level.PACK, pack = pack) },
                delete = Delete(getString(R.string.local_cache_q_pack, name)) { cache.delete(file.name) })
        }
        return rows
    }

    private fun stickerRows(): List<Row> =
        cache.loadStickers(intent.getStringExtra(EXTRA_PACK).orEmpty()).orEmpty().map { sticker ->
            val description = StickerDescriptions.get(this, sticker.id)
            Row("${sticker.emoji} ${sticker.kind}" + (description?.let { ": $it" } ?: ""))
        }

    // One row per kind of file (the folders under cacheDir/media), the biggest first
    private fun mediaRows(): List<Row> = MediaCache.kindFolders(this).mapNotNull { folder ->
        val files = folder.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return@mapNotNull null
        val bytes = files.sumOf { it.length() }
        val oldest = DateUtils.getRelativeTimeSpanString(files.minOf { it.lastModified() }, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)
        val name = kindName(folder.name)
        bytes to Row(getString(R.string.local_cache_media_row, name, files.size, size(bytes), oldest),
            open = { open(Level.FILES, kind = folder.name) },
            delete = Delete(getString(R.string.local_cache_q_media, name, size(bytes))) { MediaCache.deleteKind(this, folder.name) })
    }.sortedByDescending { it.first }.map { it.second }

    // The files of one kind, the most recently used first. Pictures and stickers open in the
    // viewer (OK); every file can be deleted (F2).
    private fun fileRows(kind: String): List<Row> {
        val chats = cache.loadChats().orEmpty()
        val viewable = kind in VIEWABLE
        val now = System.currentTimeMillis()
        return MediaCache.folder(this, kind).walkTopDown().filter { it.isFile }
            .sortedByDescending { it.lastModified() }.map { file ->
                val label = fileLabel(kind, file.name, chats)
                val age = DateUtils.getRelativeTimeSpanString(file.lastModified(), now, DateUtils.MINUTE_IN_MILLIS)
                val view: (() -> Unit)? = if (viewable) { { ImageActivity.startFile(this, file.path) } } else null
                Row(getString(R.string.local_cache_file_row, label, size(file.length()), age),
                    open = view,
                    delete = Delete(getString(R.string.local_cache_q_file, label)) { file.delete() })
            }.toList()
    }

    // Media files are named after what they hold: <chat>_<photo or message>.<type> and, for
    // stickers and custom emoji, <id>_<px>[_anim].<type>
    private fun fileLabel(kind: String, name: String, chats: List<Chat>): String {
        val parts = name.removeSuffix(".part").substringBeforeLast('.').split('_')
        if (kind == "stickers") {
            val moving = if (parts.contains("anim")) getString(R.string.local_cache_file_moving) else ""
            return getString(R.string.local_cache_file_sticker, parts[0], parts.getOrNull(1).orEmpty()) + moving
        }
        val chat = parts[0].toLongOrNull() ?: return name
        val chatName = chatName(chat, chats)
        // Profile photos are named after their photo's id, which says nothing: only the chat
        return if (kind == "photos" || parts.size < 2) chatName else getString(R.string.local_cache_file_message, chatName, parts[1])
    }

    private fun kindName(kind: String): String {
        val name = when (kind) {
            "photos" -> R.string.local_cache_kind_photos
            "images" -> R.string.local_cache_kind_images
            "voice" -> R.string.local_cache_kind_voice
            "videos" -> R.string.local_cache_kind_videos
            "stickers" -> R.string.local_cache_kind_stickers
            else -> return kind
        }
        return getString(name)
    }

    // messages_<chat> or messages_<chat>_<topic>: chat ids can be negative ("-100123")
    private fun messagesIds(name: String): Pair<Long, Long?>? {
        val parts = name.removePrefix("messages_").split('_')
        val chat = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val topic = parts.getOrNull(1)?.toLongOrNull()
        return chat to topic
    }

    private fun chatName(chatId: Long, chats: List<Chat>) =
        chats.firstOrNull { it.id == chatId }?.name ?: getString(R.string.local_cache_chat_unknown, chatId)

    // "Study group" or "Study group › Homework"
    private fun chatLabel(chatId: Long, topicId: Long?, chats: List<Chat>): String {
        val name = chatName(chatId, chats)
        if (topicId == null) return name
        val topic = cache.loadTopics(chatId)?.firstOrNull { it.id == topicId }?.title ?: getString(R.string.local_cache_topic_unknown, topicId)
        return "$name › $topic"
    }

    private fun packName(pack: String, packs: List<StickerPack>) = when (pack) {
        "recent" -> getString(R.string.stickers_recent)
        "faved" -> getString(R.string.stickers_faved)
        "local" -> getString(R.string.stickers_local)
        else -> packs.firstOrNull { it.id == pack }?.title ?: getString(R.string.local_cache_pack_unknown, pack)
    }

    private fun open(next: Level, chat: Long? = null, topic: Long? = null, pack: String? = null, kind: String? = null) {
        val intent = Intent(this, LocalCacheActivity::class.java).putExtra(EXTRA_LEVEL, next.ordinal)
        if (chat != null) intent.putExtra(EXTRA_CHAT, chat)
        if (topic != null) intent.putExtra(EXTRA_TOPIC, topic)
        if (pack != null) intent.putExtra(EXTRA_PACK, pack)
        if (kind != null) intent.putExtra(EXTRA_KIND, kind)
        startActivity(intent)
    }

    // Left goes up a level, like the screens of Settings; F2 deletes the highlighted item
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        if (event.isRightSoftKey() && rows.getOrNull(list.selectedItemPosition)?.delete != null) {
            if (event.action == KeyEvent.ACTION_UP) askDelete()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_LEVEL = "level"
        private const val EXTRA_CHAT = "chat"
        private const val EXTRA_TOPIC = "topic"
        private const val EXTRA_PACK = "pack"
        private const val EXTRA_KIND = "kind"
        private val VIEWABLE = setOf("photos", "images", "stickers")  // the kinds the picture viewer shows

        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, LocalCacheActivity::class.java))
        }
    }
}
