package com.example.mycar.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launcher geometry and tap decisions, pinned without a Surface: the strip must never lay an
 * icon outside the screen, overflow apps must still be reachable from the expanded panel, the panel
 * must follow the strip's orientation, and choosing an app must both launch it and collapse.
 */
class LauncherGeometryTest {

    private val area = LauncherGeometry.Rect(0f, 0f, 1000f, 600f)
    private val thickness = 78f

    private fun collapsed(appCount: Int, atBottom: Boolean = true) =
        LauncherGeometry.collapsedLayout(area, thickness, atBottom, appCount)

    private fun expanded(appCount: Int, atBottom: Boolean = true) =
        LauncherGeometry.expandedLayout(area, thickness, atBottom, appCount)

    private fun apps(layout: LauncherGeometry.CollapsedLayout) =
        layout.cells.filter { it.appIndex >= 0 }

    private fun apps(layout: LauncherGeometry.ExpandedLayout) =
        layout.cells.filter { it.appIndex >= 0 }

    private fun cell(layout: LauncherGeometry.CollapsedLayout, action: LauncherGeometry.Action) =
        layout.cells.first { it.action == action }

    private fun cell(layout: LauncherGeometry.ExpandedLayout, action: LauncherGeometry.Action) =
        layout.cells.first { it.action == action }

    // region capacity

    @Test
    fun `with no apps the strip still keeps expand and rotate`() {
        val layout = collapsed(appCount = 0)

        // 1000 / 78 = 12 cells fit; two are actions, so ten app cells could be shown.
        assertEquals(10, layout.appCapacity)
        assertEquals(0, layout.shownApps)
        assertFalse(layout.overflow)
        assertEquals(2, layout.cells.size)
        assertTrue(layout.cells.any { it.action == LauncherGeometry.Action.EXPAND })
        assertTrue(layout.cells.any { it.action == LauncherGeometry.Action.ROTATE })
    }

    @Test
    fun `a single app fits and is shown`() {
        val layout = collapsed(appCount = 1)

        assertEquals(1, layout.shownApps)
        assertFalse(layout.overflow)
        assertEquals(1, apps(layout).size)
        assertEquals(0, apps(layout).single().appIndex)
        assertTrue(layout.strip.contains(apps(layout).single().rect.centerX, apps(layout).single().rect.centerY))
    }

    @Test
    fun `many apps are capped to what fits and never drawn outside the strip`() {
        val layout = collapsed(appCount = 20)

        assertEquals(10, layout.appCapacity)
        assertEquals(10, layout.shownApps)
        assertTrue(layout.overflow)
        assertEquals(12, layout.cells.size)
        for (cell in layout.cells) {
            val rect = cell.rect
            assertTrue(rect.left >= layout.strip.left)
            assertTrue(rect.right <= layout.strip.right + 0.01f)
            assertTrue(rect.top >= layout.strip.top)
            assertTrue(rect.bottom <= layout.strip.bottom + 0.01f)
        }
    }

    @Test
    fun `the right column has its own capacity from the surface height`() {
        val layout = collapsed(appCount = 20, atBottom = false)

        // 600 / 78 = 7 cells fit; minus expand and rotate leaves five.
        assertEquals(5, layout.appCapacity)
        assertEquals(5, layout.shownApps)
        assertTrue(layout.overflow)
        assertEquals(7, layout.cells.size)
    }

    // endregion

    // region overflow and grid shape

    @Test
    fun `overflow apps are absent from the strip but present in the panel`() {
        val strip = collapsed(appCount = 20)
        val panel = expanded(appCount = 20)

        val stripApps = apps(strip).map { it.appIndex }.toSet()
        val panelApps = apps(panel).map { it.appIndex }.toSet()

        assertEquals((0 until 10).toSet(), stripApps)
        assertEquals((0 until 20).toSet(), panelApps)
        assertTrue(panelApps - stripApps == (10 until 20).toSet())
    }

    @Test
    fun `bottom grid grows upward with four to six columns`() {
        val layout = expanded(appCount = 20)

        assertEquals(6, layout.columns)
        assertEquals(4, layout.rows)
        // Panel is 600 - 78 high; a 78 band is reserved for the corner actions.
        assertEquals(522f, layout.panel.bottom, 0.01f)
        assertEquals(78f, layout.grid.top, 0.01f)
        assertEquals(111f, layout.cellSize, 0.01f)

        val first = apps(layout).first { it.appIndex == 0 }.rect
        val secondRow = apps(layout).first { it.appIndex == 6 }.rect
        assertEquals(layout.grid.bottom, first.bottom, 0.01f)
        assertTrue(secondRow.top < first.top)
    }

