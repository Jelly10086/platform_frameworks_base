/*
 * Copyright (C) 2026 The uwuAOSP Project
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

package org.uwuaosp.systemui.lyric;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

final class LyricSourceFactory {
    private static final String DEFAULT_LYRIC_SOURCE_URL =
            "https://api.uwuaosp.uwuniverse.org";

    private LyricSourceFactory() {
    }

    static List<LyricSource> create(String configuredSources) {
        ArrayList<LyricSource> sources = new ArrayList<>();
        if (!TextUtils.isEmpty(configuredSources)) {
            for (String source : configuredSources.split(";")) {
                source = source.trim();
                if (source.regionMatches(true, 0, "https://", 0, 8)) {
                    sources.add(new HttpLyricSource(source));
                }
            }
        } else {
            sources.add(new HttpLyricSource(DEFAULT_LYRIC_SOURCE_URL));
        }
        sources.add(new NetEaseLyricProvider());
        return sources;
    }
}
