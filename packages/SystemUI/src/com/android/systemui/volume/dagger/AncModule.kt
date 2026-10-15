/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.systemui.volume.dagger

import android.content.Context
import android.util.Log
import com.android.settingslib.bluetooth.devicesettings.data.repository.DeviceSettingRepository
import com.android.settingslib.bluetooth.devicesettings.data.repository.DeviceSettingRepositoryImpl
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import dagger.Module
import dagger.Provides
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job

/** Dagger module that provides ANC controlling backend. */
@Module
interface AncModule {

    companion object {
        private const val TAG = "AncModule"

        @Provides
        @SysUISingleton
        fun provideDeviceSettingsRepository(
            @Application context: Context,
            @Application coroutineScope: CoroutineScope,
            @Background coroutineContext: CoroutineContext,
        ): DeviceSettingRepository =
            DeviceSettingRepositoryImpl(
                context = context,
                // The repository runs async work against third-party provider services. A
                // failed child of the plain application Job cancels the whole application
                // scope without crashing, which silently stops keyguard and shade state.
                coroutineScope =
                    CoroutineScope(
                        coroutineScope.coroutineContext +
                            SupervisorJob(coroutineScope.coroutineContext.job) +
                            CoroutineExceptionHandler { _, e ->
                                Log.e(TAG, "Device setting provider failed", e)
                            }
                    ),
                backgroundCoroutineContext = coroutineContext,
            )
    }
}
