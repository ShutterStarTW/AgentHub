package com.shutterstar.agenthub.projects.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel

class TitleFirstLayoutTest {
    /** Components are added first and the layout switched on afterwards, as the renderers do. */
    private fun row(containerWidth: Int, titleWidth: Int, trailingWidth: Int?, titleFirst: Boolean = true): Pair<JPanel, JPanel?> {
        val layout = TitleFirstLayout()
        val container = JPanel(layout)
        container.setSize(containerWidth, 20)
        container.add(JPanel().apply { preferredSize = Dimension(titleWidth, 16) }, BorderLayout.CENTER)
        val trailing = trailingWidth?.let { JPanel().apply { preferredSize = Dimension(it, 16) } }
        trailing?.let { container.add(it, BorderLayout.EAST) }
        layout.hgap = 4
        layout.titleFirst = titleFirst
        container.doLayout()
        return container to trailing
    }

    @Test
    fun `switched off it lays out like a plain border layout`() {
        val (container, trailing) = row(200, 120, 50, titleFirst = false)

        assertEquals(146, container.getComponent(0).width)
        assertEquals(50, trailing!!.width)
    }

    @Test
    fun `the title keeps its full width and the trailing component gets the rest, right-aligned`() {
        val (container, trailing) = row(200, 120, 150)

        assertEquals(120, container.getComponent(0).width)
        assertEquals(76, trailing!!.width)
        assertEquals(124, trailing.x)
    }

    @Test
    fun `a trailing component that fits keeps its own width at the right edge`() {
        val (_, trailing) = row(200, 100, 40)

        assertEquals(40, trailing!!.width)
        assertEquals(160, trailing.x)
    }

    @Test
    fun `a title wider than the row leaves no room for the trailing component`() {
        val (container, trailing) = row(100, 140, 60)

        assertEquals(100, container.getComponent(0).width)
        assertEquals(0, trailing!!.width)
    }

    @Test
    fun `without a trailing component the title fills the row`() {
        val (container, _) = row(200, 80, null)

        assertEquals(200, container.getComponent(0).width)
    }
}
