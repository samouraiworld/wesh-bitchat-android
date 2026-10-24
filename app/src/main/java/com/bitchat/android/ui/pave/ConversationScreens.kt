package com.bitchat.android.ui.pave

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import java.util.Locale
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/** A message row as the conversation screens draw it. Remote names are sanitized by [MessageBubble]. */
@Immutable
data class PaveMessageItem(
    val id: String,
    val text: String,
    val own: Boolean,
    val time: String?,
    /** Received public messages only: raw nickname and [PaveFingerprint.short] (null = key unknown, shown as such). */
    val sender: String?,
    val senderFingerprint: String?,
    val delivery: PaveDelivery? = null,
    val transport: PaveTransport? = null,
    val canRetry: Boolean = false,
)

/** The shared composer state of a conversation. [feedback] is announced politely when it changes. */
@Immutable
data class PaveComposerState(
    val draft: String,
    val canSend: Boolean,
    val editable: Boolean = true,
    val blockedReason: String? = null,
    val feedback: String? = null,
    /** Set while drafts cannot be saved or restored: offers to try again. */
    val onRetryPersistence: (() -> Unit)? = null,
)

/**
 * Salon local (Pavé Fluo): a full-width yellow header that says PUBLIC before anything else,
 * messages with the sender's nickname and fingerprint, and the public composer with its yellow rule.
 */
@Composable
fun PublicRoomScreen(
    phonesInReach: Int,
    messages: List<PaveMessageItem>,
    composer: PaveComposerState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onMessageActions: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = WeshPave.colors
    val title = stringResource(R.string.wesh_pave_public_room)
    Column(modifier.fillMaxSize().background(c.bg).paveGrain(c.isNight).imePadding()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.publicColor)
                .statusBarsPadding()
                .padding(start = WeshSpace.s1, end = WeshSpace.s4, top = WeshSpace.s2, bottom = WeshSpace.s3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack, tint = c.onPublic)
                Text(
                    title.uppercase(Locale.ROOT),
                    style = WeshType.display,
                    color = c.onPublic,
                    modifier = Modifier.weight(1f).semantics { heading(); contentDescription = title },
                )
                Icon(Icons.Rounded.Campaign, contentDescription = null, tint = c.onPublic, modifier = Modifier.size(28.dp))
            }
            Text(
                stringResource(R.string.wesh_pave_room_public_subtitle, phonesInReach.coerceAtLeast(0)),
                style = WeshType.label,
                color = c.onPublic,
                modifier = Modifier.padding(start = WeshSpace.s3),
            )
        }
        MessageList(
            messages = messages,
            room = PaveRoom.Public,
            empty = stringResource(R.string.wesh_pave_room_public_empty),
            onMessageActions = onMessageActions,
            onRetry = {},
            modifier = Modifier.weight(1f),
        )
        ComposerFooter(composer, PaveRoom.Public, null, onDraftChange, onSend)
    }
}

/**
 * Discussion privée (Pavé Fluo): the contact's nickname as typed (system face, never the poster
 * face, so every script and every look-alike letter stays visible), the verified badge only for a
 * QR-verified contact, and the full short fingerprint under it.
 */
@Composable
fun PrivateChatScreen(
    nickname: String,
    fingerprint: String?,
    verified: Boolean,
    messages: List<PaveMessageItem>,
    composer: PaveComposerState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (String) -> Unit,
    onBack: () -> Unit,
    onOpenContact: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val c = WeshPave.colors
    val name = remoteDisplayName(nickname)
    Column(modifier.fillMaxSize().background(c.bg).paveGrain(c.isNight).imePadding()) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = WeshSpace.s1, end = WeshSpace.s4, top = WeshSpace.s2, bottom = WeshSpace.s3),
            verticalArrangement = Arrangement.spacedBy(WeshSpace.s1),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack, tint = c.ink)
                Text(
                    name,
                    style = WeshType.title,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = WeshTouch.min)
                        .paveClickable(RoundedCornerShape(WeshRadius.control), stringResource(R.string.wesh_pave_private_open_contact), onClick = onOpenContact)
                        .semantics { heading() }
                        // The touch target is taller than the name: center the text in it, in line with Back.
                        .wrapContentHeight(Alignment.CenterVertically),
                )
                Spacer(Modifier.width(WeshSpace.s2))
                if (verified) VerifiedChip() else UnverifiedChip()
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WeshSpace.s2),
                modifier = Modifier.padding(start = WeshSpace.s3),
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.wesh_pave_private_label).uppercase(Locale.ROOT), style = WeshType.caption, color = c.ink)
                if (fingerprint != null) {
                    val spoken = stringResource(R.string.wesh_pave_fingerprint_label) + " : " + PaveFingerprint.spoken(fingerprint)
                    Text(fingerprint, style = WeshType.fingerprint, color = c.inkMuted, maxLines = 1, modifier = Modifier.semantics { contentDescription = spoken })
                } else {
                    Text(stringResource(R.string.wesh_pave_fingerprint_unavailable), style = WeshType.caption, color = c.warning)
                }
            }
        }
        HorizontalDivider(thickness = WeshStroke.s2, color = c.line)
        MessageList(
            messages = messages,
            room = PaveRoom.Private,
            empty = stringResource(R.string.wesh_pave_room_private_empty),
            onMessageActions = {},
            onRetry = onRetry,
            modifier = Modifier.weight(1f),
            loading = loading,
        )
        ComposerFooter(composer, PaveRoom.Private, nickname, onDraftChange, onSend)
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, tint: Color) {
    val label = stringResource(R.string.wesh_pave_back)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(WeshTouch.min).paveClickable(CircleShape, label, onClick = onBack),
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun VerifiedChip() {
    val c = WeshPave.colors
    val shape = RoundedCornerShape(WeshRadius.tail + 2.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WeshSpace.s1),
        modifier = Modifier
            .border(WeshStroke.s2, c.success, shape)
            .padding(horizontal = WeshSpace.s2, vertical = WeshSpace.s1),
    ) {
        Icon(Icons.Rounded.Verified, contentDescription = null, tint = c.success, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.wesh_pave_verified), style = WeshType.caption, color = c.success)
    }
}

