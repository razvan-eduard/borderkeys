// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.SharedNativeTextRunner
import org.junit.runners.model.FrameworkMethod
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/**
 * A Robolectric runner that loads [NativePredictor], and with it the host library, through the
 * class loader every test in the JVM shares, as [SharedNativeTextRunner] loads the personal
 * dictionary's text rules.
 */
class SharedNativeLibraryRunner(testClass: Class<*>) : SharedNativeTextRunner(testClass) {

    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            .doNotAcquireClass(NativePredictor::class.java.name)
            .build()
}
