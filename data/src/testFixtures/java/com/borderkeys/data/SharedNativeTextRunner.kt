// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/**
 * A Robolectric runner that loads [NativeText], and with it the host library, through the class
 * loader every test in the JVM shares.
 */
open class SharedNativeTextRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {

    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            .doNotAcquireClass(NativeText::class.java.name)
            .build()

    companion object {
        /** The newest Android Robolectric runs, which the tests under these runners name. */
        const val SDK = 35
    }
}