@Composable
private fun UnverifiedChip() {
    val c = WeshPave.colors
    Text(stringResource(R.string.wesh_pave_unverified), style = WeshType.caption, color = c.warning)
}

/** A spinner that appears only once loading outlasts 200 ms, so a fast open never flashes it. */
@Composable
private fun DelayedSpinner() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(200); visible = true }
    Box(Modifier.fillMaxWidth().heightIn(min = WeshTouch.min), contentAlignment = Alignment.Center) {
        if (visible) CircularProgressIndicator(color = WeshPave.colors.inkMuted, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun MessageList(
    messages: List<PaveMessageItem>,
    room: PaveRoom,
    empty: String,
    onMessageActions: (String) -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier,
    loading: Boolean = false,
) {
    val c = WeshPave.colors
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = WeshSpace.gutter, vertical = WeshSpace.s3),
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s2, Alignment.Bottom),
    ) {
        if (messages.isEmpty() && loading) item(key = "loading") { DelayedSpinner() }
        else if (messages.isEmpty()) item(key = "empty") {
            Text(empty, style = WeshType.body, color = c.inkMuted, modifier = Modifier.fillMaxWidth())
        }
        items(messages, key = { it.id }) { m ->
            MessageBubble(
                text = m.text,
                own = m.own,
                room = room,
                time = m.time,
                sender = if (m.own || room != PaveRoom.Public) null else m.sender,
                senderFingerprint = if (m.own || room != PaveRoom.Public) null else m.senderFingerprint,
                delivery = m.delivery,
                transport = m.transport,
                onRetry = if (m.canRetry) ({ onRetry(m.id) }) else null,
                onOpenDetail = if (!m.own && room == PaveRoom.Public) ({ onMessageActions(m.id) }) else null,
            )
        }
    }
}

@Composable
internal fun ComposerFooter(
    state: PaveComposerState,
    room: PaveRoom,
    recipient: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val c = WeshPave.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bg)
            .navigationBarsPadding()
            .padding(horizontal = WeshSpace.s3, vertical = WeshSpace.s2),
        verticalArrangement = Arrangement.spacedBy(WeshSpace.s2),
    ) {
        state.feedback?.let {
            Text(it, style = WeshType.caption, color = c.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        state.blockedReason?.let { Text(it, style = WeshType.caption, color = c.warning) }
        state.onRetryPersistence?.let { retry ->
            SecondaryButton(stringResource(R.string.wesh_pave_fluo_draft_retry), retry, Modifier.fillMaxWidth())
        }
        Composer(
            value = state.draft,
            onValueChange = { if (state.editable) onDraftChange(it) },
            onSend = onSend,
            room = room,
            enabled = state.canSend,
            recipient = recipient,
        )
    }
}

@PaveScreenPreviews
@Composable
private fun PublicRoomScreenPreview() = PavePreview(padded = false) {
    PublicRoomScreen(
        phonesInReach = 7,
        messages = listOf(
            PaveMessageItem("1", "Point d’eau devant la mairie, il en reste.", false, "18:01", "Inès", "9F0C 22AB 71D3 E4C5", transport = PaveTransport.Bluetooth),
            PaveMessageItem("2", "Ça bouge vers République, restez groupés.", false, "18:04", "Mehdi", "0E7A 4C19 B2D8 66F1"),
            PaveMessageItem("3", "On est une dizaine au kiosque, on vous attend.", true, "18:05", null, null, delivery = PaveDelivery.Sent),
        ),
        composer = PaveComposerState(draft = "", canSend = true),
        onDraftChange = {}, onSend = {}, onBack = {}, onMessageActions = {},
    )
}

@PaveScreenPreviews
@Composable
private fun PrivateChatScreenPreview() = PavePreview(padded = false) {
    PrivateChatScreen(
        nickname = "Nora",
        fingerprint = "A1B2 C3D4 E5F6 0718",
        verified = true,
        messages = listOf(
            PaveMessageItem("1", "T’es où ? Je vois plus personne du groupe.", false, "18:02", null, null, transport = PaveTransport.Bluetooth),
            PaveMessageItem("2", "Au kiosque, côté métro.", true, "18:03", null, null, delivery = PaveDelivery.Delivered, transport = PaveTransport.Bluetooth),
            PaveMessageItem("3", "Bouge pas, j’arrive.", true, "18:07", null, null, delivery = PaveDelivery.Sent, transport = PaveTransport.Internet),
            PaveMessageItem("4", "Tu vois le kiosque vert ?", true, "18:08", null, null, delivery = PaveDelivery.Waiting),
        ),
        composer = PaveComposerState(draft = "", canSend = true),
        onDraftChange = {}, onSend = {}, onRetry = {}, onBack = {}, onOpenContact = {},
    )
}
