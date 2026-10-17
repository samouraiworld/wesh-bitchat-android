package com.bitchat.android.ui

import android.app.Application
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.SessionHiddenPeers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SessionHiddenPeersGateTest {
    private val peerID = "aaaabbbbccccdddd"
    private val haptics = AtomicInteger()
    private lateinit var privateChats: PrivateChatManager
    private lateinit var notifications: NotificationManager
    private lateinit var handler: MeshDelegateHandler

    @Before
    fun setUp() {
        SessionHiddenPeers.clear()
        val scope = TestScope(UnconfinedTestDispatcher())
        val state = ChatState(scope).apply { setNickname("Synthetic") }
        val messages = MessageManager(state)
        privateChats = PrivateChatManager(state, messages, DataManager(RuntimeEnvironment.getApplication()), mock<NoiseSessionDelegate>())
        notifications = mock()
        handler = MeshDelegateHandler(state, messages, privateChats, notifications, scope,
            onHapticFeedback = { haptics.incrementAndGet() }, getMeshService = { mock() })
    }

    @After
    fun tearDown() = SessionHiddenPeers.clear()

    @Test
    fun `a session hidden peer is blocked by the authoritative gate before haptics or mentions`() {
        SessionHiddenPeers.hide(peerID)
        assertTrue(privateChats.isPeerBlocked(peerID))

        handler.didReceiveMessage(BitchatMessage(id = "hidden", sender = "Spoof", content = "@Synthetic hello",
            timestamp = Date(), senderPeerID = peerID))

        assertEquals(0, haptics.get())
        verify(notifications, never()).showMeshMentionNotification(any(), any(), any())
    }

    @Test
    fun `unhiding restores delivery`() {
        SessionHiddenPeers.hide(peerID)
        SessionHiddenPeers.unhide(peerID)
        assertFalse(privateChats.isPeerBlocked(peerID))

        handler.didReceiveMessage(BitchatMessage(id = "visible", sender = "Synthetic peer", content = "hello",
            timestamp = Date(), senderPeerID = peerID))
        assertEquals(1, haptics.get())
    }
}
