package com.nuvio.tv.core.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeGenerator {

    fun generate(content: String, size: Int = 512, margin: Int = 4): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to margin.coerceAtLeast(0))
        )

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cornerRadius = size * 0.06f
        val bgRect = RectF(0f, 0f, size.toFloat(), size.toFloat())

        // Clip every draw to the rounded card up front: with a thin quiet zone
        // (margin = 1) dark runs can reach into the corner arcs.
        canvas.clipPath(
            Path().apply { addRoundRect(bgRect, cornerRadius, cornerRadius, Path.Direction.CW) }
        )
        canvas.drawColor(Color.WHITE)

        val fgPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }

        // zxing returns the matrix already scaled to the requested size (quiet zone
        // and centring padding included), so one matrix cell is one bitmap pixel:
        // paint one rect per horizontal run of dark pixels per row.
        for (y in 0..<bitMatrix.height) {
            var x = 0
            while (x < bitMatrix.width) {
                if (!bitMatrix[x, y]) {
                    x++
                    continue
                }
                var runEnd = x
                while (runEnd < bitMatrix.width && bitMatrix[runEnd, y]) runEnd++
                canvas.drawRect(x.toFloat(), y.toFloat(), runEnd.toFloat(), (y + 1).toFloat(), fgPaint)
                x = runEnd
            }
        }

        return bitmap
    }
}
