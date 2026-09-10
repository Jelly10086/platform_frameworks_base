/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.volume.dialog.sliders.domain.interactor

import android.media.AppVolume
import android.media.AudioManager
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogStateInteractor
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStreamModel
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.domain.model.VolumeDialogSliderType
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Operates a state of particular slider of the Volume Dialog. */
@VolumeDialogSliderScope
class VolumeDialogSliderInteractor
@Inject
constructor(
    private val sliderType: VolumeDialogSliderType,
    @Background private val backgroundContext: CoroutineContext,
    @VolumeDialog private val coroutineScope: CoroutineScope,
    volumeDialogStateInteractor: VolumeDialogStateInteractor,
    private val volumeDialogController: VolumeDialogController,
    private val audioManager: AudioManager,
    zenModeInteractor: ZenModeInteractor,
) {

    val isDisabledByZenMode: Flow<Boolean> =
        if (zenModeInteractor.canBeBlockedByZenMode(sliderType)) {
            zenModeInteractor.activeModesBlockingStream(AudioStream(sliderType.audioStream)).map {
                it.main != null
            }
        } else {
            flowOf(false)
        }
    val slider: Flow<VolumeDialogStreamModel> =
        volumeDialogStateInteractor.volumeDialogState
            .mapNotNull {
                it.streamModels[sliderType.audioStream]?.run {
                    if (level < levelMin || level > levelMax) {
                        copy(level = level.coerceIn(levelMin, levelMax))
                    } else {
                        this
                    }
                }
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)
            .filterNotNull()

    val appVolume: Flow<AppVolume> =
        if (sliderType is VolumeDialogSliderType.App) {
            volumeDialogStateInteractor.volumeDialogState
                .mapNotNull {
                    audioManager.listAppVolumes().firstOrNull { appVolume ->
                        appVolume.packageName == sliderType.packageName
                    }
                }
                .stateIn(coroutineScope, SharingStarted.Eagerly, null)
                .filterNotNull()
        } else {
            emptyFlow()
        }

    suspend fun setStreamVolume(userLevel: Int) {
        withContext(backgroundContext) {
            with(volumeDialogController) {
                setStreamVolume(sliderType.audioStream, userLevel, true)
                setActiveStream(sliderType.audioStream, true)
            }
        }
    }

    suspend fun setAppVolume(userLevel: Int) {
        val appSlider = sliderType as? VolumeDialogSliderType.App ?: return
        withContext(backgroundContext) {
            audioManager.setAppVolume(
                appSlider.packageName,
                userLevel.coerceIn(APP_VOLUME_MIN, APP_VOLUME_MAX) / APP_VOLUME_MAX.toFloat(),
            )
        }
    }

    private companion object {
        const val APP_VOLUME_MIN = 0
        const val APP_VOLUME_MAX = 100
    }
}

private fun ZenModeInteractor.canBeBlockedByZenMode(sliderType: VolumeDialogSliderType): Boolean {
    return sliderType is VolumeDialogSliderType.Stream &&
        canBeBlockedByZenMode(AudioStream(sliderType.audioStream))
}
