/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.externaldesktop

import com.android.systemui.CoreStartable
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
abstract class ExternalDesktopModule {
    @Binds
    @IntoMap
    @ClassKey(ExternalDesktopStartable::class)
    abstract fun bindExternalDesktopStartable(startable: ExternalDesktopStartable): CoreStartable
}
