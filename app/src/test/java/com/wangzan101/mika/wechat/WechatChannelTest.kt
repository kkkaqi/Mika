package com.wangzan101.mika.wechat

import com.wangzan101.mika.core.run.ChannelInput
import com.wangzan101.mika.core.run.ChannelInputSink
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.ChannelGateway
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.MemoryOwnerIdentity
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.action.ConfirmChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.wangzan101.mika.core.wechat.WechatAccount
import com.wangzan101.mika.core.wechat.WechatApi
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class WechatChannelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }

    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun acknowledgedTaskReplayCannotAnswerANewQuestion() = runTest {
        MockWebServer().use { server ->
            val dir = tmp.newFolder()
            val store = WechatStore(dir, cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val peer = store.peer("owner")
            store.journal("task-message", peer, "原任务")
            store.acknowledge("task-message")
            val channel = WechatChannel(WechatStore(dir, cipher), WechatApi(OkHttpClient(), "0.1.36"), log = {}, activeAsk = {
                Delivery.Ask("delivery", peer.threadId, "new-question", Delivery.Ask.Kind.Input, "选谁？")
            })
            val received = mutableListOf<ChannelInput>()
            channel.start(ChannelInputSink { received += it })
            server.enqueue(response("""{"get_updates_buf":"next","msgs":[
                {"message_id":"task-message","from_user_id":"owner","message_type":1,
                 "item_list":[{"type":1,"text_item":{"text":"原任务"}}]}]}"""))

            channel.pollOnce()

            assertTrue(received.isEmpty())
        }
    }

    @Test fun ownerReplyAnswersTheActiveQuestionAndIsAcknowledged() = runTest {
        MockWebServer().use { server ->
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val peer = store.peer("owner")
            var ask: Delivery.Ask? = Delivery.Ask("delivery", peer.threadId, "question", Delivery.Ask.Kind.Input, "选谁？")
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.36"), log = {}, activeAsk = { ask })
            val events = mutableListOf<String>()
            ChannelGateway(MemoryOwnerIdentity().also { it.bind(peer) }, { ask }, object : ChannelGateway.Route {
                override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) { events += "new:$text" }
                override suspend fun answer(threadId: String, askId: String, text: String): Boolean {
                    events += "answer:$askId:$text"
                    ask = null
                    return true
                }
                override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice) = false
                override suspend fun stop(runId: RunId) = Unit
                override suspend fun cancelAsk(threadId: String) { events += "cancel" }
            }).start(channel)
            server.enqueue(response("""{"get_updates_buf":"next","msgs":[
                {"message_id":1,"from_user_id":"owner","message_type":1,
                 "item_list":[{"type":1,"text_item":{"text":"张三"}}]}]}"""))

            channel.pollOnce()

            assertEquals(listOf("answer:question:张三"), events)
            assertTrue(store.pending().isEmpty())
        }
    }

    @Test fun ordinaryTextCannotBecomeAPaymentConfirmation() = runTest {
        MockWebServer().use { server ->
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val peer = store.peer("owner")
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.36"), log = {}, activeAsk = {
                Delivery.Ask("delivery", peer.threadId, "payment", Delivery.Ask.Kind.Confirm, "付款？")
            })
            val received = mutableListOf<ChannelInput>()
            channel.start(ChannelInputSink { received += it })
            server.enqueue(response("""{"msgs":[{"message_id":1,"from_user_id":"owner","message_type":1,
                "item_list":[{"type":1,"text_item":{"text":"同意"}}]}]}"""))
            channel.pollOnce()
            assertEquals(null, (received.single() as ChannelInput.Text).replyToAskId)
        }
    }

    @Test fun pendingReplyRetainsItsQuestionWhenReplayedAfterRestart() = runTest {
        MockWebServer().use { server ->
            val dir = tmp.newFolder()
            val first = WechatStore(dir, cipher)
            first.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            first.setEnabled(true)
            val peer = first.peer("owner")
            first.journal("reply", peer, "张三", replyToAskId = "original-question")
            val reopened = WechatStore(dir, cipher)
            val channel = WechatChannel(reopened, WechatApi(OkHttpClient(), "0.1.36"), log = {})
            val received = mutableListOf<ChannelInput>()
            channel.start(ChannelInputSink { received += it; channel.acknowledge((it as ChannelInput.Text).messageId) })
            server.enqueue(response("""{"get_updates_buf":"next"}"""))
            channel.pollOnce()
            assertEquals("original-question", (received.single() as ChannelInput.Text).replyToAskId)
            assertTrue(reopened.pending().isEmpty())
        }
    }

    @Test fun pausingReceptionStillDeliversAnExistingTaskResult() = runTest {
        MockWebServer().use { server ->
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val peer = store.peer("owner")
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.36"), log = {})
            store.setEnabled(false)
            server.enqueue(response("""{"ret":0}"""))

            assertEquals(DeliveryState.Delivered,
                Outbox().deliver(channel, peer, Delivery.Result("result:existing", peer.threadId, "完成")).state)
            assertEquals("/ilink/bot/sendmessage", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        }
    }

    @Test fun closingRegistrationDuringPollDiscardsItsMessagesAndCursor() = invalidatedPoll { _, registration -> registration.close() }

    @Test fun pausingReceptionDuringPollDiscardsItsMessagesAndCursor() = invalidatedPoll { store, _ -> store.setEnabled(false) }

    private fun invalidatedPoll(invalidate: (WechatStore, AutoCloseable) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            store.saveCursor("old")
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.36"), log = {})
            val received = mutableListOf<ChannelInput>()
            val registration = channel.start(ChannelInputSink { received += it })
            server.enqueue(response("""{"get_updates_buf":"next","msgs":[
                {"message_id":1,"from_user_id":"owner","message_type":1,
                 "item_list":[{"type":1,"text_item":{"text":"任务"}}]}]}""")
                .setBodyDelay(300, TimeUnit.MILLISECONDS))
            val poll = async(Dispatchers.IO) { channel.pollOnce() }
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
            invalidate(store, registration)
            withTimeout(2_000) { poll.await() }

            assertTrue(received.isEmpty())
            assertTrue(store.pending().isEmpty())
            assertEquals("old", store.cursor())
        }
    }

    @Test fun concurrentPollsUseTheCursorCommittedByThePreviousPoll() = runBlocking {
        MockWebServer().use { server ->
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            store.saveCursor("old")
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.36"), log = {})
            channel.start(ChannelInputSink {})
            server.enqueue(response("""{"get_updates_buf":"next"}""").setBodyDelay(300, TimeUnit.MILLISECONDS))
            server.enqueue(response("""{"get_updates_buf":"last"}"""))
            val first = async(Dispatchers.IO) { channel.pollOnce() }
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
            val second = async(Dispatchers.IO) { channel.pollOnce() }
            withTimeout(2_000) { first.await(); second.await() }

            assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8().contains("\"get_updates_buf\":\"next\""))
            assertEquals("last", store.cursor())
        }
    }

    @Test fun pollingJournalsOnlyOwnerTextAndAcknowledgesAfterTaskRegistration() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val received = mutableListOf<ChannelInput>()
            channel.start(ChannelInputSink { received += it })
            server.enqueue(response("""{"get_updates_buf":"next","msgs":[
                {"message_id":1,"from_user_id":"owner","message_type":1,"context_token":"ctx","item_list":[{"type":1,"text_item":{"text":"任务"}}]},
                {"message_id":2,"from_user_id":"stranger","message_type":1,"item_list":[{"type":1,"text_item":{"text":"不收"}}]}
            ]}"""))

            channel.pollOnce()

            val input = received.single() as ChannelInput.Text
            assertEquals("1", input.messageId)
            assertEquals("任务", input.text)
            assertEquals("wechat", input.peer.channelId)
            assertEquals("next", store.cursor())
            assertEquals("ctx", store.contextToken("owner"))
            assertEquals(1, store.pending().size)
            channel.acknowledge("1")
            assertEquals(emptyList<PendingWechatMessage>(), store.pending())
        }
    }

    @Test fun acceptanceNoticeIsSentAsARealMessage() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val peer = store.peer("owner")
            server.enqueue(response("""{"ret":0}"""))

            assertEquals(SendOutcome.Delivered,
                channel.send(peer, Delivery.Notice("accepted:1", peer.threadId, "收到，任务已登记。")))
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("accepted:1"))
            assertTrue(body.contains("收到，任务已登记。"))
        }
    }

    @Test fun sendDistinguishesRejectionFromUncertainNetworkResult() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val peer = store.peer("owner")
            val delivery = Delivery.Result("result:1", peer.threadId, "完成")
            server.enqueue(response("""{"ret":-1,"errmsg":"rejected"}"""))
            assertEquals(SendOutcome.Failed, channel.send(peer, delivery))
            server.enqueue(response("""{"ret":0}""").setResponseCode(500))
            assertEquals(SendOutcome.Uncertain, channel.send(peer, delivery))
        }
    }
}
