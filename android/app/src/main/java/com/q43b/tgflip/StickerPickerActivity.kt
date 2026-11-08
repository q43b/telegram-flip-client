package com.q43b.tgflip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.AdapterView
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

// F2 > Stickers in a chat: "🕘 Recent" and "★ Favorites", then my sticker packs; OK on one
// shows its stickers as a grid (all four arrows move), OK on a sticker picks it and goes back
// to the chat, which sends it. Back (or Left) in the grid returns to the packs. The grid's
// pictures are the chat's (same StickerLoader size and files); in data saver mode, or while
// a picture loads, the sticker's emoji shows instead. With Settings > Stickers > Animated
// (since 0.16.1-beta) only the highlighted sticker plays (a second loader, mover); the others
// stay still, since a whole playing pack is too heavy for the phone.
// "View sticker pack" (message menu on a sticker) opens straight at that sticker's pack; Back
// then closes, and while the pack isn't mine F1 ("Add pack") adds it to my packs.
// F2 ("Menu") on a sticker in a grid: add it to / remove it from my Telegram Favorites and my
// local favourites (the app's own list, kept on the server, no limit), and add, edit or
// remove my own description of it (shown in data saver mode).
class StickerPickerActivity : AppCompatActivity() {
    private lateinit var api: ApiClient
    private lateinit var cache: LocalCache  // the lists, saved for offline (the pictures are in the media cache)
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var packList: WrapListView
    private lateinit var grid: GridView
    private lateinit var loader: StickerLoader
    private var mover: StickerLoader? = null  // plays the highlighted sticker; null unless Animated
    private var packs = emptyList<StickerPack>()
    private var stickers = emptyList<StickerInfo>()
    private lateinit var softKeys: SoftKeyBar
    private var addablePack: String? = null  // the open pack's id while F1 can add it
    private var openPackId: String? = null  // the pack in the grid ("recent", "faved" or an id)
    private var favedIds: Set<Long>? = null  // my Telegram Favorites, asked when first needed
    private var localIds: Set<Long>? = null  // my local favourites, likewise