    @Test
    fun `right grid grows leftward with four to six columns`() {
        val layout = expanded(appCount = 20, atBottom = false)

        assertEquals(6, layout.columns)
        assertEquals(4, layout.rows)
        assertEquals(922f, layout.panel.right, 0.01f)
        assertEquals(130.5f, layout.cellSize, 0.01f)

        val first = apps(layout).first { it.appIndex == 0 }.rect
        val nextDown = apps(layout).first { it.appIndex == 1 }.rect
        val nextColumn = apps(layout).first { it.appIndex == 4 }.rect
        assertEquals(layout.grid.right, first.right, 0.01f)
        assertTrue(nextDown.top > first.top)
        assertTrue(nextColumn.left < first.left)
    }

    @Test
    fun `expanded panel covers the mirror and leaves the strip thickness untouched`() {
        val bottomStrip = collapsed(appCount = 0)
        val bottomPanel = expanded(appCount = 0)
        assertEquals(bottomStrip.strip.top, bottomPanel.panel.bottom, 0.01f)
        assertEquals(600f - 78f, bottomPanel.panel.bottom, 0.01f)

        val rightStrip = collapsed(appCount = 0, atBottom = false)
        val rightPanel = expanded(appCount = 0, atBottom = false)
        assertEquals(rightStrip.strip.left, rightPanel.panel.right, 0.01f)
        assertEquals(1000f - 78f, rightPanel.panel.right, 0.01f)
    }

    @Test
    fun `corner actions sit in the top right of the panel`() {
        val layout = expanded(appCount = 20)

        val rotate = cell(layout, LauncherGeometry.Action.ROTATE).rect
        val close = cell(layout, LauncherGeometry.Action.CLOSE).rect
        assertEquals(layout.panel.right, rotate.right, 0.01f)
        assertEquals(0f, rotate.top, 0.01f)
        assertEquals(rotate.left, close.right, 0.01f)
    }

    // endregion

    // region hit testing

    @Test
    fun `every collapsed cell centre hits that cell`() {
        val layout = collapsed(appCount = 20)
        for (cell in layout.cells) {
            assertEquals(cell, LauncherGeometry.hitTest(layout, cell.rect.centerX, cell.rect.centerY))
        }
    }

    @Test
    fun `every expanded cell centre hits that cell`() {
        val layout = expanded(appCount = 20, atBottom = false)
        for (cell in layout.cells) {
            assertEquals(cell, LauncherGeometry.hitTest(layout, cell.rect.centerX, cell.rect.centerY))
        }
    }

    @Test
    fun `a gap inside the panel misses every cell`() {
        val bottom = expanded(appCount = 20)
        // The six 111px columns do not fill 1000px, so the left margin is a real gap.
        val gap = bottom.grid.left + 10f to bottom.grid.bottom - 10f
        assertTrue(bottom.panel.contains(gap.first, gap.second))
        assertNull(LauncherGeometry.hitTest(bottom, gap.first, gap.second))

        val right = expanded(appCount = 20, atBottom = false)
        val rightGap = right.grid.left + 10f to right.grid.top + 10f
        assertTrue(right.panel.contains(rightGap.first, rightGap.second))
        assertNull(LauncherGeometry.hitTest(right, rightGap.first, rightGap.second))
    }

    @Test
    fun `a tap outside the panel is outside for both orientations`() {
        val bottom = expanded(appCount = 20)
        assertFalse(bottom.panel.contains(10f, 590f))
        assertNull(LauncherGeometry.hitTest(bottom, 10f, 590f))

        val right = expanded(appCount = 20, atBottom = false)
        assertFalse(right.panel.contains(990f, 10f))
        assertNull(LauncherGeometry.hitTest(right, 990f, 10f))
    }

    // endregion

    // region tap behaviour

    @Test
    fun `an app tap in the expanded panel launches and collapses`() {
        val strip = collapsed(appCount = 20)
        val panel = expanded(appCount = 20)
        var launched = -1
        var rotations = 0
        val interaction = LauncherInteraction(
            launchApp = { launched = it },
            rotate = { rotations++ },
        )

        // Open with the strip's expand cell.
        val expand = cell(strip, LauncherGeometry.Action.EXPAND).rect
        interaction.tap(
            LauncherGeometry.tapAt(strip, panel, interaction.expanded, expand.centerX, expand.centerY),
        )
        assertTrue(interaction.expanded)

        val appCell = apps(panel).first { it.appIndex == 12 }
        val tap = LauncherGeometry.tapAt(
            strip, panel, interaction.expanded, appCell.rect.centerX, appCell.rect.centerY,
        )
        assertEquals(LauncherGeometry.Tap.Kind.APP, tap.kind)
        assertEquals(12, tap.appIndex)

        interaction.tap(tap)
        assertEquals(12, launched)
        assertEquals(0, rotations)
        assertFalse(interaction.expanded)
    }

