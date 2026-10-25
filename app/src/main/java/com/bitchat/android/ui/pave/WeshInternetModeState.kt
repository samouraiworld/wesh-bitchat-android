package com.bitchat.android.ui.pave

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Connexion sheet shows about Internet mode. Mirrors the Internet mode state machine. */
sealed interface PaveInternetStatus {
    data object Off : PaveInternetStatus
    data class Connecting(val torPercent: Int) : PaveInternetStatus
    data class On(val relays: Int) : PaveInternetStatus
    data class Error(val reason: PaveInternetError) : PaveInternetStatus
}

enum class PaveInternetError { TorBlocked, NoNetwork, RelaysUnreachable }

/** Local recovery guidance selected from the typed state, never from an exception or relay response. */
@androidx.annotation.StringRes
internal fun PaveInternetError.recoveryHelp(): Int = when (this) {
    PaveInternetError.NoNetwork -> com.bitchat.android.R.string.wesh_pave_internet_help_network
    PaveInternetError.TorBlocked -> com.bitchat.android.R.string.wesh_pave_internet_help_tor
    PaveInternetError.RelaysUnreachable -> com.bitchat.android.R.string.wesh_pave_internet_help_relays
}

/**
 * The UI's view of Internet mode. The Connexion sheet depends only on this,
 * so the real opt-in Tor-only implementation can be bound behind it.
 */
interface WeshInternetModeState {
    /** False when this build ships without Internet mode; the switch is then disabled. */
    val available: Boolean
    val enabled: StateFlow<Boolean>
    val status: StateFlow<PaveInternetStatus>

    /** Whether the Internet mode disclosure was accepted; turning Internet on is refused until then. */
    val disclosureAccepted: Boolean
    fun acceptDisclosure()

    /** Returns false when the change was refused (for example during an unfinished erase). */
    fun setEnabled(on: Boolean): Boolean
    fun retry()
}

/** In-memory stand-in for previews and tests. Never touches the network. */
class FakeWeshInternetModeState(
    override val available: Boolean = true,
    enabled: Boolean = false,
    status: PaveInternetStatus = if (enabled) PaveInternetStatus.On(3) else PaveInternetStatus.Off,
    disclosureAccepted: Boolean = false,
) : WeshInternetModeState {
    override var disclosureAccepted: Boolean = disclosureAccepted
        private set

    override fun acceptDisclosure() {
        disclosureAccepted = true
    }

    private val _enabled = MutableStateFlow(enabled)
    private val _status = MutableStateFlow(status)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val status: StateFlow<PaveInternetStatus> = _status.asStateFlow()

    override fun setEnabled(on: Boolean): Boolean {
        if (!available || (on && !disclosureAccepted)) return false
        _enabled.value = on
        _status.value = if (on) PaveInternetStatus.Connecting(0) else PaveInternetStatus.Off
        return true
    }

    override fun retry() {
        if (_enabled.value) _status.value = PaveInternetStatus.Connecting(0)
    }

    /** Lets a test or preview drive the status. */
    fun emit(status: PaveInternetStatus) {
        _status.value = status
    }
}

/** What a touch on the Internet switch did. */
internal enum class InternetToggleOutcome { Changed, NeedsDisclosure, Refused }

/** The switch: the first time on, the disclosure (spec section 4) comes first; a refused change is reported. */
internal fun toggleInternet(internet: WeshInternetModeState, on: Boolean): InternetToggleOutcome = when {
    on && !internet.disclosureAccepted -> InternetToggleOutcome.NeedsDisclosure
    internet.setEnabled(on) -> InternetToggleOutcome.Changed
    else -> InternetToggleOutcome.Refused
}

/** "Activer" under the disclosure: records it, then turns Internet on. */
internal fun confirmInternetDisclosure(internet: WeshInternetModeState): InternetToggleOutcome {
    internet.acceptDisclosure()
    return if (internet.setEnabled(true)) InternetToggleOutcome.Changed else InternetToggleOutcome.Refused
}

/** The Home Internet pill (spec §3 « Pill »): only while Internet mode is on, as on iOS. */
fun paveHomeInternetPill(available: Boolean, enabled: Boolean, status: PaveInternetStatus): PaveInternetStatus? =
    status.takeIf { available && enabled }

/**
 * « Bluetooth seulement », « Connexion à Tor… », « Internet actif », « Internet indisponible — Réessayer ».
 * The Tor step shows no figure: Arti reports no real bootstrap progress.
 */
@androidx.annotation.StringRes
fun PaveInternetStatus.pillLabel(): Int = when (this) {
    PaveInternetStatus.Off -> com.bitchat.android.R.string.wesh_pave_pill_bluetooth_only
    is PaveInternetStatus.Connecting -> com.bitchat.android.R.string.wesh_pave_pill_connecting
    is PaveInternetStatus.On -> com.bitchat.android.R.string.wesh_pave_pill_on
    is PaveInternetStatus.Error -> com.bitchat.android.R.string.wesh_pave_pill_failed
}

/** Only a failure turns the pill into a retry. */
val PaveInternetStatus.offersRetry: Boolean get() = this is PaveInternetStatus.Error

enum class PaveInternetPillAction { Retry, OpenConnexion }

/** A touch on the pill: retry after a failure, otherwise open the Connexion sheet. */
fun PaveInternetStatus.pillAction(): PaveInternetPillAction =
    if (offersRetry) PaveInternetPillAction.Retry else PaveInternetPillAction.OpenConnexion
