package com.bitchat.android.mesh

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshBluetoothHandoffTest {
    private val peerID = "aaaabbbbccccdddd"
    private val address = "synthetic-link"
    private lateinit var scope: CoroutineScope
    private lateinit var tracker: BluetoothConnectionTracker
    private lateinit var broadcaster: BluetoothPacketBroadcaster
    private lateinit var gatt: BluetoothGatt
    private lateinit var device: BluetoothDevice
    private lateinit var characteristic: BluetoothGattCharacteristic
    @Before fun setup() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        tracker = mock()
        gatt = mock()
        characteristic = BluetoothGattCharacteristic(java.util.UUID.fromString("00000000-0000-4000-8000-000000000001"),
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        device = mock()
        whenever(device.address).thenReturn(address)
        val link = BluetoothConnectionTracker.DeviceConnection(device, gatt, characteristic,
            isClient = true, peerID = peerID, linkID = "synthetic-generation")
        whenever(tracker.getSubscribedDevices()).thenReturn(emptyList())
        whenever(tracker.getConnectedDevices()).thenReturn(mapOf(address to link))
        whenever(tracker.getDeviceConnection(address)).thenReturn(link)
        whenever(tracker.getLink(address, link.linkID)).thenReturn(link)
        whenever(tracker.addressPeerMap).thenReturn(java.util.concurrent.ConcurrentHashMap<String, String>().apply { put(address, peerID) })
        broadcaster = BluetoothPacketBroadcaster(scope, tracker, FragmentManager(), "1111222233334444")
    }
    @After fun cleanup() { broadcaster.shutdown(); scope.cancel() }
    private fun packet(bytes: Int): RoutedPacket {
        val payload = ByteArray(bytes).also { Random(42).nextBytes(it) }
        return RoutedPacket(BitchatPacket(version = 1u, type = MessageType.NOISE_ENCRYPTED.value,
            senderID = MeshPacketUtils.hexStringToByteArray("1111222233334444"),
            recipientID = MeshPacketUtils.hexStringToByteArray(peerID), timestamp = 1uL, payload = payload, ttl = 7u))
    }
    private fun controlPacket(): RoutedPacket = packet(32).let {
        it.copy(packet = it.packet.copy(type = MessageType.ANNOUNCE.value))
    }
    @Test fun `no live target is not a handoff even when the actor accepts a fragmented request`() = runBlocking<Unit> {
        whenever(tracker.getConnectedDevices()).thenReturn(emptyMap())
        assertFalse(withTimeout(5_000) { broadcaster.broadcastPacketAndAwaitAcceptance(packet(2000), null, null) { true } })
        verify(gatt, never()).writeCharacteristic(any())
    }
    @Test fun `rejected GATT operation produces failure rather than sent or unbounded retries`() = runBlocking<Unit> {
        whenever(gatt.writeCharacteristic(any())).thenReturn(false)
        assertEquals(BoundSendOutcome.NOT_SENT, withTimeout(5_000) {
            broadcaster.broadcastPacketAndAwaitOutcome(packet(32), null, null) { true }
        })
        verify(gatt, times(1)).writeCharacteristic(any())
    }

    @Test fun `refused control write invalidates its link even when retries cannot execute`() = runBlocking<Unit> {
        assertRefusedControlHeadReleasesWaitingText(serverSide = false)
    }

    @Test fun `refused control notification invalidates its link even when retries cannot execute`() = runBlocking<Unit> {
        assertRefusedControlHeadReleasesWaitingText(serverSide = true)
    }

    @Test fun `repeated control API refusals cannot extend the head deadline`() = runBlocking<Unit> {
        assertRefusedControlHeadReleasesWaitingText(serverSide = false, pauseRetries = false)
    }

    private suspend fun assertRefusedControlHeadReleasesWaitingText(
        serverSide: Boolean, pauseRetries: Boolean = true
    ) = coroutineScope {
        val firstCall = CompletableDeferred<Unit>()
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val server: BluetoothGattServer = mock()
        if (serverSide) {
            val link = BluetoothConnectionTracker.DeviceConnection(
                device, isClient = false, peerID = peerID, linkID = "synthetic-generation"
            )
            whenever(tracker.getSubscribedDevices()).thenReturn(listOf(device))
            whenever(tracker.getConnectedDevices()).thenReturn(emptyMap())
            whenever(tracker.getDeviceConnection(address)).thenReturn(link)
            whenever(tracker.getLink(address, link.linkID)).thenReturn(link)
        }
        broadcaster.shutdown()
        if (pauseRetries) {
            scope.cancel()
            // Do not execute any scheduled retry: recovery must have its own live deadline.
            scope = CoroutineScope(StandardTestDispatcher() + SupervisorJob())
        }
        broadcaster = BluetoothPacketBroadcaster(
            scope, tracker, FragmentManager(), "1111222233334444",
            gattCallbackTimeoutMs = 5_000L, gattApiRefusalTimeoutMs = if (pauseRetries) 50L else 250L
        )
        val refuse = {
            if (calls.incrementAndGet() == 1) {
                firstCall.complete(Unit)
                check(releaseFirst.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            false
        }
        if (serverSide) {
            whenever(server.notifyCharacteristicChanged(any(), any(), any())).thenAnswer { refuse() }
        } else {
            whenever(gatt.writeCharacteristic(any())).thenAnswer { refuse() }
        }
        assertTrue(broadcaster.sendPacketToLink(
            controlPacket(), address, "synthetic-generation", server, characteristic
        ))
        withTimeout(1_000) { firstCall.await() }
        val outcome = async {
            broadcaster.broadcastPacketAndAwaitOutcome(packet(32), server, characteristic) { true }
        }
        try {
            withTimeout(1_000) {
                while (broadcaster.pendingSendCountForTesting(address, "synthetic-generation") != 2) yield()
            }
        } finally {
            releaseFirst.countDown()
        }
        assertEquals(BoundSendOutcome.NOT_SENT, withTimeout(1_000) { outcome.await() })
        val refusedCalls = calls.get()
        if (pauseRetries) assertEquals(1, refusedCalls) else assertTrue(refusedCalls > 1)
        assertEquals(0, broadcaster.pendingSendCountForTesting(address, "synthetic-generation"))
        verify(tracker).cleanupDeviceConnectionIfCurrent(address, "synthetic-generation")
        if (serverSide) verify(server).cancelConnection(device)
        else { verify(gatt).disconnect(); verify(gatt).close() }

        // The old generation cannot be revived by a late completion callback.
        if (serverSide) broadcaster.onGattServerNotificationComplete(address, "synthetic-generation", BluetoothGatt.GATT_SUCCESS)
        else broadcaster.onGattClientWriteComplete(address, "synthetic-generation", BluetoothGatt.GATT_SUCCESS)
        assertEquals(0, broadcaster.pendingSendCountForTesting(address, "synthetic-generation"))
        assertEquals(refusedCalls, calls.get())

        // A genuinely new exact link can progress without restarting the broadcaster or router.
        val replacementGatt: BluetoothGatt = mock()
        val replacement = BluetoothConnectionTracker.DeviceConnection(
            device, replacementGatt, characteristic, isClient = !serverSide,
            peerID = peerID, linkID = "replacement-synthetic-generation"
        )
        whenever(tracker.getDeviceConnection(address)).thenReturn(replacement)
        whenever(tracker.getLink(address, replacement.linkID)).thenReturn(replacement)
        val replacementStarted = CompletableDeferred<Unit>()
        if (serverSide) {
            whenever(server.notifyCharacteristicChanged(any(), any(), any())).thenAnswer {
                replacementStarted.complete(Unit)
                true
            }
        } else {
            whenever(tracker.getConnectedDevices()).thenReturn(mapOf(address to replacement))
            whenever(replacementGatt.writeCharacteristic(any())).thenAnswer {
                replacementStarted.complete(Unit)
                true
            }
        }
        val replacementOutcome = async {
            broadcaster.broadcastPacketAndAwaitOutcome(packet(32), server, characteristic) { true }
        }
        withTimeout(1_000) { replacementStarted.await() }
        if (serverSide) broadcaster.onGattServerNotificationComplete(address, "synthetic-generation", BluetoothGatt.GATT_SUCCESS)
        else broadcaster.onGattClientWriteComplete(address, "synthetic-generation", BluetoothGatt.GATT_SUCCESS)
        assertFalse(replacementOutcome.isCompleted)
        if (serverSide) broadcaster.onGattServerNotificationComplete(address, replacement.linkID, BluetoothGatt.GATT_SUCCESS)
        else broadcaster.onGattClientWriteComplete(address, replacement.linkID, BluetoothGatt.GATT_SUCCESS)
        assertEquals(BoundSendOutcome.SENT, withTimeout(1_000) { replacementOutcome.await() })
        verify(tracker, never()).cleanupDeviceConnectionIfCurrent(address, replacement.linkID)
        assertEquals(0, broadcaster.pendingSendCountForTesting(address, replacement.linkID))
    }

    @Test fun `temporary control API refusal preserves queued text and succeeds once the stack accepts`() = runBlocking<Unit> {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        broadcaster.shutdown()
        broadcaster = BluetoothPacketBroadcaster(
            scope, tracker, FragmentManager(), "1111222233334444",
            gattCallbackTimeoutMs = 5_000L
        )
        whenever(gatt.writeCharacteristic(any())).thenAnswer {
            if (calls.incrementAndGet() == 1) false
            else {
                broadcaster.onGattClientWriteComplete(address, "synthetic-generation", BluetoothGatt.GATT_SUCCESS)
                true
            }
        }
        assertTrue(broadcaster.sendPacketToLink(controlPacket(), address, "synthetic-generation", null, null))
        assertEquals(BoundSendOutcome.SENT, withTimeout(1_000) {
            broadcaster.broadcastPacketAndAwaitOutcome(packet(32), null, null) { true }
        })
        assertEquals(3, calls.get())
        verify(tracker, never()).cleanupDeviceConnectionIfCurrent(any(), any())
        verify(gatt, never()).disconnect()
        verify(gatt, never()).close()
    }

    @Test fun `control refusal deadline releases waiting text while a subsequent API call has not returned`() = runBlocking<Unit> {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val releaseSecond = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        broadcaster.shutdown()
        broadcaster = BluetoothPacketBroadcaster(
            scope, tracker, FragmentManager(), "1111222233334444",
            gattCallbackTimeoutMs = 5_000L, gattApiRefusalTimeoutMs = 250L
        )
        whenever(gatt.writeCharacteristic(any())).thenAnswer {
            if (calls.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                check(releaseFirst.await(5, java.util.concurrent.TimeUnit.SECONDS))
                false
            } else {
                secondStarted.complete(Unit)
                check(releaseSecond.await(5, java.util.concurrent.TimeUnit.SECONDS))
                true
            }
        }
        assertTrue(broadcaster.sendPacketToLink(controlPacket(), address, "synthetic-generation", null, null))
        withTimeout(1_000) { firstStarted.await() }
        val waiting = async {
            broadcaster.broadcastPacketAndAwaitOutcome(packet(32), null, null) { true }
        }
        try {
            withTimeout(1_000) {
                while (broadcaster.pendingSendCountForTesting(address, "synthetic-generation") != 2) yield()
            }
            releaseFirst.countDown()
            withTimeout(1_000) { secondStarted.await() }
            assertEquals(BoundSendOutcome.NOT_SENT, withTimeout(1_000) { waiting.await() })
            assertEquals(1L, releaseSecond.count)
            verify(tracker).cleanupDeviceConnectionIfCurrent(address, "synthetic-generation")
            assertEquals(0, broadcaster.pendingSendCountForTesting(address, "synthetic-generation"))
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
        }
    }

    @Test fun `local API acceptance followed by disconnect is unconfirmed`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        whenever(gatt.writeCharacteristic(any())).thenAnswer { started.complete(Unit); true }
        val outcome = async {
            broadcaster.broadcastPacketAndAwaitOutcome(packet(32), null, null) { true }
        }
        withTimeout(1_000) { started.await() }
        broadcaster.onLinkDisconnected(address, "synthetic-generation")
        assertEquals(BoundSendOutcome.UNCONFIRMED, withTimeout(1_000) { outcome.await() })
    }

    @Test fun `disconnect between GATT API return and result publication stays unconfirmed`() = runBlocking<Unit> {
        val apiReturned = java.util.concurrent.CountDownLatch(1)
        val publishResult = java.util.concurrent.CountDownLatch(1)
        broadcaster.shutdown()
        broadcaster = BluetoothPacketBroadcaster(
            scope,
            tracker,
            FragmentManager(),
            "1111222233334444",
            afterGattApiResultForTesting = { accepted ->
                if (accepted) {
                    apiReturned.countDown()
                    publishResult.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        )
        whenever(gatt.writeCharacteristic(any())).thenReturn(true)
        val outcome = async {
            broadcaster.broadcastPacketAndAwaitOutcome(packet(32), null, null) { true }
        }

        try {
            withTimeout(1_000) {
                while (apiReturned.count != 0L) delay(10)
            }
            broadcaster.onLinkDisconnected(address, "synthetic-generation")
        } finally {
            publishResult.countDown()
        }

        assertEquals(BoundSendOutcome.UNCONFIRMED, withTimeout(1_000) { outcome.await() })
    }

    @Test fun `local API acceptance waits for fallback invalidation before becoming unconfirmed`() = runBlocking<Unit> {
        val cleanupStarted = java.util.concurrent.CountDownLatch(1)
        val releaseCleanup = java.util.concurrent.CountDownLatch(1)
        whenever(tracker.cleanupDeviceConnectionIfCurrent(address, "synthetic-generation")).thenAnswer {
            cleanupStarted.countDown()
            releaseCleanup.await(5, java.util.concurrent.TimeUnit.SECONDS)
            throw IllegalStateException("private tracker detail")
        }
        broadcaster.shutdown()
        broadcaster = BluetoothPacketBroadcaster(
            scope, tracker, FragmentManager(), "1111222233334444", gattCallbackTimeoutMs = 50L
        )
        whenever(gatt.writeCharacteristic(any())).thenReturn(true)
        doThrow(SecurityException("private disconnect detail")).whenever(gatt).disconnect()

        val outcome = async(Dispatchers.Default) {
            broadcaster.broadcastPacketAndAwaitOutcome(packet(32), null, null) { true }
        }
        try {
            assertTrue(cleanupStarted.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("outcome completed before exact-link cleanup returned", outcome.isCompleted)
        } finally {
            releaseCleanup.countDown()
        }

        assertEquals(BoundSendOutcome.UNCONFIRMED, withTimeout(1_000) { outcome.await() })
        verify(tracker).cleanupDeviceConnectionIfCurrent(address, "synthetic-generation")
        verify(gatt).disconnect()
        verify(gatt).close()
    }

    @Test fun `local API acceptance waits for manager invalidation before becoming unconfirmed`() = runBlocking<Unit> {
        val handlerStarted = java.util.concurrent.CountDownLatch(1)
        val releaseHandler = java.util.concurrent.CountDownLatch(1)
        var handledAddress: String? = null
        var handledLinkID: String? = null
        var handledAsClient: Boolean? = null
        var handledDevice: BluetoothDevice? = null
        broadcaster.shutdown()
        broadcaster = BluetoothPacketBroadcaster(
            scope, tracker, FragmentManager(), "1111222233334444", gattCallbackTimeoutMs = 50L
        )
        broadcaster.setLinkTimeoutHandler { deviceAddress, linkID, isClient, device ->
            handledAddress = deviceAddress
            handledLinkID = linkID
            handledAsClient = isClient
            handledDevice = device
            handlerStarted.countDown()
            releaseHandler.await(5, java.util.concurrent.TimeUnit.SECONDS)
            throw IllegalStateException("private manager detail")
        }
        whenever(gatt.writeCharacteristic(any())).thenReturn(true)

        val outcome = async(Dispatchers.Default) {
            broadcaster.broadcastPacketAndAwaitOutcome(packet(32), null, null) { true }
        }
        try {
            assertTrue(handlerStarted.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("outcome completed before manager invalidation returned", outcome.isCompleted)
            // This exceeds the two former gattCallbackTimeoutMs + 1s wrappers. Neither the queue
            // await nor the public API may publish while exact-link invalidation is still blocked.
            delay(1_100)
            assertFalse("outer timeout completed before manager invalidation", outcome.isCompleted)
        } finally {
            releaseHandler.countDown()
        }

        assertEquals(BoundSendOutcome.UNCONFIRMED, withTimeout(1_000) { outcome.await() })
        assertEquals(address, handledAddress)
        assertEquals("synthetic-generation", handledLinkID)
        assertEquals(true, handledAsClient)
        assertSame(device, handledDevice)
        verify(tracker).cleanupDeviceConnectionIfCurrent(address, "synthetic-generation")
        verify(gatt).disconnect()
        verify(gatt).close()
    }

    @Test fun `stuck platform API is bounded by exact link invalidation`() = runBlocking<Unit> {
        val apiStarted = java.util.concurrent.CountDownLatch(1)
        val releaseApi = java.util.concurrent.CountDownLatch(1)
        val apiReturned = java.util.concurrent.CountDownLatch(1)
        broadcaster.shutdown()
        broadcaster = BluetoothPacketBroadcaster(
            scope, tracker, FragmentManager(), "1111222233334444", gattCallbackTimeoutMs = 50L
        )
        whenever(gatt.writeCharacteristic(any())).thenAnswer {
            apiStarted.countDown()
            releaseApi.await(5, java.util.concurrent.TimeUnit.SECONDS)
            apiReturned.countDown()
            true
        }

        val outcome = async(Dispatchers.Default) {
            broadcaster.broadcastPacketAndAwaitOutcome(packet(32), null, null) { true }
        }
        try {
            assertTrue(apiStarted.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(BoundSendOutcome.UNCONFIRMED, withTimeout(1_000) { outcome.await() })
            verify(tracker).cleanupDeviceConnectionIfCurrent(address, "synthetic-generation")
            verify(gatt).disconnect()
            verify(gatt).close()
        } finally {
            releaseApi.countDown()
        }
        assertTrue(apiReturned.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(0, broadcaster.pendingSendCountForTesting(address, "synthetic-generation"))
        verify(gatt, times(1)).writeCharacteristic(any())
    }

    @Test fun `timeout invalidation precedes outcome across repeated sends`() = runBlocking<Unit> {
        val invalidations = java.util.concurrent.atomic.AtomicInteger()
        whenever(tracker.cleanupDeviceConnectionIfCurrent(address, "synthetic-generation")).thenAnswer {
            invalidations.incrementAndGet()
            true
        }
        broadcaster.shutdown()
        broadcaster = BluetoothPacketBroadcaster(
            scope, tracker, FragmentManager(), "1111222233334444", gattCallbackTimeoutMs = 1L
        )
        whenever(gatt.writeCharacteristic(any())).thenReturn(true)

        repeat(50) { iteration ->
            assertEquals(BoundSendOutcome.UNCONFIRMED, withTimeout(1_000) {
                broadcaster.broadcastPacketAndAwaitOutcome(packet(32), null, null) { true }
            })
            assertEquals(iteration + 1, invalidations.get())
        }
    }

    @Test fun `callback failures after local API acceptance are unconfirmed`() = runBlocking<Unit> {
        val writes = Channel<Unit>(Channel.UNLIMITED)
        whenever(gatt.writeCharacteristic(any())).thenAnswer {
            writes.trySend(Unit)
            true
        }
        val outcome = async {
            broadcaster.broadcastPacketAndAwaitOutcome(packet(32), null, null) { true }
        }

        repeat(4) {
            withTimeout(1_000) { writes.receive() }
            broadcaster.onGattClientWriteComplete(
                address,
                "synthetic-generation",
                BluetoothGatt.GATT_FAILURE
            )
        }
        assertEquals(BoundSendOutcome.UNCONFIRMED, withTimeout(1_000) { outcome.await() })
    }

    @Test fun `missing GATT callback times out invalidates link and releases its whole queue`() = runBlocking<Unit> {
        broadcaster.shutdown()
        broadcaster = BluetoothPacketBroadcaster(
            scope, tracker, FragmentManager(), "1111222233334444", gattCallbackTimeoutMs = 50L
        )
        whenever(gatt.writeCharacteristic(any())).thenReturn(true)

        assertTrue(broadcaster.sendPacketToLink(packet(32), address, "synthetic-generation", null, null))
        assertTrue(broadcaster.sendPacketToLink(packet(32), address, "synthetic-generation", null, null))
        assertEquals(2, broadcaster.pendingSendCountForTesting(address, "synthetic-generation"))

        withTimeout(1_000) {
            while (broadcaster.pendingSendCountForTesting(address, "synthetic-generation") != 0) delay(10)
        }
        // Queue removal happens before cleanup. Wait for the final fallback operation so the
        // assertions below cannot race the timeout worker after pendingSendCount reaches zero.
        verify(gatt, timeout(1_000)).close()
        verify(gatt, times(1)).writeCharacteristic(any())
        verify(tracker).cleanupDeviceConnectionIfCurrent(address, "synthetic-generation")
        verify(gatt).disconnect()

        // A late callback belongs to the invalidated generation and cannot advance anything twice.
        broadcaster.onGattClientWriteComplete(address, "synthetic-generation", BluetoothGatt.GATT_SUCCESS)
        assertEquals(0, broadcaster.pendingSendCountForTesting(address, "synthetic-generation"))
        verify(gatt, times(1)).writeCharacteristic(any())
    }
    @Test fun `fragmented handoff waits for accepted GATT writes and preserves native packet limits`() = runBlocking<Unit> {
        val writes = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        whenever(gatt.writeCharacteristic(any())).thenAnswer {
            writes.add(characteristic.value.copyOf())
            broadcaster.onGattClientWriteComplete(address, "synthetic-generation", BluetoothGatt.GATT_SUCCESS)
            true
        }
        assertTrue(withTimeout(5_000) { broadcaster.broadcastPacketAndAwaitAcceptance(packet(2000), null, null) { true } })
        assertTrue(writes.size > 1)
        assertTrue(writes.all { it.size <= 512 })
    }
    @Test fun `revocation while routing is rechecked before the concrete GATT call`() = runBlocking<Unit> {
        var allowed = true
        val links = tracker.getConnectedDevices()
        whenever(tracker.getConnectedDevices()).thenAnswer { allowed = false; links }
        assertFalse(withTimeout(5_000) { broadcaster.broadcastPacketAndAwaitAcceptance(packet(2000), null, null) { allowed } })
        verify(gatt, never()).writeCharacteristic(any())
    }

    @Test fun `queued head is dropped when its exact link disappears before it starts`() {
        whenever(gatt.writeCharacteristic(any())).thenReturn(true)
        val routed = packet(32)

        assertTrue(broadcaster.sendPacketToLink(routed, address, "synthetic-generation", null, null))
        assertTrue(broadcaster.sendPacketToLink(routed, address, "synthetic-generation", null, null))
        verify(gatt, timeout(1_000)).writeCharacteristic(any())
        whenever(tracker.getLink(address, "synthetic-generation")).thenReturn(null)

        broadcaster.onGattClientWriteComplete(
            address,
            "synthetic-generation",
            BluetoothGatt.GATT_SUCCESS
        )

        verify(gatt, times(1)).writeCharacteristic(any())
    }

    @Test fun `tie break loser cleanup preserves messages queued on exact winner`() {
        whenever(gatt.writeCharacteristic(any())).thenReturn(true)
        val routed = packet(32)
        assertTrue(broadcaster.sendPacketToLink(
            routed, address, "synthetic-generation", null, null
        ))
        assertTrue(broadcaster.sendPacketToLink(
            routed, address, "synthetic-generation", null, null
        ))
        verify(gatt, timeout(1_000)).writeCharacteristic(any())

        broadcaster.onLinkDisconnected(address, "losing-server-generation")
        broadcaster.onGattClientWriteComplete(
            address,
            "synthetic-generation",
            BluetoothGatt.GATT_SUCCESS
        )

        verify(gatt, timeout(1_000).times(2)).writeCharacteristic(any())
    }

    @Test fun `exact server link never falls back to replacement selected at same address`() {
        val device: BluetoothDevice = mock()
        whenever(device.address).thenReturn(address)
        val expected = BluetoothConnectionTracker.DeviceConnection(
            device = device,
            isClient = false,
            peerID = peerID,
            linkID = "expected-server-generation"
        )
        val replacement = expected.copy(linkID = "replacement-server-generation")
        whenever(tracker.getSubscribedDevices()).thenReturn(listOf(device))
        whenever(tracker.getLink(address, expected.linkID)).thenReturn(expected, null)
        whenever(tracker.getDeviceConnection(address)).thenReturn(replacement)
        val server: BluetoothGattServer = mock()

        assertFalse(broadcaster.sendPacketToLink(
            packet(32),
            address,
            expected.linkID,
            server,
            characteristic
        ))
        verify(server, never()).notifyCharacteristicChanged(any(), any(), any())
    }

    @Test fun `a copied broadcast recipient reaches every peer instead of taking a unicast early return`() = runBlocking<Unit> {
        val reserved = tracker.getConnectedDevices().getValue(address)
        val secondAddress = "second-synthetic-link"
        val secondPeerID = "2222333344445555"
        val secondGatt: BluetoothGatt = mock()
        val secondCharacteristic = BluetoothGattCharacteristic(
            java.util.UUID.fromString("00000000-0000-4000-8000-000000000002"),
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val secondDevice: BluetoothDevice = mock()
        whenever(secondDevice.address).thenReturn(secondAddress)
        val second = BluetoothConnectionTracker.DeviceConnection(
            secondDevice,
            secondGatt,
            secondCharacteristic,
            isClient = true,
            peerID = secondPeerID,
            linkID = "second-synthetic-generation"
        )
        whenever(tracker.getConnectedDevices()).thenReturn(linkedMapOf(address to reserved, secondAddress to second))
        whenever(tracker.getDeviceConnection(secondAddress)).thenReturn(second)
        whenever(tracker.getLink(secondAddress, second.linkID)).thenReturn(second)
        tracker.addressPeerMap[address] = SpecialRecipients.BROADCAST.toHexString()
        tracker.addressPeerMap[secondAddress] = secondPeerID
        whenever(gatt.writeCharacteristic(any())).thenAnswer {
            broadcaster.onGattClientWriteComplete(address, "synthetic-generation", BluetoothGatt.GATT_SUCCESS)
            true
        }
        whenever(secondGatt.writeCharacteristic(any())).thenAnswer {
            broadcaster.onGattClientWriteComplete(secondAddress, "second-synthetic-generation", BluetoothGatt.GATT_SUCCESS)
            true
        }

        val copiedBroadcast = SpecialRecipients.BROADCAST.copyOf()
        assertNotSame(SpecialRecipients.BROADCAST, copiedBroadcast)
        assertArrayEquals(SpecialRecipients.BROADCAST, copiedBroadcast)
        val unicastFixture = packet(32)
        val broadcast = unicastFixture.copy(packet = unicastFixture.packet.copy(recipientID = copiedBroadcast))

        assertTrue(withTimeout(5_000) {
            broadcaster.broadcastPacketAndAwaitAcceptance(broadcast, null, null) { true }
        })
        verify(gatt, times(1)).writeCharacteristic(any())
        verify(secondGatt, times(1)).writeCharacteristic(any())
    }
}