    // Back in the grid goes back to the packs
    private val backToPacks = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showPacks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_sticker_picker)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val settings = ServerSettings(this)
        api = ApiClient(settings.getAddress(), settings.getToken().orEmpty())
        cache = LocalCache(this)
        title = findViewById(R.id.picker_title)
        status = findViewById(R.id.status)
        packList = findViewById(R.id.pack_list)
        grid = findViewById(R.id.sticker_grid)
        loader = StickerLoader(this, lifecycleScope) { (grid.adapter as? BaseAdapter)?.notifyDataSetChanged() }
            .also { it.api = api }
        if (StickerSetting.plays(this)) {
            mover = StickerLoader(this, lifecycleScope, plays = true) {
                (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
            }.also { it.api = api }
            // A new highlight: the old one goes back to its still picture, the new one plays
            grid.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        onBackPressedDispatcher.addCallback(this, backToPacks)
        packList.setOnItemClickListener { _, _, position, _ -> openPack(packs[position]) }
        grid.setOnItemClickListener { _, _, position, _ -> pick(stickers[position]) }
        softKeys = SoftKeyBar(this)
        softKeys.onLeftTap(::addPack)
        softKeys.onRightTap { stickerMenu(grid.selectedItemPosition) }
        softKeys.setLeft(null)
        softKeys.setRight(null)
        if (intent.hasExtra(EXTRA_FROM_STICKER)) {
            openPackOf(intent.getLongExtra(EXTRA_FROM_STICKER, 0), intent.getLongExtra(EXTRA_FROM_CHAT, 0),
                intent.getLongExtra(EXTRA_FROM_MESSAGE, 0))
        } else {
            loadPacks()
        }
    }

    // Left closes the grid like Back; in the pack list it closes the screen. F1 adds the open
    // pack while it isn't mine.
    override fun onStart() {
        super.onStart()
        mover?.resume()
    }

    override fun onStop() {
        mover?.pause()  // nothing plays while the picker isn't shown
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isLeftSoftKey()) {
            if (event.action == KeyEvent.ACTION_UP) addPack()
            return true
        }
        if (event.isRightSoftKey()) {
            if (event.action == KeyEvent.ACTION_UP && grid.visibility == View.VISIBLE) stickerMenu(grid.selectedItemPosition)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && packList.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // The saved list first (since 1.2.3, so the picker works offline), then the server's, which
    // replaces it and is saved; offline the saved one stays, with a note
    private fun loadPacks() {
        title.setText(R.string.stickers_picker_title)
        showStatus(getString(R.string.stickers_loading))
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { cache.loadStickerPacks() }
            if (saved != null) showPackList(saved)
            val mine = try {
                withContext(Dispatchers.IO) { api.getStickerPacks().also { cache.saveStickerPacks(it) } }
            } catch (e: IOException) {
                showStatus(getString(if (saved != null) R.string.offline_saved else R.string.stickers_not_loaded))
                return@launch
            }
            if (mine != saved) showPackList(mine)
            if (packList.visibility == View.VISIBLE) status.visibility = View.GONE
        }
    }

    // Recent, Favorites and Local favourites, then my packs; the highlighted row stays where it was
    private fun showPackList(mine: List<StickerPack>) {
        val selected = packList.selectedItemPosition
        packs = listOf(
            StickerPack(RECENT, getString(R.string.stickers_recent), 0),
            StickerPack(FAVED, getString(R.string.stickers_faved), 0),
            StickerPack(LOCAL, getString(R.string.stickers_local), 0),
        ) + mine
        packList.adapter = ArrayAdapter(this@StickerPickerActivity, android.R.layout.simple_list_item_1,
            packs.map { if (it.count > 0) "${it.title} (${it.count})" else it.title })
        if (selected > 0) packList.setSelection(selected.coerceAtMost(packs.size - 1))
        if (packList.visibility == View.VISIBLE) {  // not while a pack is open over it
            status.visibility = View.GONE
            packList.requestFocus()
        }
    }

    // Like the pack list: the saved stickers at once, then the server's (only while this pack
    // is still the one open)
    private fun openPack(pack: StickerPack) {
        title.text = pack.title
        openPackId = pack.id
        showStatus(getString(R.string.stickers_loading))
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { cache.loadStickers(pack.id) }
            if (!saved.isNullOrEmpty()) {
                showGrid(saved)
                backToPacks.isEnabled = true
            }
            val found = try {
                fetchPack(pack.id)
            } catch (e: IOException) {
                if (openPackId == pack.id) {
                    showStatus(getString(if (saved.isNullOrEmpty()) R.string.stickers_not_loaded else R.string.offline_saved))
                }
                return@launch
            }
            if (openPackId != pack.id) return@launch  // closed meanwhile
            when {
                found.isEmpty() -> showStatus(getString(R.string.stickers_empty))
                found != saved -> showGrid(found, grid.selectedItemPosition.coerceAtLeast(0))
            }
            backToPacks.isEnabled = true
        }
    }

    // One pack's stickers from the server, saved for offline
    private suspend fun fetchPack(pack: String): List<StickerInfo> =
        withContext(Dispatchers.IO) { api.getStickerPack(pack).also { cache.saveStickers(pack, it) } }

    // Straight at a sticker's pack (from a chat); Back closes the screen, there is no pack list
    private fun openPackOf(stickerId: Long, chatId: Long, messageId: Long) {
        title.setText(R.string.stickers_picker_title)
        showStatus(getString(R.string.stickers_loading))
        lifecycleScope.launch {
            val (pack, found) = try {
                withContext(Dispatchers.IO) { api.getStickerPackOf(stickerId, chatId, messageId) }
            } catch (e: IOException) {
                showStatus(getString(R.string.pack_not_loaded))
                return@launch
            }
            title.text = pack.title
            openPackId = pack.id
            withContext(Dispatchers.IO) { cache.saveStickers(pack.id, found) }
            if (!pack.installed) {
                addablePack = pack.id
                softKeys.setLeft(getString(R.string.soft_key_add_pack))
            }
            showGrid(found)
        }
    }

    private fun showGrid(found: List<StickerInfo>, select: Int = 0) {
        stickers = found
        status.visibility = View.GONE
        grid.adapter = StickerGridAdapter()
        packList.visibility = View.GONE
        grid.visibility = View.VISIBLE
        softKeys.setRight(getString(R.string.soft_key_menu))
        grid.requestFocus()
        grid.setSelection(select.coerceIn(0, (found.size - 1).coerceAtLeast(0)))
    }

    // F2 on a sticker: Favorites (Telegram's), and my description of it
    private fun stickerMenu(position: Int) {
        val sticker = stickers.getOrNull(position) ?: return
        lifecycleScope.launch {
            // Offline: as last saved
            val faved = favedIds ?: try {
                fetchPack(FAVED).map { it.id }.toSet().also { favedIds = it }
            } catch (e: IOException) {
                withContext(Dispatchers.IO) { cache.loadStickers(FAVED) }?.map { it.id }?.toSet().orEmpty()
            }
            val local = localIds ?: try {
                fetchPack(LOCAL).map { it.id }.toSet().also { localIds = it }
            } catch (e: IOException) {
                withContext(Dispatchers.IO) { cache.loadStickers(LOCAL) }?.map { it.id }?.toSet().orEmpty()
            }
            val description = StickerDescriptions.get(this@StickerPickerActivity, sticker.id)
            val actions = mutableListOf<Pair<String, () -> Unit>>()
            if (sticker.id in faved) {
                actions += getString(R.string.sticker_unfave) to { fave(sticker, unfave = true, position) }
            } else {
                actions += getString(R.string.sticker_fave) to { fave(sticker, unfave = false, position) }
            }
            if (sticker.id in local) {
                actions += getString(R.string.sticker_unfave_local) to { localFave(sticker, remove = true, position) }
            } else {
                actions += getString(R.string.sticker_fave_local) to { localFave(sticker, remove = false, position) }
            }
            actions += getString(if (description == null) R.string.sticker_describe else R.string.sticker_edit_description) to
                { describe(sticker, description) }
            if (description != null) {
                actions += getString(R.string.sticker_remove_description) to {
                    StickerDescriptions.save(this@StickerPickerActivity, sticker.id, null)
                    (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
                }
            }
            val menuTitle = listOfNotNull(sticker.emoji.ifEmpty { null }, description).joinToString(" ")
                .ifEmpty { getString(R.string.sticker) }
            MessageMenu.choose(this@StickerPickerActivity, menuTitle, actions.map { it.first }) { actions[it].second() }
        }
    }

    // Telegram keeps 5 favorites without Premium; when it refuses, the note says so. In the
    // Favorites grid itself, the grid follows the change.
    private fun fave(sticker: StickerInfo, unfave: Boolean, position: Int) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { api.faveSticker(sticker.id, unfave) }
                favedIds = null  // ask again next time (Telegram may have dropped an old one)
                showToast(if (unfave) R.string.sticker_unfaved else R.string.sticker_faved)
                if (openPackId == FAVED) {
                    val found = fetchPack(FAVED)
                    if (found.isEmpty()) showStatus(getString(R.string.stickers_empty)) else showGrid(found, position)
                }
            } catch (e: IOException) {
                showToast(if (unfave) R.string.sticker_unfave_failed else R.string.sticker_fave_failed)
            }
        }
    }

    // The same for the local favourites; the Local favourites grid follows the change
    private fun localFave(sticker: StickerInfo, remove: Boolean, position: Int) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { api.localFavorite(sticker.id, remove) }
                localIds = null
                showToast(if (remove) R.string.sticker_unfaved_local else R.string.sticker_faved_local)
                if (openPackId == LOCAL) {
                    val found = fetchPack(LOCAL)
                    if (found.isEmpty()) showStatus(getString(R.string.stickers_empty)) else showGrid(found, position)
                }
            } catch (e: IOException) {
                showToast(if (remove) R.string.sticker_unfave_local_failed else R.string.sticker_fave_local_failed)
            }
        }
    }

    private fun describe(sticker: StickerInfo, current: String?) {
        MessageMenu.askText(this, getString(R.string.sticker_describe_title), current.orEmpty()) { text ->
            StickerDescriptions.save(this, sticker.id, text)
            (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
        }
    }

    private fun addPack() {
        val pack = addablePack ?: return
        addablePack = null
        softKeys.setLeft(null)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { api.installStickerPack(pack) }
                showToast(R.string.pack_added)
            } catch (e: IOException) {
                addablePack = pack
                softKeys.setLeft(getString(R.string.soft_key_add_pack))
                showToast(R.string.pack_add_failed)
            }
        }
    }

    private fun showToast(text: Int) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    private fun showPacks() {
        backToPacks.isEnabled = false
        openPackId = null
        softKeys.setRight(null)
        grid.visibility = View.GONE
        grid.adapter = null
        status.visibility = View.GONE
        packList.visibility = View.VISIBLE
        title.setText(R.string.stickers_picker_title)
        packList.requestFocus()
    }

    private fun pick(sticker: StickerInfo) {
        setResult(Activity.RESULT_OK, Intent()
            .putExtra(EXTRA_ID, sticker.id).putExtra(EXTRA_EMOJI, sticker.emoji).putExtra(EXTRA_KIND, sticker.kind))
        finish()
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private inner class StickerGridAdapter : BaseAdapter() {
        private val pictures = StickerSetting.showsPictures(this@StickerPickerActivity)

        override fun getCount() = stickers.size
        override fun getItem(position: Int) = stickers[position]
        override fun getItemId(position: Int) = stickers[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView ?: layoutInflater.inflate(R.layout.sticker_cell, parent, false)
            val sticker = stickers[position]
            // The highlighted one plays (its still picture shows while the moving one loads)
            val playing = if (pictures && position == grid.selectedItemPosition) {
                mover?.drawable(sticker.id, null, null, sticker.kind)
            } else null
            val picture = if (pictures) playing ?: loader.drawable(sticker.id, null, null) else null
            cell.findViewById<ImageView>(R.id.picture).setImageDrawable(picture)
            cell.findViewById<TextView>(R.id.emoji).apply {
                text = sticker.emoji.ifEmpty { "🏷" }
                visibility = if (picture == null) View.VISIBLE else View.INVISIBLE
            }
            // My description, where no picture shows (data saver)
            val description = if (pictures) null else StickerDescriptions.get(this@StickerPickerActivity, sticker.id)
            cell.findViewById<TextView>(R.id.description).apply {
                text = description
                visibility = if (description == null) View.GONE else View.VISIBLE
            }
            return cell
        }
    }

    // How the chat screen opens this and gets the chosen sticker back (null = nothing chosen).
    // Input: null for the pack list, or a sticker in a chat to open straight at its pack.
    class Pick : ActivityResultContract<PackOf?, StickerInfo?>() {
        override fun createIntent(context: android.content.Context, input: PackOf?) =
            Intent(context, StickerPickerActivity::class.java).apply {
                if (input != null) {
                    putExtra(EXTRA_FROM_STICKER, input.stickerId)
                    putExtra(EXTRA_FROM_CHAT, input.chatId)
                    putExtra(EXTRA_FROM_MESSAGE, input.messageId)
                }
            }

        override fun parseResult(resultCode: Int, intent: Intent?): StickerInfo? {
            if (resultCode != Activity.RESULT_OK || intent == null) return null
            return StickerInfo(
                intent.getLongExtra(EXTRA_ID, 0),
                intent.getStringExtra(EXTRA_EMOJI).orEmpty(),
                intent.getStringExtra(EXTRA_KIND) ?: "static",
            )
        }
    }

    companion object {
        private const val RECENT = "recent"
        private const val FAVED = "faved"
        private const val LOCAL = "local"
        private const val EXTRA_ID = "sticker_id"
        private const val EXTRA_EMOJI = "emoji"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_FROM_STICKER = "from_sticker"
        private const val EXTRA_FROM_CHAT = "from_chat"
        private const val EXTRA_FROM_MESSAGE = "from_message"
    }

    // A sticker in a chat, whose pack to open
    data class PackOf(val stickerId: Long, val chatId: Long, val messageId: Long)
}

// A pack opened from a sticker: its id (as text), title, and whether it is among my packs
data class PackInfo(val id: String, val title: String, val installed: Boolean)

// A sticker pack in the picker: "recent", "faved", or an installed pack's id (as text)
data class StickerPack(val id: String, val title: String, val count: Int)
