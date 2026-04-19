package com.example.wearableai.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wearableai.shared.Pin
import com.example.wearableai.shared.PinSeverity
import java.io.File

/**
 * Pan + zoom + tap-to-add-pin floor plan viewer.
 *
 * [floorPlanPath]  — absolute path to a PNG/JPEG on disk. Null clears the view.
 * [pins]           — pins in normalized (0..1) coords.
 * [onAddPinAtNorm] — user-tapped add; called with normalized x,y.
 * [onPinTap]       — existing pin tapped.
 */
@Composable
fun FloorPlanScreen(
    floorPlanPath: String?,
    pins: List<Pin>,
    onAddPinAtNorm: (Float, Float) -> Unit,
    onPinTap: (Pin) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedPinLabel by remember { mutableStateOf<String?>(null) }
    val fg = Color(0xFFE6E6E6)

    val bitmap = remember(floorPlanPath) {
        floorPlanPath?.let {
            try { BitmapFactory.decodeFile(it) } catch (_: Throwable) { null }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Floor plan${if (pins.isNotEmpty()) " (${pins.size} pins)" else ""}",
                color = fg,
                modifier = Modifier.padding(8.dp),
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = { scale = 1f; offset = Offset.Zero }) { Text("Reset view") }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp)
                .weight(1f)
                .background(Color(0x11000000))
                .onSizeChanged { viewSize = it }
                .pointerInput(floorPlanPath) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 8f)
                        offset += pan
                    }
                }
                .pointerInput(floorPlanPath, pins) {
                    detectTapGestures(
                        onTap = { tap ->
                            if (viewSize.width == 0 || viewSize.height == 0) return@detectTapGestures
                            // Invert the graphicsLayer transform: tap is in view-space.
                            val cx = viewSize.width / 2f
                            val cy = viewSize.height / 2f
                            val localX = (tap.x - cx - offset.x) / scale + cx
                            val localY = (tap.y - cy - offset.y) / scale + cy
                            val normX = (localX / viewSize.width).coerceIn(0f, 1f)
                            val normY = (localY / viewSize.height).coerceIn(0f, 1f)
                            // Hit-test existing pins (12 dp tolerance in view space).
                            val hitPin = pins.firstOrNull { pin ->
                                val pxView = pin.x * viewSize.width
                                val pyView = pin.y * viewSize.height
                                val dx = pxView - localX
                                val dy = pyView - localY
                                (dx * dx + dy * dy) <= (24f * 24f)
                            }
                            if (hitPin != null) {
                                selectedPinLabel = "${hitPin.label} (${hitPin.severity.key})"
                                onPinTap(hitPin)
                            } else if (bitmap != null) {
                                onAddPinAtNorm(normX, normY)
                            }
                        },
                    )
                },
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Floor plan",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            } else {
                Text(
                    text = "No floor plan loaded.\nTap \"Floor Plan\" to pick one.",
                    color = fg,
                    modifier = Modifier.padding(24.dp),
                    fontSize = 14.sp,
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            ) {
                for (pin in pins) {
                    val color = when (pin.severity) {
                        PinSeverity.INFO -> Color(0xFF2962FF)
                        PinSeverity.WARN -> Color(0xFFFFA000)
                        PinSeverity.HAZARD -> Color(0xFFD32F2F)
                    }
                    val cx = pin.x * size.width
                    val cy = pin.y * size.height
                    drawCircle(color = color, radius = 14f, center = Offset(cx, cy))
                    drawCircle(color = Color.White, radius = 14f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                }
            }
        }
        if (selectedPinLabel != null) {
            Text(
                text = "Selected: $selectedPinLabel",
                color = fg,
                modifier = Modifier.padding(8.dp),
                fontSize = 13.sp,
            )
        }
    }
}
