package com.bitchat.android.mesh

/** What a bound private send over Bluetooth achieved; decides the Internet fallback (spec §3). */
enum class BoundSendOutcome {
    /** Every fragment completed with a successful GATT callback; this is not the application ack. */
    SENT,
    /** Nothing reached the radio and the session still checks out: another route may be used. */
    NOT_SENT,
    /** Some bytes may have left (a later fragment failed, a timeout): wait for the ack (rule 4). */
    UNCONFIRMED,
    /** The session or the contact's key no longer matches: never send this message elsewhere. */
    AUTH_FAILED;

    companion object {
        /**
         * Outcome after fragment [index] got [accepted] (null: timeout or error), with the session
         * re-checked afterwards; null means carry on. A session lost mid-send is not an identity
         * change: before any byte it is [NOT_SENT], after some bytes [UNCONFIRMED]. Only the
         * caller, which knows the enrolled key, decides [AUTH_FAILED].
         */
        fun afterFragment(index: Int, accepted: Boolean?, stillAuthorized: Boolean): BoundSendOutcome? = when {
            accepted == true -> if (stillAuthorized) null else UNCONFIRMED
            index == 0 && accepted == false -> NOT_SENT
            else -> UNCONFIRMED
        }

        /** The session check failed before fragment [index] was handed over. */
        fun beforeFragment(index: Int): BoundSendOutcome = if (index == 0) NOT_SENT else UNCONFIRMED
    }
}
