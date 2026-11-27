package com.nuvio.tv.ui.screens.settings.trackers

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.AutoResizeText
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeVeilDialog
import com.nuvio.tv.ui.components.marquee.rememberQrCode
import com.nuvio.tv.ui.screens.account.SignInCard
import com.nuvio.tv.ui.screens.account.SignInStatusLine
import com.nuvio.tv.ui.screens.profile.ProfileWordmark
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatMinutesSecondsCountdown
import kotlinx.coroutines.delay

/**
 * A tracker's device-code sign-in (Spec 104 D8, sandbox `spec-104-trackers` v2 stages B1-B4): a full screen in the
 * Spec 31 QR sign-in layout, in its own window over Settings (no NavHost route). The intro on the left; the card on
 * the right with the QR of `verification_uri_complete` (the code is filled in after the scan), the code and the plain
 * address for typing it by hand, the countdown, the status, and Cancel (New code / Try again once the code is dead).
 * Back and Cancel call [onClose], which stops the polling. B5 (approved) closes it from the ViewModel.
 */
@Composable
internal fun TrackerSignInDialog(
    signIn: TrackerSignInUi,
    logoRes: Int?,
    onRestart: () -> Unit,
    onClose: () -> Unit
) {
    val state = signIn.state
    val name = signIn.name
    val code = state.shownCode
    val cancelFocus = remember { FocusRequester() }
    val restartFocus = remember { FocusRequester() }
    val dead = state is TrackerSignIn.Expired || state is TrackerSignIn.Failed
    val tokens = NuvioTheme.marquee.settings
    val safeHorizontal = NuvioTheme.layout.tvSafeHorizontal
    MarqueeVeilDialog(
        onDismissRequest = onClose,
        veil = NuvioTheme.colors.Background,
        initialFocus = if (dead) restartFocus else cancelFocus
    ) {
        ProfileWordmark()
        TrackerSignInIntro(
            name = name,
            logoRes = logoRes,
            address = code?.let { displayAddress(it.verificationUri) },
            modifier = Modifier
                .padding(start = safeHorizontal, top = tokens.signInIntroTop)
                .width(tokens.signInIntroWidth)
        )
        SignInCard(
            eyebrow = stringResource(R.string.trackers_signin_card_eyebrow, name),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = safeHorizontal, top = tokens.signInCardTop, bottom = tokens.signInCardBottom)
                .width(tokens.signInCardWidth)
                .fillMaxHeight()
        ) {
            Text(
                text = stringResource(R.string.trackers_signin_steps, name),
                style = NuvioTheme.textStyles.meta,
                color = NuvioTheme.marquee.onImage2,
                textAlign = TextAlign.Center
            )
            TrackerQrTile(state = state, name = name)
            if (code != null) TrackerCode(code.userCode)
            TrackerCountdown(state)
            trackerStatus(state, name)?.let { (text, error) -> SignInStatusLine(text = text, error = error) }
            Row(
                modifier = Modifier.padding(top = NuvioTheme.spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                if (dead) {
                    MarqueeGlassTextButton(
                        label = stringResource(
                            if (state is TrackerSignIn.Expired) R.string.trackers_signin_new_code else R.string.trackers_signin_try_again
                        ),
                        onClick = onRestart,
                        modifier = Modifier.focusRequester(restartFocus)
                    )
                }
                MarqueeGlassTextButton(
                    label = stringResource(R.string.action_cancel),
                    onClick = onClose,
                    modifier = Modifier.focusRequester(cancelFocus)
                )
            }
            // The line at the card's foot, as the account sign-in's Terms line.
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                Text(
                    text = stringResource(R.string.trackers_signin_terms),
                    style = NuvioTheme.textStyles.profileLine,
                    color = NuvioTheme.marquee.onImage3,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** `.l11ql` with the tracker's eyebrow (`.s104eye`), what connecting does, and the address to type the code at (`.s104alt`). */
@Composable
private fun TrackerSignInIntro(name: String, logoRes: Int?, address: String?, modifier: Modifier) {
    val marquee = NuvioTheme.marquee
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(marquee.settings.signInIntroGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackerLogo(logoRes = logoRes, name = name, size = TrackerLogoSize.EYEBROW)
            Text(
                text = stringResource(R.string.trackers_signin_eyebrow, name).uppercase(),
                style = NuvioTheme.textStyles.eyebrow,
                color = marquee.onImage3
            )
        }
        Text(
            text = stringResource(R.string.trackers_signin_title, name),
            style = NuvioTheme.textStyles.signInTitle,
            color = NuvioPrimitives.white
        )
        Text(
            text = stringResource(R.string.trackers_signin_body, name),
            style = NuvioTheme.textStyles.signInIntro,
            color = marquee.onImage2
        )
        if (address != null) {
            val line = stringResource(R.string.trackers_signin_no_camera, address)
            val at = line.indexOf(address)
            Text(
                text = buildAnnotatedString {
                    append(line)
                    if (at >= 0) {
                        addStyle(SpanStyle(color = NuvioPrimitives.white, fontWeight = FontWeight.SemiBold), at, at + address.length)
                    }
                },
                style = NuvioTheme.textStyles.meta.copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = marquee.onImage3
            )
        }
    }
}

/** The QR (B1/B2), dark under "Code expired" (B3) or "Not connected" (B4); "Getting a code..." before there is one. */
@Composable
private fun TrackerQrTile(state: TrackerSignIn, name: String) {
    val tokens = NuvioTheme.marquee.settings
    val code = state.shownCode
    val qr = code?.let { rememberQrCode(it.verificationUriComplete ?: it.verificationUri) }
    val overlay = when (state) {
        is TrackerSignIn.Expired -> stringResource(R.string.auth_qr_code_expired)
        is TrackerSignIn.Failed -> stringResource(R.string.trackers_signin_not_connected)
        else -> null
    }
    Box(
        modifier = Modifier
            .size(tokens.qrSize)
            .clip(RoundedCornerShape(tokens.signInQrRadius))
            .background(if (qr != null) NuvioPrimitives.white else NuvioTheme.marquee.formRow.fill),
        contentAlignment = Alignment.Center
    ) {
        if (qr != null) {
            Image(
                bitmap = qr,
                contentDescription = stringResource(R.string.cd_trackers_signin_qr, name),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else if (state is TrackerSignIn.Starting) {
            Text(
                text = stringResource(R.string.trackers_signin_getting_code),
                style = NuvioTheme.textStyles.meta,
                color = NuvioTheme.marquee.onImage2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(NuvioTheme.spacing.md)
            )
        }
        if (overlay != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (qr != null) tokens.signInExpiredVeil else NuvioTheme.marquee.formRow.fill),
                contentAlignment = Alignment.Center
            ) {
                Text(text = overlay, style = NuvioTheme.textStyles.profileName, color = NuvioPrimitives.white)
            }
        }
    }
}

/** `.l11code.s104big`: CODE over the user code, large and spaced for typing by hand. */
@Composable
private fun TrackerCode(userCode: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.trackers_signin_code_label).uppercase(),
            style = NuvioTheme.textStyles.eyebrow,
            color = NuvioTheme.marquee.onImage3
        )
        AutoResizeText(
            text = userCode,
            style = NuvioTheme.textStyles.signInCode.copy(
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            ),
            color = NuvioPrimitives.white
        )
    }
}

