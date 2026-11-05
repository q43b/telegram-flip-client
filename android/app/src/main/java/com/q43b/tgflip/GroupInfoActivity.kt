package com.q43b.tgflip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

// A group's (or channel's) info: photo, name, member count and the member list, each
// member with their photo. OK on a member opens their profile; "Show more members"
// loads another page. Opened with Right in a group chat; Left or Back closes it.
// Same layout as the profile screen.
class GroupInfoActivity : AppCompatActivity() {
    private lateinit var photos: PhotoLoader  // the group's photo, big
    private lateinit var memberPhotos: PhotoLoader  // members' photos, list size
    private lateinit var photoView: ImageView
    private lateinit var status: TextView
    private lateinit var adapter: ChatRowAdapter
    private var chatId = 0L
    private var name = ""
    private var group: Group? = null
    private var memberLimit = PAGE_SIZE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_profile)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        photoView = findViewById(R.id.profile_photo)
        status = findViewById(R.id.status)
        status.visibility = View.VISIBLE  // here it also holds the member count
        val settings = ServerSettings(this)
        val api = ApiClient(settings.getAddress(), settings.getToken().orEmpty())
        photos = PhotoLoader(this, lifecycleScope, PhotoLoader.PROFILE_SIZE_DP) { showPhoto() }
        memberPhotos = PhotoLoader(this, lifecycleScope) { adapter.notifyDataSetChanged() }
        photos.api = api
        memberPhotos.api = api

        val list = findViewById<ListView>(R.id.profile_rows)
        adapter = ChatRowAdapter(this) { position ->
            memberAt(position)?.let { memberPhotos.drawable(it.id, it.name, it.photo) }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> onRowChosen(position) }
        list.requestFocus()

        showGroup()
        load()
    }

    // Left closes, like Settings and profiles
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun load() {
        val api = photos.api ?: return
        status.text = getString(R.string.group_loading)
        lifecycleScope.launch {
            try {
                group = withContext(Dispatchers.IO) { api.getGroup(chatId, memberLimit) }
            } catch (e: BadTokenException) {
                status.text = getString(R.string.error_bad_token)
                return@launch
            } catch (e: IOException) {
                status.text = getString(R.string.group_not_loaded)
                return@launch
            }
            showGroup()
        }
    }

    private fun memberAt(position: Int): Member? = group?.members?.getOrNull(position)

    // A full page that is still short of the total: there are more to load
    private fun hasMore(g: Group): Boolean {
        val members = g.members ?: return false
        return !g.partial && members.size >= memberLimit && members.size < (g.total ?: 0)
    }

    private fun showGroup() {
        val g = group
        findViewById<TextView>(R.id.profile_name).text = g?.name?.ifEmpty { null } ?: name
        showPhoto()
        g ?: return
        val count = g.total?.let {
            getString(if (g.channel) R.string.group_subscribers else R.string.group_members, it)
        }
        status.text = when {
            g.members == null -> listOfNotNull(count, getString(R.string.group_members_hidden)).joinToString(" · ")
            g.partial -> listOfNotNull(count, getString(R.string.group_members_partial)).joinToString(" · ")
            else -> count.orEmpty()
        }
        adapter.clear()
        adapter.addAll(g.members.orEmpty().map(::memberRow))
        if (hasMore(g)) adapter.add(getString(R.string.group_more_members))
    }

    // "Anna · admin", "Some Bot · bot"
    private fun memberRow(member: Member): CharSequence {
        val notes = listOfNotNull(
            when (member.role) {
                "owner" -> getString(R.string.member_owner)
                "admin" -> getString(R.string.member_admin)
                else -> null
            },
            if (member.bot) getString(R.string.member_bot) else null,
        )
        return (listOf(member.name) + notes).joinToString(" · ")
    }

    private fun showPhoto() {
        val g = group
        photoView.setImageDrawable(photos.drawable(chatId, g?.name ?: name, g?.photo))
    }

    private fun onRowChosen(position: Int) {
        val member = memberAt(position)
        if (member != null) {
            ProfileActivity.start(this, member.id, member.name, canMessage = true)
        } else if (group?.let(::hasMore) == true) {
            // Keeps the selection on this row, which becomes the first newly loaded member
            memberLimit += PAGE_SIZE
            load()
        }
    }

    companion object {
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_NAME = "name"
        private const val PAGE_SIZE = 50

        fun start(activity: Activity, chatId: Long, name: String) {
            activity.startActivity(
                Intent(activity, GroupInfoActivity::class.java)
                    .putExtra(EXTRA_CHAT_ID, chatId)
                    .putExtra(EXTRA_NAME, name)
            )
        }
    }
}
