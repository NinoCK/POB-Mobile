package io.room.poe2tree.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import io.room.poe2tree.TreeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.pow

/** Smooth zooming of the tree camera around a point (the view centre by default). */
class ZoomController(private val vm: TreeViewModel, private val scope: CoroutineScope) {
    private var job: Job? = null

    fun zoomBy(factor: Float, focus: Offset? = null) {
        job?.cancel()
        val f = focus ?: vm.viewCentre()
        job = scope.launch {
            var applied = 1f
            animate(1f, factor, animationSpec = tween(ZOOM_ANIMATION_MS, easing = FastOutSlowInEasing)) { value, _ ->
                vm.transform(f.x, f.y, 0f, 0f, value / applied)
                applied = value
            }
        }
    }

    companion object {
        const val STEP = 1.6f
        private const val ZOOM_ANIMATION_MS = 220
    }
}

@Composable
fun rememberZoomController(vm: TreeViewModel): ZoomController {
    val scope = rememberCoroutineScope()
    return remember(vm, scope) { ZoomController(vm, scope) }
}

/**
 * The zoomable, pannable passive tree.
 * Zoom: pinch, mouse wheel / trackpad scroll, double tap on empty space, or [ZoomController] buttons.
 */
@Composable
fun TreeCanvas(vm: TreeViewModel, zoom: ZoomController, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { vm.onViewport(it.width, it.height, density) }
            .pointerInput(Unit) {
                // Mouse wheel (emulator, Chromebooks, tablets with a mouse): zoom around the cursor
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val dy = change.scrollDelta.y
                        if (dy != 0f) {
                            vm.transform(change.position.x, change.position.y, 0f, 0f, WHEEL_STEP.pow(-dy))
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    vm.transform(centroid.x, centroid.y, pan.x, pan.y, gestureZoom)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { pos ->
                    vm.onTap(pos.x, pos.y)?.let { factor -> zoom.zoomBy(factor, pos) }
                })
            }
    ) {
        // Reading these states makes the canvas redraw when they change
        vm.revision
        vm.spriteRevision
        vm.selected
        vm.allocMode
        vm.searchResults
        val state = vm.renderState()
        drawIntoCanvas { canvas ->
            vm.renderer.draw(canvas.nativeCanvas, size.width.toInt(), size.height.toInt(), vm.spec, state)
        }
    }
}

private const val WHEEL_STEP = 1.2f
