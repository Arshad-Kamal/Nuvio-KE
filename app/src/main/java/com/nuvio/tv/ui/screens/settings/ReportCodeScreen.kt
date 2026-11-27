package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeVeilDialog
import com.nuvio.tv.ui.components.marquee.rememberQrCode
import com.nuvio.tv.ui.screens.profile.ProfileWordmark
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import java.net.URLEncoder

/** `.l11ql.s116l`: the left column sits higher and tighter than the tip card's (`.s81l`). */
private val INTRO_TOP = 96.dp
private val INTRO_GAP = 12.dp

/**
 * D7: the new-issue link with [code] in the template's Report code field (GitHub fills an issue
 * form's field from the URL by its id, E6). The code already matched `^KE-[0-9A-HJKMNP-TV-Z]{6}$`
 * (ReportUploader); it is URL-encoded all the same.
 */
internal fun reportIssueLink(code: String): String =
    "https://github.com/${BuildConfig.UPDATE_OWNER}/${BuildConfig.UPDATE_REPO}/issues/new" +
        "?template=bug_report.yml&report_code=" + URLEncoder.encode(code, "UTF-8")

/**
 * Spec 116 D1 (sandbox spec-116-report-sharing v6, stage d-code): the tip card's layout (Spec 81)
 * full screen in its own window. Left: REPORT SENT, the code large, what to do with it and who
 * reads it; right: the QR of [reportIssueLink], the address for typing by hand, Done (focused) and
 * the terms. Done and Back call [onDone].
 */
@Composable
internal fun ReportCodeScreen(code: String, onDone: () -> Unit) {
    val marquee = NuvioTheme.marquee
    val tip = marquee.tip
    val settings = marquee.settings
    val done = remember { FocusRequester() }
    MarqueeVeilDialog(onDismissRequest = onDone, veil = tip.veil, initialFocus = done) {
        ProfileWordmark()
        Column(
            modifier = Modifier
                .padding(start = NuvioTheme.layout.tvSafeHorizontal, top = INTRO_TOP)
                .width(tip.introWidth),
            verticalArrangement = Arrangement.spacedBy(INTRO_GAP)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = marquee.readyText,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.report_sent_eyebrow).uppercase(),
                    style = NuvioTheme.textStyles.eyebrow,
                    color = marquee.readyText
                )
            }
            Text(
                text = stringResource(R.string.report_code_label),
                style = NuvioTheme.textStyles.signInIntro,
                color = marquee.onImage2,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = code,
                style = NuvioTheme.textStyles.signInTitle.copy(
                    fontSize = 60.sp,
                    lineHeight = 68.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontFeatureSettings = "tnum"
                ),
                color = NuvioPrimitives.white,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
            )
            Text(
                text = stringResource(R.string.report_code_body),
                style = NuvioTheme.textStyles.signInIntro,
                color = marquee.onImage2
            )
            Text(
                text = stringResource(R.string.report_code_private),
                style = NuvioTheme.textStyles.signInIntro.copy(fontSize = 13.sp),
                color = marquee.onImage3
            )
        }
        val shape = RoundedCornerShape(settings.signInCardRadius)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = NuvioTheme.layout.tvSafeHorizontal, top = tip.cardTop, bottom = tip.cardBottom)
                .width(settings.signInCardWidth)
                .fillMaxHeight()
                .clip(shape)
                .background(settings.signInCardFill, shape)
                .border(NuvioTheme.spacing.hairline, settings.signInCardEdge, shape)
                .padding(
                    start = settings.signInCardPaddingHorizontal,
                    end = settings.signInCardPaddingHorizontal,
                    top = settings.signInCardPaddingTop,
                    bottom = settings.signInCardPaddingBottom
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tip.cardGap)
        ) {
            PanelEyebrowText(text = stringResource(R.string.report_scan_eyebrow))
            Text(
                text = stringResource(R.string.report_scan_line),
                style = NuvioTheme.textStyles.meta,
                color = marquee.onImage2,
                textAlign = TextAlign.Center
            )
            ReportQrTile(reportIssueLink(code))
            Text(
                text = "${updateFeedAddress()}/issues",
                style = NuvioTheme.textStyles.caption,
                color = marquee.onImage2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            MarqueeGlassTextButton(
                label = stringResource(R.string.tip_done),
                onClick = onDone,
                modifier = Modifier.padding(top = 6.dp).focusRequester(done)
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                Text(
                    text = stringResource(R.string.report_code_terms),
                    style = NuvioTheme.textStyles.profileLine,
                    color = marquee.onImage3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = tip.termsWidth)
                )
            }
        }
    }
}

/** The tip card's white tile (`.s116qr`) with the QR of [link]; empty while it encodes. */
@Composable
private fun ReportQrTile(link: String) {
    val tip = NuvioTheme.marquee.tip
    val tile = RoundedCornerShape(tip.qrRadius)
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .size(tip.qrTile)
            .clip(tile)
            .background(NuvioPrimitives.white, tile)
            .padding(tip.qrInset),
        contentAlignment = Alignment.Center
    ) {
        rememberQrCode(link)?.let { qr ->
            Image(
                bitmap = qr,
                contentDescription = stringResource(R.string.cd_report_qr),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}
