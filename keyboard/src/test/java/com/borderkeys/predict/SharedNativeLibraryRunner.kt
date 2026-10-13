// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/**
 * A Robolectric runner that loads [NativePredictor], and with it the host library, through the
 * class loader every test in the JVM shares.
 */
class SharedNativeLibraryRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {

    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            .doNotAcquireClass(NativePredictor::class.java.name)
            .build()
}
