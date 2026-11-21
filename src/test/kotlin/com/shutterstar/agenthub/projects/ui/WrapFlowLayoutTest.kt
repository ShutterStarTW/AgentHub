package com.shutterstar.agenthub.projects.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

class WrapFlowLayoutTest {
    private fun row(width: Int): JPanel = JPanel(WrapFlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        repeat(3) { add(JPanel().apply { preferredSize = Dimension(100, 20) }) }
        setSize(width, 100)
    }

    @Test
    fun shouldGrowTallerWhenTheComponentsDoNotFitOneRow() {
        SwingUtilities.invokeAndWait {
            val wide = row(width = 500).preferredSize.height
            val narrow = row(width = 230).preferredSize.height
            val veryNarrow = row(width = 110).preferredSize.height

            assertEquals(20, wide)
            assertEquals(40, narrow)
            assertEquals(60, veryNarrow)
            assertTrue(narrow > wide)
        }
    }
}
