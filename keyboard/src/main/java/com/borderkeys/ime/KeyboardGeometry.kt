// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * A compiled layout: where every key is, what it types, and which key a touch belongs to.
 *
 * Split out of the view and free of Android on purpose. Hit-testing and the arrangement of key
 * rectangles are the two things in the keyboard that are pure arithmetic and easy to get subtly
 * wrong -- a one-pixel gap between two keys is a touch that does nothing, and neither a device
 * nor a screenshot will show it. Here they can be tested for what they are.
 *
 * Everything is a parallel array of primitives indexed by key. There is no `Key` object and no
 * `List<Key>`: the draw path reads floats and the touch path reads ints, and neither
 * dereferences anything per key.
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

    /** Every label's characters end to end, so no `String` is created while drawing. */
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
     * Lays the layout out into [width] by [height] pixels.
     *
     * Reallocates only when the number of keys changed, so a rotation or a window resize reuses
     * every array. Rows are independent: each one divides the full width by its own total in
     * units, which is what lets a row of ten keys and a row with a five-unit space bar both
     * reach the edges exactly.
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
     * A uniform grid over the keyboard, every cell resolved to the key a touch in it belongs to.
     *
     * Built once so that [findKeyAt] is an array index rather than a scan over forty keys on
     * every motion event -- and there are several of those per frame while a finger moves.
     *
     * Cells falling in the gap between keys are resolved here, at build time, to the nearest key
     * by squared distance to its rectangle. So a finger landing between two keys still types,
     * the fallback costs nothing at touch time, and there is no such thing as a dead pixel on
     * this keyboard.
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

    /** Exact containment first, then nearest by squared distance. No square roots anywhere. */
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
     * The key a touch at (x, y) belongs to. O(1), and exact.
     *
     * The grid alone is not exact, and the difference matters. A cell records the key containing
     * its *centre*, so a point near a key boundary can sit in a different key than its cell's
     * centre did -- by up to half a cell, which at the resolutions here is a band several pixels
     * wide down the side of every key where the wrong character would be typed. Invisible in a
     * screenshot, and exactly what "this keyboard is hard to type on" is made of.
     *
     * So the grid answer is verified against the key's actual rectangle, and on a miss the eight
     * surrounding cells are checked. That is enough to be exact rather than approximate: cells
     * are smaller than the smallest key, so a point and its cell centre are at most half a cell
     * apart, and any key that could contain the point owns one of those nine cells.
     *
     * When nothing contains the point it is in the gap between keys, and the grid's answer is
     * already the nearest key -- resolved at build time, so the fallback costs nothing here.
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
        /**
         * Chosen so a cell is always smaller than a key: the narrowest key on either shipped
         * layout is one unit of ten, so 64 columns puts more than six cells across it, and six
         * rows per key row does the same vertically. That is the precondition for the
         * nine-cell search in [findKeyAt] being exhaustive rather than merely usually right.
         */
        private const val GRID_COLUMNS = 64
        private const val GRID_ROWS_PER_KEY_ROW = 6
    }
}
