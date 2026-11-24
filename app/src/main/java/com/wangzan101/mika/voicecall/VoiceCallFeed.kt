package com.wangzan101.mika.voicecall

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 通话页对话流里的一条:一句话(你/Mika,说到一半的是 partial),或一张办事步骤卡。 */
sealed interface VoiceCallFeedItem {
    val id: Long

    data class Said(override val id: Long, val speaker: Speaker, val text: String, val partial: Boolean) : VoiceCallFeedItem

    /** 一次办事:步骤按出现顺序排,最后一条是当前这步;[running] 为 false 时已办完(成败看最后一步的话)。 */
    data class Task(override val id: Long, val steps: List<String>, val running: Boolean) : VoiceCallFeedItem

    enum class Speaker { User, Mika }
}

/**
 * 通话页的对话流:把会话的转写(含说到一半的)和路由的办事状态排成一列,给页面直接画气泡。
 * 只管显示,不写存储(写回聊天是 [VoiceCallTranscript] 的事)。各回调可能来自不同线程,内部加锁。
 *
 * - 你说的:partial 段落替换同一条,final 落定。
 * - Mika 说的:草稿逐段替换同一条,final(或被打断时的 non-final)落定。
 * - 办事:路由 busy 起来开一张卡,之后每出现一行新状态就加一步,busy 落下卡片收尾。
 */
class VoiceCallFeed(private val maxItems: Int = MAX_ITEMS) {
    private val _items = MutableStateFlow<List<VoiceCallFeedItem>>(emptyList())
    val items: StateFlow<List<VoiceCallFeedItem>> = _items

    private val lock = Any()
    private var nextId = 0L
    private var openTaskId: Long? = null
    private var taskBaseline: List<String> = emptyList()

    fun onUser(text: String, isFinal: Boolean) = said(VoiceCallFeedItem.Speaker.User, text, partial = !isFinal)

    fun onAssistantDraft(text: String) = said(VoiceCallFeedItem.Speaker.Mika, text, partial = true)

    /** 助手一段话落定;被打断的(isFinal=false)也落定,说到哪算哪。 */
    @Suppress("UNUSED_PARAMETER")
    fun onAssistant(text: String, isFinal: Boolean) = said(VoiceCallFeedItem.Speaker.Mika, text, partial = false)

    /** 路由的办事状态:[busy] 是否在办,[lines] 是路由滚动的状态行(新的在最后)。 */
    fun onTask(busy: Boolean, lines: List<String>) = synchronized(lock) {
        // 状态流可能合并多次更新;找前次后缀与本次前缀的重叠,一次补齐缓冲中全部新增行。
        val overlap = (minOf(taskBaseline.size, lines.size) downTo 0).first {
            taskBaseline.takeLast(it) == lines.take(it)
        }
        val added = lines.drop(overlap)
        // 空闲时也记住上次任务的末尾,新卡打开时只接本次的新行。
        taskBaseline = lines.toList()
        val open = openTaskId
        if (open == null) {
            if (!busy) return@synchronized
            val id = nextId++
            openTaskId = id
            publish(_items.value + VoiceCallFeedItem.Task(id, added, running = true))
            return@synchronized
        }
        var current = _items.value
        if (added.isNotEmpty()) {
            current = current.map { item ->
                if (item is VoiceCallFeedItem.Task && item.id == open) {
                    item.copy(steps = item.steps + added)
                } else item
            }
        }
        if (!busy) {
            openTaskId = null
            current = current.map { if (it is VoiceCallFeedItem.Task && it.id == open) it.copy(running = false) else it }
        }
        publish(current)
    }

    private fun said(speaker: VoiceCallFeedItem.Speaker, text: String, partial: Boolean) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        synchronized(lock) {
            val current = _items.value
            val index = current.indexOfLast { it is VoiceCallFeedItem.Said && it.speaker == speaker && it.partial }
            publish(
                if (index >= 0) {
                    val item = current[index] as VoiceCallFeedItem.Said
                    current.toMutableList().also { it[index] = item.copy(text = clean, partial = partial) }
                } else {
                    current + VoiceCallFeedItem.Said(nextId++, speaker, clean, partial)
                },
            )
        }
    }

    private fun publish(items: List<VoiceCallFeedItem>) {
        _items.value = if (items.size > maxItems) items.takeLast(maxItems) else items
    }

    companion object {
        const val MAX_ITEMS = 80
    }
}
