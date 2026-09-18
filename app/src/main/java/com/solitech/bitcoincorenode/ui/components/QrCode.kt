package com.solitech.bitcoincorenode.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code drawn as vector modules rather than a bitmap.
 *
 * Two reasons, both practical rather than aesthetic:
 *
 * 1. **It stays sharp at any size.** A bitmap generated at one density and
 *    scaled to another produces soft module edges, and soft edges are what make
 *    a scanner sit there failing while the user angles their phone around.
 * 2. **No allocation.** A 512×512 ARGB bitmap is a megabyte held for as long as
 *    the screen is open. Drawing rectangles costs nothing.
 *
 * ## The one non-obvious choice
 *
 * The code is drawn **dark-on-light even though the whole app is dark**, with
 * an explicit light quiet zone. Scanners are built around the assumption of
 * dark modules on a light ground; inverted codes work on some readers and fail
 * on others, and a receive address that some people's phones cannot scan is a
 * bug wearing a design decision's clothes. The aesthetic yields here.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    foreground: Color = Color(0xFF04070A),
    background: Color = Color(0xFFE8F2F8),
) {
    val matrix = remember(content) {
        if (content.isEmpty()) null else runCatching {
            QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                0, 0,   // zero size: let zxing pick the natural module count
                mapOf(
                    // Level M is the standard trade for payment codes: ~15%
                    // recoverable, without inflating the module count so far
                    // that each module becomes too small to resolve on screen.
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 1,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                ),
            )
        }.getOrNull()
    }

    Box(
        modifier
            .aspectRatio(1f)
            .background(background)
            .padding(12.dp),   // quiet zone, on top of zxing's own margin
    ) {
        if (matrix == null) return@Box
        Canvas(Modifier.fillMaxSize()) {
            val cols = matrix.width
            val rows = matrix.height
            val module = minOf(size.width / cols, size.height / rows)
            val offsetX = (size.width - module * cols) / 2f
            val offsetY = (size.height - module * rows) / 2f

            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    if (matrix.get(x, y)) {
                        drawRect(
                            color = foreground,
                            topLeft = Offset(offsetX + x * module, offsetY + y * module),
                            // A hair of overdraw closes the sub-pixel seams that
                            // otherwise appear between modules and confuse some
                            // scanners into reading a light gap.
                            size = Size(module + 0.5f, module + 0.5f),
                        )
                    }
                }
            }
        }
    }
}