/** "Expires in 14:32" while waiting (ticking), "Expired" once the code ran out. */
@Composable
private fun TrackerCountdown(state: TrackerSignIn) {
    val text = when (state) {
        is TrackerSignIn.Waiting -> {
            val now by produceState(System.currentTimeMillis(), state.code) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            stringResource(R.string.auth_qr_expires, formatMinutesSecondsCountdown(state.code.expiresAtEpochMs - now))
        }
        is TrackerSignIn.Expired -> stringResource(R.string.auth_qr_expired_short)
        else -> null
    } ?: return
    Text(text = text, style = NuvioTheme.textStyles.settingsNote, color = NuvioTheme.marquee.onImage3)
}

/** The status line's text and whether it is an error. */
@Composable
private fun trackerStatus(state: TrackerSignIn, name: String): Pair<String, Boolean>? = when (state) {
    is TrackerSignIn.Waiting -> stringResource(R.string.trackers_signin_waiting) to false
    is TrackerSignIn.Expired -> stringResource(R.string.trackers_signin_expired) to true
    is TrackerSignIn.Failed -> stringResource(
        if (state.reason == TrackerSignIn.Reason.DENIED) R.string.trackers_signin_denied else R.string.trackers_signin_unreachable,
        name
    ) to true
    is TrackerSignIn.Starting, is TrackerSignIn.Connected -> null
}

/** The address as typed: no scheme, no trailing slash (`https://simkl.com/pin/` -> `simkl.com/pin`). */
internal fun displayAddress(uri: String): String =
    uri.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
