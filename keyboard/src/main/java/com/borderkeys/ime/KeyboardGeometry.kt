// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * A compiled layout, in parallel arrays indexed by key: where every key is, what it types, and
 * which key a touch belongs to.
 */
class KeyboardGeometry {

    var keyCount: Int = 0
        private set

    var keyLeft = FloatArray(0); private set
    var keyTop = FloatArray(0); private set
    var keyRight = FloatArray(0); private set
    var keyBottom = FloatArray(0); private set
    var keyCode = IntArray(0); private set
    var keyFlags = IntArray(0); private set
    var rowOfKey = IntArray(0); private set
    var centerX = FloatArray(0); private set
    var centerY = FloatArray(0); private set

    /** Every label's characters, end to end. */
    var labelChars = CharArray(0); private set
    var labelOffset = IntArray(0); private set
    var labelLength = IntArray(0); private set

    var altChars = CharArray(0); private set
    var altOffset = IntArray(0); private set
    var altLength = IntArray(0); private set

    private var gridColumns = 0
    private var gridRows = 0
    private var gridCellWidth = 0f
    private var gridCellHeight = 0f
    private var gridKey = IntArray(0)

    var viewWidth: Float = 0f; private set
    var viewHeight: Float = 0f; private set

    /**
     * Lays the layout out into [width] by [height] pixels, each row dividing the full width by its
     * own units. Reallocates only when the key count changes.
     */
    fun compile(layout: KeyboardLayout, width: Float, height: Float, gapPx: Float) {
        val total = layout.keyCount
        if (total != keyCount) {
            keyCount = total
            keyLeft = FloatArray(total)
            keyTop = FloatArray(total)
            keyRight = FloatArray(total)
            keyBottom = FloatArray(total)
            keyCode = IntArray(total)
            keyFlags = IntArray(total)
            rowOfKey = IntArray(total)
            centerX = FloatArray(total)
            centerY = FloatArray(total)
            labelOffset = IntArray(total)
            labelLength = IntArray(total)
            altOffset = IntArray(total)
            altLength = IntArray(total)
        }
        viewWidth = width
        viewHeight = height

        val labelBuilder = StringBuilder()
        val altBuilder = StringBuilder()
        val heightUnit = height / layout.totalHeightScale
        val halfGap = gapPx / 2f

        var index = 0
        var y = 0f
        for ((rowIndex, row) in layout.rows.withIndex()) {
            val rowHeight = row.heightScale * heightUnit
            val unitWidth = width / row.units
            var x = row.indent * unitWidth
            for (key in row.keys) {
                val keyWidth = key.widthUnits * unitWidth
                keyLeft[index] = x + halfGap
                keyTop[index] = y + halfGap
                keyRight[index] = x + keyWidth - halfGap
                keyBottom[index] = y + rowHeight - halfGap
                keyCode[index] = key.code
                keyFlags[index] = key.flags
                rowOfKey[index] = rowIndex
                centerX[index] = (keyLeft[index] + keyRight[index]) / 2f
                centerY[index] = (keyTop[index] + keyBottom[index]) / 2f

                labelOffset[index] = labelBuilder.length
                labelLength[index] = key.label.length
                labelBuilder.append(key.label)

                altOffset[index] = altBuilder.length
                altLength[index] = key.alternatives.length
                altBuilder.append(key.alternatives)

                x += keyWidth
                index++
            }
            y += rowHeight
        }

        labelChars = labelBuilder.toString().toCharArray()
        altChars = altBuilder.toString().toCharArray()

        buildHitGrid(layout, width, height)
    }

