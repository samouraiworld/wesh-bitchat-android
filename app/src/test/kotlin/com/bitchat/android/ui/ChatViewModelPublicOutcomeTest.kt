package com.bitchat.android.ui

import android.app.Application
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.BoundSendOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class ChatViewModelPublicOutcomeTest {
    @Test fun `busy is uncertain even when the new caller is unauthorized and never starts another send`() {
        for (allowed in listOf(false, true)) {
            val mesh = Mockito.mock(BluetoothMeshService::class.java)
            Mockito.`when`<() -> Boolean>(mesh.weshPublicSendGuard(any())).thenReturn({ allowed })
            Mockito.clearInvocations(mesh)
            val viewModel = Mockito.mock(ChatViewModel::class.java, Mockito.CALLS_REAL_METHODS)
            inject(viewModel, "meshService", mesh)
            inject(viewModel, "weshPublicSending", MutableStateFlow(true))
            val outcomes = mutableListOf<BoundSendOutcome>()
            viewModel.sendWeshPublicOutcome("synthetic public", outcomes::add)
            assertEquals(listOf(BoundSendOutcome.UNCONFIRMED), outcomes)
            Mockito.verify(mesh).weshPublicSendGuard(any())
            Mockito.verifyNoMoreInteractions(mesh)
        }
    }

    @Test fun `unauthorized idle attempt is not sent without invoking transport`() {
        val mesh = Mockito.mock(BluetoothMeshService::class.java)
        Mockito.`when`<() -> Boolean>(mesh.weshPublicSendGuard(any())).thenReturn({ false })
        Mockito.clearInvocations(mesh)
        val viewModel = Mockito.mock(ChatViewModel::class.java, Mockito.CALLS_REAL_METHODS)
        inject(viewModel, "meshService", mesh)
        inject(viewModel, "weshPublicSending", MutableStateFlow(false))
        val outcomes = mutableListOf<BoundSendOutcome>()
        viewModel.sendWeshPublicOutcome("synthetic public", outcomes::add)
        assertEquals(listOf(BoundSendOutcome.NOT_SENT), outcomes)
        Mockito.verify(mesh).weshPublicSendGuard(any())
        Mockito.verifyNoMoreInteractions(mesh)
    }

    private fun inject(viewModel: ChatViewModel, field: String, value: Any) {
        ChatViewModel::class.java.getDeclaredField(field).apply { isAccessible = true }.set(viewModel, value)
    }
}
