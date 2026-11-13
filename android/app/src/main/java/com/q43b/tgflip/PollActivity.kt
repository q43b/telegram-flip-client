package com.q43b.tgflip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

// A poll (since 1.9.0), opened from its media row in a chat. The title is the question; the first row says
// what kind it is (anonymous or public, poll or quiz, multiple choice, closed), then one row per answer:
// "○ Monday" before voting, "● Monday · 3 (60%)" after (results show once I've voted or the poll closed,
// as in Telegram), a quiz's right answer "✓" and my wrong one "✗". OK on an answer votes; with multiple
// choice OK ticks (☐/☑) and "✓ Vote (N chosen)" sends them. "↺ Retract vote" takes my vote back where the
// poll allows it. The chat gets the poll as it is now (Pick's result) and redraws its row.
class PollActivity : AppCompatActivity() {
    private sealed class Row(val label: String) {
        class Info(label: String) : Row(label)
        class Answer(label: String, val index: Int) : Row(label)
        class Vote(label: String) : Row(label)
        class Retract(label: String) : Row(label)
    }

    private lateinit var api: Backend
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var list: WrapListView
    private lateinit var poll: PollInfo
    private var rows: List<Row> = emptyList()
    private val ticked = sortedSetOf<Int>()
    private var chatId = 0L
    private var messageId = 0L
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings_group)  // a title and a list
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        api = Backends.create(this)
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)
        messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0)
        poll = PollInfo.fromJson(JSONObject(intent.getStringExtra(EXTRA_POLL).orEmpty()))
        findViewById<TextView>(R.id.group_title).text = poll.question
        list = findViewById(R.id.group_list)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> choose(position) }
        redraw()
    }

    private val canVote get() = !poll.closed && !poll.voted

    private fun redraw() {
        val kind = MessageFormat.poll(this, poll, "").removeSuffix(": ").removePrefix("📊 ")
        val built = mutableListOf<Row>(Row.Info(kind))
        val shown = poll.answers.any { it.votes != null }
        val sum = poll.answers.sumOf { it.votes ?: 0 }.coerceAtLeast(1)
        poll.answers.forEachIndexed { i, a ->
            val mark = when {
                canVote && poll.multiple -> if (i in ticked) "☑" else "☐"
                a.chosen -> "●"
                else -> "○"
            }
            var label = "$mark ${a.text}"
            if (a.votes != null) label += " · ${a.votes} (${a.votes * 100 / sum}%)"
            if (poll.quiz && a.correct) label += " ✓"
            if (poll.quiz && a.chosen && !a.correct && shown) label += " ✗"
            built += Row.Answer(label, i)
        }
        if (canVote && poll.multiple) built += Row.Vote(getString(R.string.poll_vote, ticked.size))
        if (poll.voted && poll.revoting && !poll.quiz && !poll.closed) built += Row.Retract(getString(R.string.poll_retract))
        built += Row.Info(if (shown || poll.total == null) getString(R.string.poll_total, poll.total ?: 0)
        else getString(R.string.poll_total, poll.total) + " · " + getString(R.string.poll_results_hidden))
        poll.solution?.let { built += Row.Info(getString(R.string.poll_solution, it)) }
        rows = built
        val selected = list.selectedItemPosition
        adapter.clear()
        adapter.addAll(rows.map { it.label })
        if (selected in rows.indices) list.setSelection(selected) else if (rows.size > 1) list.setSelection(1)
    }

    private fun choose(position: Int) {
        when (val row = rows.getOrNull(position)) {
            is Row.Answer -> when {
                !canVote -> {}
                poll.multiple -> {
                    if (!ticked.add(row.index)) ticked.remove(row.index)
                    redraw()
                }
                else -> vote(listOf(row.index))
            }
            is Row.Vote -> if (ticked.isNotEmpty()) vote(ticked.toList())
            is Row.Retract -> vote(emptyList())
            else -> {}
        }
    }

    private fun vote(options: List<Int>) {
        if (busy) return
        busy = true
        Toast.makeText(this, R.string.poll_voting, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                poll = withContext(Dispatchers.IO) { api.vote(chatId, messageId, options) }
                ticked.clear()
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_MESSAGE_ID, messageId).putExtra(EXTRA_POLL, poll.toJson().toString()))
                redraw()
            } catch (e: IOException) {
                Toast.makeText(this@PollActivity, getString(R.string.poll_vote_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    // OK picks, as everywhere; Left goes back like the settings screens
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Opens a poll; its result is the poll as it is after voting (null when nothing changed)
    class Open : ActivityResultContract<Triple<Long, Long, PollInfo>, Pair<Long, PollInfo>?>() {
        override fun createIntent(context: android.content.Context, input: Triple<Long, Long, PollInfo>) =
            Intent(context, PollActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, input.first)
                .putExtra(EXTRA_MESSAGE_ID, input.second)
                .putExtra(EXTRA_POLL, input.third.toJson().toString())

        override fun parseResult(resultCode: Int, intent: Intent?): Pair<Long, PollInfo>? {
            if (resultCode != Activity.RESULT_OK || intent == null) return null
            val json = intent.getStringExtra(EXTRA_POLL) ?: return null
            return intent.getLongExtra(EXTRA_MESSAGE_ID, 0) to PollInfo.fromJson(JSONObject(json))
        }
    }

    private companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_POLL = "poll"
    }
}
