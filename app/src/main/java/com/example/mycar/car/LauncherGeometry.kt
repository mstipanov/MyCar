package com.example.mycar.car

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Pure geometry for the car-side launcher, deliberately free of `android.graphics` types so it can
 * be unit-tested on the host without a Surface or a running car host.
 *
 * Two layouts describe the launcher:
 * - [collapsedLayout]: the strip that is carved out of the mirror. It has room for
 *   [CollapsedLayout.appCapacity] app cells plus the action cells (expand and rotate); apps that do
 *   not fit are not drawn outside the screen and are only reachable from the expanded panel.
 * - [expandedLayout]: a temporary panel over the mirror with every app in a grid, plus rotate and
 *   close cells in the panel's top-right corner. The strip keeps its thickness while this is open,
 *   so closing the panel does not move the mirror.
 *
 * Sizes are surface pixels. [thickness] is the strip thickness, which is also the minimum cell edge
 * (and therefore the minimum icon cell size).
 */
object LauncherGeometry {

    /** Most grid columns in the expanded panel, so a typical head unit shows 4-6 across. */
    const val MAX_COLUMNS = 6

    /** Expanded cells may grow, but only to this multiple of the strip thickness. */
    const val MAX_CELL_FACTOR = 2f

    /** One rectangle in surface pixels. */
    data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        fun contains(x: Float, y: Float): Boolean =
            x >= left && x < right && y >= top && y < bottom
    }

    /** A built-in launcher action. Apps have `action == null` and a non-negative [Cell.appIndex]. */
    enum class Action { EXPAND, ROTATE, CLOSE }

    /** One tappable cell in either layout. */
    data class Cell(
        val rect: Rect,
        val appIndex: Int = NO_APP,
        val action: Action? = null,
    ) {
        companion object {
            const val NO_APP = -1
        }
    }

    /**
     * The collapsed strip. [shownApps] app cells are drawn; [appCapacity] is how many fit next to
     * the action cells. [overflow] says the remaining apps are only reachable from the panel.
     */
    data class CollapsedLayout(
        val strip: Rect,
        val cells: List<Cell>,
        val appCapacity: Int,
        val shownApps: Int,
        val overflow: Boolean,
    )

    /** The temporary panel over the mirror plus its app grid and corner actions. */
    data class ExpandedLayout(
        val panel: Rect,
        val grid: Rect,
        val cells: List<Cell>,
        val rows: Int,
        val columns: Int,
        val cellSize: Float,
    )

    /** What a tap resolved to. [Kind.NONE] means it belongs to the mirror, not the launcher. */
    data class Tap(val kind: Kind, val appIndex: Int = Cell.NO_APP) {
        enum class Kind { NONE, APP, EXPAND, ROTATE, CLOSE, COLLAPSE }
    }

    /**
     * The collapsed strip: a right-hand column, or a bottom row when a host panel is open. Apps are
     * centred in the space before the action block; rotate stays at the far end with expand next to
     * it. When the strip is too short for every app the extra apps are simply not laid out — the
     * panel is what makes them reachable.
     */
    fun collapsedLayout(
        area: Rect,
        thickness: Float,
        atBottom: Boolean,
        appCount: Int,
    ): CollapsedLayout {
        val strip = if (atBottom) {
            Rect(area.left, (area.bottom - thickness).coerceAtLeast(area.top), area.right, area.bottom)
        } else {
            Rect((area.right - thickness).coerceAtLeast(area.left), area.top, area.right, area.bottom)
        }
        val length = if (atBottom) strip.width else strip.height
        val slots = if (thickness <= 0f || length <= 0f) {
            0
        } else {
            floor(length / thickness).toInt()
        }

        // Expand is always the first action kept; rotate joins it when there is room for both.
        val actionCount = when {
            slots >= 2 -> 2
            slots >= 1 -> 1
            else -> 0
        }
        val appCapacity = (slots - actionCount).coerceAtLeast(0)
        val shownApps = appCount.coerceIn(0, appCapacity)
        val actionsStart = if (atBottom) {
            strip.right - actionCount * thickness
        } else {
            strip.bottom - actionCount * thickness
        }
        val appRegionStart = if (atBottom) strip.left else strip.top
        val appRegionLength = (actionsStart - appRegionStart).coerceAtLeast(0f)
        val shownSpan = shownApps * thickness
        val appStart = appRegionStart + (appRegionLength - shownSpan) / 2f

        val cells = ArrayList<Cell>(shownApps + actionCount)
        for (index in 0 until shownApps) {
            val start = appStart + index * thickness
            val rect = if (atBottom) {
                Rect(start, strip.top, start + thickness, strip.bottom)
            } else {
                Rect(strip.left, start, strip.right, start + thickness)
            }
            cells += Cell(rect, appIndex = index)
        }

        if (atBottom) {
            if (actionCount == 2) {
                // Rotate outermost, expand immediately beside it.
                cells += Cell(
                    Rect(strip.right - thickness, strip.top, strip.right, strip.bottom),
                    action = Action.ROTATE,
                )
                cells += Cell(
                    Rect(strip.right - 2f * thickness, strip.top, strip.right - thickness, strip.bottom),
                    action = Action.EXPAND,
                )
            } else if (actionCount == 1) {
                cells += Cell(
                    Rect(strip.right - thickness, strip.top, strip.right, strip.bottom),
                    action = Action.EXPAND,
                )
            }
        } else {
            if (actionCount == 2) {
                // Rotate bottom-most, expand immediately above it.
                cells += Cell(
                    Rect(strip.left, strip.bottom - thickness, strip.right, strip.bottom),
                    action = Action.ROTATE,
                )
                cells += Cell(
                    Rect(strip.left, strip.bottom - 2f * thickness, strip.right, strip.bottom - thickness),
                    action = Action.EXPAND,
                )
            } else if (actionCount == 1) {
                cells += Cell(
                    Rect(strip.left, strip.bottom - thickness, strip.right, strip.bottom),
                    action = Action.EXPAND,
                )
            }
        }

        return CollapsedLayout(
            strip = strip,
            cells = cells,
            appCapacity = appCapacity,
            shownApps = shownApps,
            overflow = appCount > appCapacity,
        )
    }

    /**
     * The expanded panel over the mirror. The panel is the mirror area (the strip is left alone),
     * with a top band for close/rotate in its top-right corner. The grid fills a column (bottom
     * strip: rows grow upward) or a row (right strip: columns grow leftward) at the strip's edge.
     * Columns are capped at [MAX_COLUMNS] so a typical head unit gets 4-6 across; more columns are
     * only used when that is the only way to fit every app at the minimum cell size.
     */
    fun expandedLayout(
        area: Rect,
        thickness: Float,
        atBottom: Boolean,
        appCount: Int,
    ): ExpandedLayout {
        val panel = if (atBottom) {
            Rect(area.left, area.top, area.right, (area.bottom - thickness).coerceAtLeast(area.top))
        } else {
            Rect(area.left, area.top, (area.right - thickness).coerceAtLeast(area.left), area.bottom)
        }
        val actionBand = thickness.coerceAtMost(panel.height)
        val grid = Rect(panel.left, panel.top + actionBand, panel.right, panel.bottom)

        val actions = ArrayList<Cell>(2)
        if (panel.width >= thickness && panel.height >= thickness) {
            actions += Cell(
                Rect(panel.right - thickness, panel.top, panel.right, panel.top + thickness),
                action = Action.ROTATE,
            )
            if (panel.width >= 2f * thickness) {
                actions += Cell(
                    Rect(panel.right - 2f * thickness, panel.top, panel.right - thickness, panel.top + thickness),
                    action = Action.CLOSE,
                )
            }
        }

        if (appCount <= 0 || thickness <= 0f ||
            grid.width < thickness || grid.height < thickness
        ) {
            return ExpandedLayout(panel, grid, actions, rows = 0, columns = 0, cellSize = thickness)
        }

        val maxColumns = floor(grid.width / thickness).toInt().coerceAtLeast(1)
        val maxRows = floor(grid.height / thickness).toInt().coerceAtLeast(1)

        var columns = min(min(MAX_COLUMNS, maxColumns), appCount)
        var rows = ceilDiv(appCount, columns)
        if (rows > maxRows) {
            // More apps than MAX_COLUMNS columns can hold at a readable size: grow the column
            // count until the rows fit, up to what the panel width allows.
            columns = min(maxColumns, ceilDiv(appCount, maxRows))
            rows = ceilDiv(appCount, columns)
        }

        var cellSize = min(grid.width / columns, grid.height / rows)
        cellSize = cellSize.coerceAtLeast(thickness).coerceAtMost(thickness * MAX_CELL_FACTOR)

        val cells = ArrayList<Cell>(appCount + actions.size)
        val gridWidth = columns * cellSize
        val gridHeight = rows * cellSize
        val leftMargin = (grid.width - gridWidth) / 2f
        val topMargin = (grid.height - gridHeight) / 2f
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                // Bottom strip fills a row then grows upward; right strip fills a column then
                // grows leftward, so the first apps are nearest the strip.
                val index = if (atBottom) row * columns + column else column * rows + row
                if (index >= appCount) continue
                val left = if (atBottom) {
                    grid.left + leftMargin + column * cellSize
                } else {
                    grid.right - (column + 1) * cellSize
                }
                val top = if (atBottom) {
                    grid.bottom - (row + 1) * cellSize
                } else {
                    grid.top + topMargin + row * cellSize
                }
                cells += Cell(
                    Rect(left, top, left + cellSize, top + cellSize),
                    appIndex = index,
                )
            }
        }
        cells += actions
        return ExpandedLayout(
            panel = panel,
            grid = grid,
            cells = cells,
            rows = rows,
            columns = columns,
            cellSize = cellSize,
        )
    }

    /** The cell under a point in the collapsed strip, or null for a gap / outside tap. */
    fun hitTest(layout: CollapsedLayout, x: Float, y: Float): Cell? =
        layout.cells.firstOrNull { it.rect.contains(x, y) }

    /** The cell under a point in the expanded panel, or null for a gap / outside tap. */
    fun hitTest(layout: ExpandedLayout, x: Float, y: Float): Cell? =
        layout.cells.firstOrNull { it.rect.contains(x, y) }

    /**
     * Resolves a tap without touching Android. While expanded, any tap that is not a panel cell is
     * a collapse request; the strip's expand cell is still the toggle that closes the panel.
     */
    fun tapAt(
        collapsed: CollapsedLayout,
        expanded: ExpandedLayout?,
        expandedVisible: Boolean,
        x: Float,
        y: Float,
    ): Tap {
        if (expandedVisible) {
            val panelCell = expanded?.let { hitTest(it, x, y) }
            if (panelCell != null) return panelCell.toTap()
            val stripCell = hitTest(collapsed, x, y)
            if (stripCell?.action == Action.EXPAND) return Tap(Tap.Kind.EXPAND)
            return Tap(Tap.Kind.COLLAPSE)
        }
        val cell = hitTest(collapsed, x, y) ?: return Tap(Tap.Kind.NONE)
        return cell.toTap()
    }

    private fun Cell.toTap(): Tap = when {
        appIndex >= 0 -> Tap(Tap.Kind.APP, appIndex)
        action == Action.EXPAND -> Tap(Tap.Kind.EXPAND)
        action == Action.ROTATE -> Tap(Tap.Kind.ROTATE)
        action == Action.CLOSE -> Tap(Tap.Kind.CLOSE)
        else -> Tap(Tap.Kind.NONE)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = ceil(value.toFloat() / divisor).toInt()
}

/**
 * The launcher's expand/collapse state and what each resolved [LauncherGeometry.Tap] does. Pure
 * Kotlin (the launching and rotating are injected as callbacks), so the "an app tap launches and
 * collapses" behaviour is testable without a Surface.
 */
class LauncherInteraction(
    private val launchApp: (Int) -> Unit,
    private val rotate: () -> Unit,
) {
    var expanded: Boolean = false
        private set

    fun collapse() {
        expanded = false
    }

    fun tap(tap: LauncherGeometry.Tap) {
        when (tap.kind) {
            LauncherGeometry.Tap.Kind.APP -> {
                expanded = false
                launchApp(tap.appIndex)
            }
            LauncherGeometry.Tap.Kind.ROTATE -> rotate()
            LauncherGeometry.Tap.Kind.EXPAND -> expanded = !expanded
            LauncherGeometry.Tap.Kind.CLOSE,
            LauncherGeometry.Tap.Kind.COLLAPSE,
            -> expanded = false
            LauncherGeometry.Tap.Kind.NONE -> Unit
        }
    }
}
