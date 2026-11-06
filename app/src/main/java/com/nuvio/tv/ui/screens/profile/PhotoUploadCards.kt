package com.nuvio.tv.ui.screens.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.PendingPhoto
import com.nuvio.tv.data.local.MyPhotoKind
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.screens.addon.WindowTopLeft
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames

/**
 * Add from phone's overlays (sandbox u44 stages 3 and 5), composed while the profile editor is
 * open: the QR card while [controller]'s page runs, and the TV confirm for a photo that has
 * arrived. Each is a focusable Popup (its own window, so the in-app screensaver pauses, as
 * PhoneManageHost); the QR card keeps the screen on. Leaving composition (Back out of the editor,
 * its exit) and ON_STOP (Home) close the session; a confirm made after the 10-minute lifetime closed
 * the card reports its outcome to [onClosedNotice] (the caller's toast). An outcome landing after a
 * close (Back, Home, editor exit) is dropped by the controller.
 */
@Composable
internal fun PhotoUploadHost(controller: PhotoUploadController, onClosedNotice: (PhotoUploadNotice) -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.close() }

    val cardOpen = state.url != null
    LaunchedEffect(state.notice, cardOpen) {
        val notice = state.notice
        if (notice != null && !cardOpen) {
            onClosedNotice(notice)
            controller.noticeShown()
        }
    }

    state.url?.let { url ->
        Popup(
            popupPositionProvider = WindowTopLeft,
            onDismissRequest = controller::close,
            properties = PopupProperties(focusable = true)
        ) {
            QrUploadCard(url = url, notice = state.notice)
        }
    }

    state.pending?.let { photo ->
        Popup(
            popupPositionProvider = WindowTopLeft,
            onDismissRequest = { controller.discard(photo.id) },
            properties = PopupProperties(focusable = true)
        ) {
            ConfirmUploadCard(
                photo = photo,
                onAdd = { controller.confirm(photo.id) },
                onDiscard = { controller.discard(photo.id) }
            )
        }
    }
}

/** Stage 3: the QR, the three steps, the address and the status line ("Waiting for a photo", or the last outcome). */
@Composable
private fun QrUploadCard(url: String, notice: PhotoUploadNotice?) {
    val qr = remember(url) { QrCodeGenerator.generate(url, QR_BITMAP_SIZE, margin = 1).asImageBitmap() }
    UploadDim(modifier = Modifier.keepScreenOn()) {
        Row(
            modifier = Modifier.uploadCard().width(QR_CARD_WIDTH),
            horizontalArrangement = Arrangement.spacedBy(QR_CARD_GAP)
        ) {
            Box(
                modifier = Modifier
                    .size(QR_SIZE)
                    .clip(RoundedCornerShape(QR_RADIUS))
                    .background(NuvioPrimitives.white),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = qr,
                    contentDescription = stringResource(R.string.cd_qr_code),
                    modifier = Modifier.size(QR_SIZE * QR_FILL),
                    contentScale = ContentScale.Fit
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.photo_upload_title),
                    style = NuvioTheme.textStyles.welcomeDevice,
                    color = NuvioPrimitives.white
                )
                CardBody(stringResource(R.string.photo_upload_wifi), modifier = Modifier.padding(top = 6.dp))
                Column(
                    modifier = Modifier.padding(top = SECTION_GAP),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UploadStep(1, stringResource(R.string.photo_upload_step_scan))
                    UploadStep(2, stringResource(R.string.photo_upload_step_crop))
                    UploadStep(3, stringResource(R.string.photo_upload_step_confirm))
                }
                Text(
                    text = url,
                    style = NuvioTheme.textStyles.signInCode,
                    color = NuvioPrimitives.white,
                    modifier = Modifier
                        .padding(top = SECTION_GAP)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NuvioPrimitives.white.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                UploadStatus(notice = notice, modifier = Modifier.padding(top = SECTION_GAP))
            }
        }
    }
}

@Composable
private fun UploadStep(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(NuvioPrimitives.white.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                color = NuvioPrimitives.white,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        CardBody(text)
    }
}

