package com.example.android_cast.cast

/**
 * Maps internal [CastState] + pairing outcomes to user-facing error copy. The UI
 * surfaces [UserError] verbatim so the user always knows whether to retry, check
 * the network, or re-pair.
 *
 * Issue #11: actionable errors for PIN rejection, session drop, peer loss.
 */
sealed interface UserError {
    val title: String
    val body: String
    val retryable: Boolean

    data class PinRejected(override val title: String = "Pairing rejected",
                           override val body: String =
                               "The PIN didn't match what the Mac showed. Ask the Mac to issue a new PIN and try again.")
        : UserError { override val retryable = true }

    data class SessionDropped(override val title: String = "Cast dropped",
                              override val body: String =
                                  "The Mac stopped receiving. It may have quit or slept. Try connecting again.")
        : UserError { override val retryable = true }

    data class PeerLost(override val title: String = "Receiver unreachable",
                        override val body: String =
                            "Make sure both devices are on the same Wi-Fi, then retry.")
        : UserError { override val retryable = true }

    data class Generic(override val title: String = "Something went wrong",
                       override val body: String,
                       override val retryable: Boolean = true) : UserError
}

object UserErrorMapper {
    fun from(state: CastState, pairingOutcome: PairingClient.Outcome? = null): UserError? {
        pairingOutcome?.let {
            return when (it) {
                PairingClient.Outcome.Rejected -> UserError.PinRejected()
                PairingClient.Outcome.Cancelled -> null  // user-driven, no error surface
                PairingClient.Outcome.AlreadyPaired,
                is PairingClient.Outcome.Paired -> null
            }
        }
        return when (state) {
            is CastState.Failed -> when (state.message.lowercase()) {
                "session dropped", "session_dropped" -> UserError.SessionDropped()
                "peer lost", "peer_lost" -> UserError.PeerLost()
                else -> UserError.Generic(body = state.message)
            }
            else -> null
        }
    }
}
