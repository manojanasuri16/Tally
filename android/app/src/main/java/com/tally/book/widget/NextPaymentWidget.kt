package com.tally.book.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.LocalSize
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tally.book.MainActivity
import com.tally.book.R
import com.tally.book.core.*
import com.tally.book.data.DueNotices

/*
 * The "Next payment" home-screen widget (docs/DESIGN.md "Widget"). What it says is chosen by WidgetText from the snapshot (app open,
 * no lock, amounts shown) or, otherwise, from the cached due dates alone. Tapping it opens the app (through the lock, as usual).
 *
 * NEVER rename NextPaymentWidgetReceiver (or move it out of this package): placed widgets point at it by class name.
 *
 * Look: a rounded white (dark: #1E1E21) card with the app's label colours and system red for "overdue". Glance cannot read the
 * app theme, so light and dark follow the PHONE (the in-app Appearance setting does not reach the widget).
 */
class NextPaymentWidget : GlanceAppWidget() {
    companion object {
        val SMALL = DpSize(110.dp, 40.dp)
        val LARGE = DpSize(200.dp, 100.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        DevicePrefs.init(context)
        I18n.init(context, DevicePrefs.get("tally-lang") ?: "en")
        // read now, at draw time: the snapshot only exists while the app may keep one; the dates are all that is left otherwise
        val lines = WidgetText.choose(NextPaymentData.snap(), DueNotices.takenDates(), Clock.today())
        provideContent { Content(context, lines) }
    }

    @Composable
    private fun Content(ctx: Context, l: WidgetText.Lines) {
        val open = actionStartActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        val small = LocalSize.current.height < 90.dp
        GlanceTheme {
            Column(
                GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_bg)).padding(horizontal = 14.dp, vertical = if (small) 6.dp else 12.dp).clickable(open),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (small) {
                    // one line, then the overdue line (or the title when nothing is overdue)
                    Text(listOfNotNull(l.main, l.detail).joinToString(" · "), style = TextStyle(color = Ink.label, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Text(l.overdue ?: l.caption, style = TextStyle(color = if (l.overdue != null) Ink.red else Ink.secondary, fontSize = 12.sp, fontWeight = if (l.overdue != null) FontWeight.Medium else FontWeight.Normal), maxLines = 1)
                } else {
                    Text(l.caption, style = TextStyle(color = Ink.secondary, fontSize = 13.sp), maxLines = 1)
                    Spacer(GlanceModifier.height(2.dp))
                    Text(l.main, style = TextStyle(color = Ink.label, fontSize = 20.sp, fontWeight = FontWeight.Bold), maxLines = 2)
                    if (l.detail != null) Text(l.detail, style = TextStyle(color = Ink.label, fontSize = 16.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    if (l.overdue != null) {
                        Spacer(GlanceModifier.height(6.dp))
                        Text(l.overdue, style = TextStyle(color = Ink.red, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The app's label colours, light then dark (the phone's mode picks one; see the class note). */
private object Ink {
    val label = ColorProvider(Color(0xFF1D1D1F), Color(0xFFF5F5F7))
    val secondary = ColorProvider(Color(0xFF6E6E73), Color(0xFFA1A1A6))
    val red = ColorProvider(Color(0xFFD70015), Color(0xFFFF6961))
}

/**
 * NEVER rename or move this class: it is named in the manifest and in every placed widget. It also tells the app when the first
 * widget is placed and the last one removed, which switches the snapshot, the due-date copy and the daily redraw on and off.
 */
class NextPaymentWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextPaymentWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        DevicePrefs.init(context)
        NextPaymentData.placedChanged(context, true)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        DevicePrefs.init(context)
        NextPaymentData.placedChanged(context, false)
    }
}