    /**
     * A uniform grid over the keyboard, each cell resolved to the key containing its centre, or to
     * the nearest key for a cell in a gap.
     */
    private fun buildHitGrid(layout: KeyboardLayout, width: Float, height: Float) {
        gridColumns = GRID_COLUMNS
        gridRows = maxOf(4, layout.rows.size * GRID_ROWS_PER_KEY_ROW)
        gridCellWidth = width / gridColumns
        gridCellHeight = height / gridRows
        val cells = gridColumns * gridRows
        if (gridKey.size != cells) {
            gridKey = IntArray(cells)
        }
        for (row in 0 until gridRows) {
            val sampleY = (row + 0.5f) * gridCellHeight
            for (column in 0 until gridColumns) {
                gridKey[row * gridColumns + column] =
                    nearestKey((column + 0.5f) * gridCellWidth, sampleY)
            }
        }
    }

    /** The key containing ([x], [y]), else the nearest by squared distance. */
    fun nearestKey(x: Float, y: Float): Int {
        var best = NO_KEY
        var bestDistance = Float.MAX_VALUE
        for (index in 0 until keyCount) {
            if (x >= keyLeft[index] && x < keyRight[index] &&
                y >= keyTop[index] && y < keyBottom[index]
            ) {
                return index
            }
            val dx = when {
                x < keyLeft[index] -> keyLeft[index] - x
                x > keyRight[index] -> x - keyRight[index]
                else -> 0f
            }
            val dy = when {
                y < keyTop[index] -> keyTop[index] - y
                y > keyBottom[index] -> y - keyBottom[index]
                else -> 0f
            }
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    fun contains(index: Int, x: Float, y: Float): Boolean =
        index >= 0 && index < keyCount &&
            x >= keyLeft[index] && x < keyRight[index] &&
            y >= keyTop[index] && y < keyBottom[index]

    /**
     * The key a touch at ([x], [y]) belongs to, in constant time: the cell's key when its
     * rectangle contains the point, else the key of one of the eight surrounding cells that does,
     * else the cell's nearest key.
     */
    fun findKeyAt(x: Float, y: Float): Int {
        if (keyCount == 0 || gridKey.isEmpty()) {
            return NO_KEY
        }
        val column = (x / gridCellWidth).toInt().coerceIn(0, gridColumns - 1)
        val row = (y / gridCellHeight).toInt().coerceIn(0, gridRows - 1)
        val candidate = gridKey[row * gridColumns + column]
        if (contains(candidate, x, y)) {
            return candidate
        }
        var neighbourRow = maxOf(0, row - 1)
        val lastRow = minOf(gridRows - 1, row + 1)
        val lastColumn = minOf(gridColumns - 1, column + 1)
        while (neighbourRow <= lastRow) {
            var neighbourColumn = maxOf(0, column - 1)
            while (neighbourColumn <= lastColumn) {
                val neighbour = gridKey[neighbourRow * gridColumns + neighbourColumn]
                if (contains(neighbour, x, y)) {
                    return neighbour
                }
                neighbourColumn++
            }
            neighbourRow++
        }
        return candidate
    }

    /** Letter keys only, in the form the native engine wants for proximity correction. */
    fun exportGeometry(codesOut: IntArray, centersXOut: FloatArray, centersYOut: FloatArray): Int {
        var written = 0
        for (index in 0 until keyCount) {
            if (written >= codesOut.size) {
                break
            }
            if (!KeyFlags.has(keyFlags[index], KeyFlags.LETTER)) {
                continue
            }
            codesOut[written] = keyCode[index]
            centersXOut[written] = centerX[index]
            centersYOut[written] = centerY[index]
            written++
        }
        return written
    }

    val averageKeyWidth: Float
        get() {
            if (keyCount == 0) return 0f
            var sum = 0f
            for (index in 0 until keyCount) sum += keyRight[index] - keyLeft[index]
            return sum / keyCount
        }

    val averageKeyHeight: Float
        get() {
            if (keyCount == 0) return 0f
            var sum = 0f
            for (index in 0 until keyCount) sum += keyBottom[index] - keyTop[index]
            return sum / keyCount
        }

    companion object {
        const val NO_KEY = -1
        /** Grid columns; [findKeyAt] is exact while a cell is smaller than the narrowest key. */
        private const val GRID_COLUMNS = 64
        private const val GRID_ROWS_PER_KEY_ROW = 6
    }
}