/** `.u44wait`: a dot (green while waiting or after an add, red after a failure) and the line. */
@Composable
private fun UploadStatus(notice: PhotoUploadNotice?, modifier: Modifier = Modifier) {
    val ok = notice == null || notice == PhotoUploadNotice.Added
    val dot = if (ok) NuvioTheme.colors.Success else NuvioTheme.colors.Error
    val text = stringResource(notice?.message ?: R.string.photo_upload_waiting)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(16.dp).background(dot.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = Modifier.size(8.dp).background(dot, CircleShape))
        }
        Text(text = text, style = NuvioTheme.textStyles.caption, color = NuvioTheme.marquee.onImage3)
    }
}

/** Stage 5: the photo as it will show (a circle, or 16:9), Add to My photos (focused) and Discard. */
@Composable
private fun ConfirmUploadCard(photo: PendingPhoto, onAdd: () -> Unit, onDiscard: () -> Unit) {
    val addFocus = remember(photo.id) { FocusRequester() }
    LaunchedEffect(photo.id) { addFocus.requestFocusAfterFrames() }
    val context = LocalContext.current
    UploadDim {
        Column(
            modifier = Modifier.uploadCard().width(CONFIRM_CARD_WIDTH),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val shape = if (photo.kind == MyPhotoKind.Avatar) CircleShape else RoundedCornerShape(10.dp)
            AsyncImage(
                model = remember(photo.id) { ImageRequest.Builder(context).data(photo.jpeg).build() },
                contentDescription = stringResource(R.string.profile_my_photo),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .then(
                        if (photo.kind == MyPhotoKind.Avatar) {
                            Modifier.size(CONFIRM_AVATAR_SIZE)
                        } else {
                            Modifier.size(CONFIRM_BACKGROUND_WIDTH, CONFIRM_BACKGROUND_WIDTH * 9 / 16)
                        }
                    )
                    .clip(shape)
                    .background(NuvioTheme.colors.BackgroundCard)
            )
            Text(
                text = stringResource(R.string.photo_upload_confirm_title),
                style = NuvioTheme.textStyles.welcomeDevice,
                color = NuvioPrimitives.white,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
            CardBody(
                stringResource(R.string.photo_upload_confirm_body),
                modifier = Modifier.padding(top = 6.dp),
                textAlign = TextAlign.Center
            )
            Row(
                modifier = Modifier.padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MarqueePrimaryButton(
                    label = stringResource(R.string.photo_upload_confirm_add),
                    icon = null,
                    onClick = onAdd,
                    focusRequester = addFocus,
                    compact = true
                )
                MarqueeGlassTextButton(
                    label = stringResource(R.string.photo_upload_confirm_discard),
                    onClick = onDiscard,
                    height = NuvioTheme.marquee.primaryButton.compactHeight,
                    horizontalPadding = 20.dp,
                    textStyle = NuvioTheme.textStyles.panelButtonSmall
                )
            }
        }
    }
}

/** `.u44dim`: the editor dimmed behind a centred card. */
@Composable
private fun UploadDim(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.fillMaxSize().background(NuvioPrimitives.black.copy(alpha = DIM_ALPHA)),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** `.u44card`: near-black glass, 22 radius, a faint white edge. */
private fun Modifier.uploadCard(): Modifier =
    clip(RoundedCornerShape(CARD_RADIUS))
        .background(CARD_FILL)
        .border(1.dp, NuvioPrimitives.white.copy(alpha = 0.1f), RoundedCornerShape(CARD_RADIUS))
        .padding(horizontal = 28.dp, vertical = 26.dp)

@Composable
private fun CardBody(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.panelBody.copy(fontWeight = FontWeight.Medium),
        color = NuvioTheme.marquee.onImage2,
        textAlign = textAlign,
        modifier = modifier
    )
}

// Sandbox u44 stages 3 and 5 (`.u44dim`, `.u44card`, `.u44qr`).
private const val DIM_ALPHA = 0.62f
private val CARD_FILL = Color(0xF0101012)
private val CARD_RADIUS = 22.dp
private val SECTION_GAP = 14.dp
private val QR_CARD_WIDTH = 640.dp
private val QR_CARD_GAP = 26.dp
private val QR_SIZE = 176.dp
private val QR_RADIUS = 14.dp
private const val QR_FILL = 0.88f
private const val QR_BITMAP_SIZE = 512
private val CONFIRM_CARD_WIDTH = 400.dp
private val CONFIRM_AVATAR_SIZE = 132.dp
private val CONFIRM_BACKGROUND_WIDTH = 288.dp
