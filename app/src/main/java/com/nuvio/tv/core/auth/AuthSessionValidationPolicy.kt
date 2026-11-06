package com.nuvio.tv.core.auth

// Single home for auth error classification: the cause-chain walk and the
// invalid-session marker lists used by both the session validator
// (isInvalidRemoteSessionResponse) and the token-refresh path
// (isInvalidAuthSessionMessage).

internal fun isInvalidRemoteSessionResponse(
    statusCode: Int?,
    message: String
): Boolean {
    if (statusCode == 401) return true
    val normalizedMessage = message.lowercase()
    return INVALID_REMOTE_SESSION_MARKERS.any(normalizedMessage::contains)
}

/** Classifies a refresh-token response body or failure cause messages. */
internal fun isInvalidAuthSessionMessage(message: String): Boolean {
    val normalized = message.lowercase()
    return INVALID_AUTH_REFRESH_MARKERS.any(normalized::contains)
}

/** A revoked or unknown server-side session; invalid in both contexts. */
private val INVALID_SESSION_MARKERS = listOf(
    "session not found",
    "session_not_found",
    "invalid session",
    "invalid token"
)

// The context-specific extras stay separate on purpose: on a refresh failure an
// access-token marker such as "jwt expired" is not proof the session is gone, and
// merging the lists would sign the user out on it.
private val INVALID_REMOTE_SESSION_MARKERS = INVALID_SESSION_MARKERS + listOf(
    "invalid jwt",
    "jwt expired",
    "user not found",
    "user does not exist"
)

private val INVALID_AUTH_REFRESH_MARKERS = INVALID_SESSION_MARKERS + listOf(
    "invalid refresh token",
    "refresh token is not valid",
    "refresh token not found",
    "refresh_token_not_found",
    "invalid_grant"
)

internal fun Throwable.isJwtExpiredAuthError(): Boolean =
    causeChain().any { it.markerText()?.contains("jwt expired", ignoreCase = true) == true }

/**
 * This throwable's text for marker matching: its message, plus the server response body an
 * [AuthHttpException] keeps out of its message (the message is logged; the body is not).
 * Never log the result.
 */
internal fun Throwable.markerText(): String? =
    if (this is AuthHttpException) "$message $responseBody" else message

/** This throwable followed by its causes, outermost first. */
internal fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

internal inline fun <reified T : Throwable> Throwable.findCause(): T? =
    causeChain().filterIsInstance<T>().firstOrNull()

internal inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    findCause<T>() != null

/** Every message in the cause chain, space-joined, for marker matching. */
internal fun Throwable.causeMessages(): String =
    causeChain().mapNotNull { it.markerText() }.joinToString(" ")
