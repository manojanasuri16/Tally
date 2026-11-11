package com.tally.book.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tally.book.core.t
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.LocalSemantic
import com.tally.book.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * A row that swipes left to reveal a red Delete, as in iOS lists. It follows the finger 1:1, stretches with resistance
 * past the action's width, settles on a spring with the release velocity (a little bounce only after a flick), and a long
 * pull deletes. [onDelete] returns true when the row is really gone (it may ask first); the row then snaps back either way.
 * [content] gets whether the action is showing and a function that closes it. Place it inside an InsetSurface.
 */
@Composable
fun SwipeRow(enabled: Boolean, onDelete: suspend () -> Boolean, content: @Composable (open: Boolean, close: () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val neg = LocalSemantic.current.negative
    val calm = reduceMotion()
    val reveal = with(LocalDensity.current) { 88.dp.toPx() }
    val offset = remember { Animatable(0f) }
    var crossed by remember { mutableStateOf(false) }

    fun settle(to: Float, velocity: Float = 0f) {
        scope.launch {
            if (calm) offset.snapTo(to) else if (velocity != 0f) offset.animateTo(to, Motion.bouncy(), velocity) else offset.animateTo(to, Motion.push())
        }
    }
    fun delete() {
        scope.launch {
            if (onDelete()) haptics.destructive()
            offset.snapTo(0f)
        }
    }
    val drag = rememberDraggableState { dx ->
        scope.launch {
            val raw = offset.value + dx
            offset.snapTo(if (raw > 0f) 0f else if (raw >= -reveal || dx > 0f) raw else offset.value + dx * 0.35f)
            val past = offset.value < -reveal * 1.6f
            if (past != crossed) { crossed = past; if (past) haptics.tick() }
        }
    }
    val open = offset.value < -1f
    Box(Modifier.fillMaxWidth()) {
        if (open) Box(Modifier.matchParentSize().background(neg).pressable(scaleTo = 1f, highlight = false) { delete() }, contentAlignment = Alignment.CenterEnd) {
            Column(Modifier.width(88.dp).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(22.dp), tint = Color.White)
                Text(t("dlg.delete", "Delete"), style = Apple.type.caption1.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 2, textAlign = TextAlign.Center)
            }
        }
        Box(
            Modifier.fillMaxWidth().graphicsLayer { translationX = offset.value }.background(Apple.colors.cell)
                .then(if (!enabled) Modifier else Modifier.draggable(drag, Orientation.Horizontal, onDragStopped = { v ->
                    crossed = false
                    when {
                        offset.value < -reveal * 1.6f -> delete()
                        offset.value < -reveal / 2 || v < -900f -> settle(-reveal, v)
                        else -> settle(0f, v)
                    }
                })),
        ) { content(open) { settle(0f) } }
    }
}
