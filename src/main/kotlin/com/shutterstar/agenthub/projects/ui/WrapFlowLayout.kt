package com.shutterstar.agenthub.projects.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/**
 * A [FlowLayout] whose preferred height grows when the components wrap onto further rows, so a control row
 * that is too narrow for all its components pushes the content below it down instead of hiding the overflow.
 * Use it for a panel in a `BorderLayout.NORTH`/`SOUTH` slot.
 */
internal class WrapFlowLayout(align: Int, hgap: Int, vgap: Int) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: Container): Dimension = wrappedSize(target)

    override fun minimumLayoutSize(target: Container): Dimension = wrappedSize(target).also { it.width = 0 }

    private fun wrappedSize(target: Container): Dimension = synchronized(target.treeLock) {
        var available = target.size.width
        if (available <= 0) {
            var ancestor: Container? = target.parent
            while (ancestor != null && ancestor.size.width <= 0) ancestor = ancestor.parent
            available = ancestor?.size?.width ?: 0
        }
        if (available <= 0) available = Int.MAX_VALUE
        val insets = target.insets
        val horizontal = insets.left + insets.right + hgap * 2
        val maxWidth = available - horizontal

        val result = Dimension(0, 0)
        var rowWidth = 0
        var rowHeight = 0
        for (component in target.components) {
            if (!component.isVisible) continue
            val size = component.preferredSize
            if (rowWidth > 0 && rowWidth + hgap + size.width > maxWidth) {
                result.width = maxOf(result.width, rowWidth)
                result.height += rowHeight + vgap
                rowWidth = 0
                rowHeight = 0
            }
            if (rowWidth > 0) rowWidth += hgap
            rowWidth += size.width
            rowHeight = maxOf(rowHeight, size.height)
        }
        result.width = maxOf(result.width, rowWidth)
        result.height += rowHeight
        result.width += horizontal
        result.height += insets.top + insets.bottom + vgap * 2
        result
    }
}