    @Test
    fun `the expand cell toggles the panel closed`() {
        val strip = collapsed(appCount = 20)
        val panel = expanded(appCount = 20)
        val interaction = LauncherInteraction(launchApp = {}, rotate = {})
        val expand = cell(strip, LauncherGeometry.Action.EXPAND).rect

        interaction.tap(
            LauncherGeometry.tapAt(strip, panel, interaction.expanded, expand.centerX, expand.centerY),
        )
        assertTrue(interaction.expanded)

        interaction.tap(
            LauncherGeometry.tapAt(strip, panel, interaction.expanded, expand.centerX, expand.centerY),
        )
        assertFalse(interaction.expanded)
    }

    @Test
    fun `the close cell collapses without launching`() {
        val strip = collapsed(appCount = 20)
        val panel = expanded(appCount = 20)
        var launched = -1
        val interaction = LauncherInteraction(launchApp = { launched = it }, rotate = {})
        interaction.tap(
            LauncherGeometry.tapAt(
                strip, panel, interaction.expanded,
                cell(strip, LauncherGeometry.Action.EXPAND).rect.centerX,
                cell(strip, LauncherGeometry.Action.EXPAND).rect.centerY,
            ),
        )

        val close = cell(panel, LauncherGeometry.Action.CLOSE).rect
        val tap = LauncherGeometry.tapAt(
            strip, panel, interaction.expanded, close.centerX, close.centerY,
        )
        assertEquals(LauncherGeometry.Tap.Kind.CLOSE, tap.kind)
        interaction.tap(tap)

        assertFalse(interaction.expanded)
        assertEquals(-1, launched)
    }

    @Test
    fun `a tap outside the panel collapses without launching`() {
        val strip = collapsed(appCount = 20)
        val panel = expanded(appCount = 20)
        var launched = -1
        val interaction = LauncherInteraction(launchApp = { launched = it }, rotate = {})
        interaction.tap(
            LauncherGeometry.tapAt(
                strip, panel, interaction.expanded,
                cell(strip, LauncherGeometry.Action.EXPAND).rect.centerX,
                cell(strip, LauncherGeometry.Action.EXPAND).rect.centerY,
            ),
        )

        val tap = LauncherGeometry.tapAt(strip, panel, interaction.expanded, 10f, 590f)
        assertEquals(LauncherGeometry.Tap.Kind.COLLAPSE, tap.kind)
        interaction.tap(tap)

        assertFalse(interaction.expanded)
        assertEquals(-1, launched)
    }

    @Test
    fun `the panel rotate action does not collapse the panel`() {
        val strip = collapsed(appCount = 20)
        val panel = expanded(appCount = 20)
        var rotations = 0
        val interaction = LauncherInteraction(launchApp = {}, rotate = { rotations++ })
        interaction.tap(
            LauncherGeometry.tapAt(
                strip, panel, interaction.expanded,
                cell(strip, LauncherGeometry.Action.EXPAND).rect.centerX,
                cell(strip, LauncherGeometry.Action.EXPAND).rect.centerY,
            ),
        )

        val rotate = cell(panel, LauncherGeometry.Action.ROTATE).rect
        val tap = LauncherGeometry.tapAt(
            strip, panel, interaction.expanded, rotate.centerX, rotate.centerY,
        )
        interaction.tap(tap)

        assertEquals(1, rotations)
        assertTrue(interaction.expanded)
    }

    @Test
    fun `a collapsed strip app tap still launches and stays collapsed`() {
        val strip = collapsed(appCount = 20)
        val panel = expanded(appCount = 20)
        var launched = -1
        val interaction = LauncherInteraction(launchApp = { launched = it }, rotate = {})

        val appCell = apps(strip).first { it.appIndex == 3 }
        val tap = LauncherGeometry.tapAt(
            strip, panel, interaction.expanded, appCell.rect.centerX, appCell.rect.centerY,
        )
        assertEquals(LauncherGeometry.Tap.Kind.APP, tap.kind)
        interaction.tap(tap)

        assertEquals(3, launched)
        assertFalse(interaction.expanded)
    }

    // endregion
}
