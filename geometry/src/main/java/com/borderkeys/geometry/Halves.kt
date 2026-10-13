// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.geometry

import kotlin.math.PI

/** A length's two sides: what a margin on both of them doubles, and what its middle halves. */
const val SIDES = 2

/** Half of [length]: the distance from either end to its middle. */
fun half(length: Float): Float = length / SIDES

/** Half of [length], rounded down. */
fun halfDown(length: Int): Int = length / SIDES

/** Half of [length]. */
fun half(length: Double): Double = length / SIDES

/** The point halfway from [start] to [end]. */
fun middle(start: Float, end: Float): Float = half(start + end)

/** The point halfway from [start] to [end], rounded down. */
fun middleDown(start: Int, end: Int): Int = halfDown(start + end)

/** [inset] taken on both sides of a length. */
fun bothSides(inset: Float): Float = inset * SIDES

/** [inset] taken on both sides of a length. */
fun bothSides(inset: Int): Int = inset * SIDES

/** [inset] taken on both sides of a length. */
fun bothSides(inset: Double): Double = inset * SIDES

/** The middle of the [index]th of a row of slots each [size] long. */
fun centreOfSlot(index: Int, size: Float): Float = (index + half(1f)) * size

/** The width of a circle of [radius]. */
fun diameter(radius: Float): Float = bothSides(radius)

/** The length around a rectangle [width] by [height]. */
fun perimeter(width: Float, height: Float): Float = bothSides(width + height)

/** A full turn, in radians. */
const val FULL_TURN: Float = (PI * SIDES).toFloat()

/** A quarter turn, in radians: the arc of a rounded corner. */
const val QUARTER_TURN: Float = (PI / SIDES).toFloat()
