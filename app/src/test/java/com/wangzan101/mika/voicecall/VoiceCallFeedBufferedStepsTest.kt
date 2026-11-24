package com.wangzan101.mika.voicecall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VoiceCallFeedBufferedStepsTest {
    private fun VoiceCallFeed.task() = items.value.filterIsInstance<VoiceCallFeedItem.Task>().last()

    @Test fun coalescedUpdatesKeepEveryBufferedStep() {
        val feed = VoiceCallFeed()
        feed.onTask(false, emptyList())
        feed.onTask(true, emptyList())
        feed.onTask(true, listOf("open", "read", "summarize"))
        feed.onTask(false, listOf("open", "read", "summarize", "done"))
        assertEquals(listOf("open", "read", "summarize", "done"), feed.task().steps)
        assertFalse(feed.task().running)
    }

    @Test fun rollingWindowAppendsAllNewStepsWithoutRepeatingOverlap() {
        val feed = VoiceCallFeed()
        feed.onTask(false, listOf("previous"))
        feed.onTask(true, listOf("previous"))
        feed.onTask(true, listOf("previous", "open", "read"))
        feed.onTask(true, listOf("read", "summarize", "send"))
        feed.onTask(false, listOf("summarize", "send", "done"))
        assertEquals(listOf("open", "read", "summarize", "send", "done"), feed.task().steps)
    }

    @Test fun fullyReplacedWindowAndRepeatedLabelsKeepTheirOrder() {
        val feed = VoiceCallFeed()
        feed.onTask(true, emptyList())
        feed.onTask(true, listOf("open", "read"))
        feed.onTask(true, listOf("next", "open", "read"))
        feed.onTask(true, listOf("open", "read", "done"))
        feed.onTask(true, listOf("open", "read", "done"))
        feed.onTask(false, listOf("open", "read", "done"))
        assertEquals(listOf("open", "read", "next", "open", "read", "done"), feed.task().steps)
    }

    @Test fun openingCardKeepsStepsThatArrivedTogetherWithBusy() {
        val feed = VoiceCallFeed()
        feed.onTask(false, listOf("previous"))
        feed.onTask(true, listOf("previous", "open", "read"))
        feed.onTask(false, listOf("previous", "open", "read", "done"))
        assertEquals(listOf("open", "read", "done"), feed.task().steps)

        feed.onTask(true, listOf("read", "done", "next task"))
        assertEquals(2, feed.items.value.filterIsInstance<VoiceCallFeedItem.Task>().size)
        assertEquals(listOf("next task"), feed.task().steps)
    }

    @Test fun firstObservationOfAnActiveTaskKeepsItsBufferedProgress() {
        val feed = VoiceCallFeed()
        feed.onTask(true, listOf("open", "read"))
        assertEquals(listOf("open", "read"), feed.task().steps)
    }
}
