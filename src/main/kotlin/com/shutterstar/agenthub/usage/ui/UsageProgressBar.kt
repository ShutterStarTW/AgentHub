package com.shutterstar.agenthub.usage.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JPanel

/**
 * Lightweight, theme-aware progress bar for agent usage windows.
 * Uses only stable `java.awt`/`JBColor` primitives so both Light and Darcula themes render cleanly.
 */
internal class UsageProgressBar(
    private val barHeight: Int = 8,
    private val preferredBarWidth: Int = 140,
) : JPanel(null) {
    var percent: Double = 0.0
        set(value) {
            field = value.coerceIn(0.0, 100.0)
            repaint()
        }

    var severity: UsageSeverity = UsageSeverity.NORMAL
        set(value) {
            field = value
            repaint()
        }

    /** Even-pace point (0..100) drawn as a tick; fill past it means usage is ahead of pace. */
    var paceMarker: Double? = null
        set(value) {
            field = value?.coerceIn(0.0, 100.0)
            repaint()
        }

    var onSelectedRow: Boolean = false
        set(value) {
            field = value
            repaint()
        }

    init {
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(preferredBarWidth), JBUI.scale(barHeight))

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(48), JBUI.scale(barHeight))

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, JBUI.scale(barHeight))

    override fun getAccessibleContext(): AccessibleContext {
        if (accessibleContext == null) {
            accessibleContext = object : AccessibleJPanel() {
                override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PROGRESS_BAR
            }
        }
        return accessibleContext
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width.toDouble()
            val h = height.toDouble()
            if (w <= 0.0 || h <= 0.0) return
            val arc = h
            g2.color = if (onSelectedRow) SELECTED_TRACK_COLOR else TRACK_COLOR
            g2.fill(RoundRectangle2D.Double(0.0, 0.0, w, h, arc, arc))

            val fraction = (percent / 100.0).coerceIn(0.0, 1.0)
            if (fraction > 0.0) {
                val fillWidth = (w * fraction).coerceAtLeast(h.coerceAtMost(w))
                g2.color = fillColor(severity, onSelectedRow)
                g2.fill(RoundRectangle2D.Double(0.0, 0.0, fillWidth, h, arc, arc))
            }
            paceMarker?.let { marker ->
                val x = (w * marker / 100.0).coerceIn(1.0, w - 1.0)
                g2.color = MARKER_COLOR
                g2.fill(java.awt.geom.Rectangle2D.Double(x - 1.0, 0.0, 2.0, h))
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private val TRACK_COLOR = JBColor(Color(0xE2, 0xE5, 0xEA), Color(0x43, 0x45, 0x4A))
        private val MARKER_COLOR = JBColor(Color(0x1F, 0x23, 0x28), Color(0xF0, 0xF1, 0xF3))
        private val SELECTED_TRACK_COLOR = JBColor(Color(255, 255, 255, 70), Color(255, 255, 255, 65))
        private val NORMAL_FILL = JBColor(Color(0x2E, 0x8B, 0x57), Color(0x57, 0x96, 0x5C))
        private val WARNING_FILL = JBColor(Color(0xC9, 0x7A, 0x16), Color(0xE0, 0xA8, 0x4E))
        private val CRITICAL_FILL = JBColor(Color(0xC9, 0x3B, 0x3B), Color(0xE0, 0x6C, 0x75))
        private val STALE_FILL = JBColor(Color(0x8C, 0x93, 0x9E), Color(0x7A, 0x7E, 0x85))

        fun fillColor(severity: UsageSeverity, onSelectedRow: Boolean = false): Color = when {
            severity == UsageSeverity.CRITICAL -> CRITICAL_FILL
            severity == UsageSeverity.WARNING -> WARNING_FILL
            severity == UsageSeverity.STALE || severity == UsageSeverity.NEUTRAL -> STALE_FILL
            else -> NORMAL_FILL
        }

        fun badgeForeground(severity: UsageSeverity, defaultSecondary: Color): Color = when (severity) {
            UsageSeverity.CRITICAL -> CRITICAL_FILL
            UsageSeverity.WARNING -> WARNING_FILL
            UsageSeverity.NORMAL -> NORMAL_FILL
            UsageSeverity.STALE, UsageSeverity.NEUTRAL -> defaultSecondary
        }
    }
}
