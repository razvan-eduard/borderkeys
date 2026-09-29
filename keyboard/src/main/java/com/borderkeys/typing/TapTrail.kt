// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/**
 * Where each code point of the word being written was typed: the key's index on the layout and
 * the point where it was chosen, in the keyboard view's pixels, or no point for a code point no
 * tap chose. One entry per code point, edited with the word.
 */
class TapTrail {

    private var keyIndexes = IntArray(INITIAL_CAPACITY)
    private var xs = FloatArray(INITIAL_CAPACITY)
    private var ys = FloatArray(INITIAL_CAPACITY)

    /** How many code points the trail covers. */
    var size = 0
        private set

    /** A code point chosen at [keyIndex], at ([x], [y]); NaN coordinates for no point. */
    fun add(keyIndex: Int, x: Float, y: Float) {
        if (size == keyIndexes.size) {
            val capacity = size * 2
            keyIndexes = keyIndexes.copyOf(capacity)
            xs = xs.copyOf(capacity)
            ys = ys.copyOf(capacity)
        }
        keyIndexes[size] = keyIndex
        xs[size] = x
        ys[size] = y
        size++
    }

    /** [count] code points that no tap chose. */
    fun addUntapped(count: Int) {
        repeat(count) { add(NO_KEY, Float.NaN, Float.NaN) }
    }

    fun removeLast() {
        if (size > 0) {
            size--
        }
    }

    fun clear() {
        size = 0
    }

    fun keyIndexAt(index: Int): Int = keyIndexes[index]

    fun xAt(index: Int): Float = xs[index]

    fun yAt(index: Int): Float = ys[index]

    /** Each code point's x, NaN for no point, as a new array. */
    fun copyXs(): FloatArray = xs.copyOf(size)

    /** Each code point's y, NaN for no point, as a new array. */
    fun copyYs(): FloatArray = ys.copyOf(size)

    /** Whether the code point at [index] was chosen by a tap with a point. */
    fun isTapped(index: Int): Boolean = !xs[index].isNaN() && !ys[index].isNaN()

    companion object {
        /** No key: a code point no tap chose. */
        const val NO_KEY = -1

        private const val INITIAL_CAPACITY = 48
    }
}
