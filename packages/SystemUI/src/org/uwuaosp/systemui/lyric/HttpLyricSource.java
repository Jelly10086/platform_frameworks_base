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

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** A configured source using the common lyric source HTTP contract. */
final class HttpLyricSource implements LyricSource {
    private static final String TAG = "HttpLyricSource";
    private final String mBaseUrl;

    HttpLyricSource(String baseUrl) {
        mBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public Lyrics fetch(Track track) {
        try {
            if (track == null) {
                return null;
            }
            String lyricUrl = mBaseUrl + "/v1/lyrics?title=" + encode(track.title)
                    + "&artist=" + encode(track.artist)
                    + "&album=" + encode(track.album)
                    + "&durationMs=" + track.durationMs
                    + "&sourcePackage=" + encode(track.packageName)
                    + "&mediaId=" + encode(track.mediaId);
            JSONObject lyricResponse = new JSONObject(NetEaseLyricProvider.request(lyricUrl));
            return NetEaseLyricProvider.parseLyrics(normalizeLyricResponse(lyricResponse));
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Unable to fetch lyrics from " + mBaseUrl, e);
            return null;
        }
    }

    private JSONObject normalizeLyricResponse(JSONObject response) throws JSONException {
        if (response.optJSONObject("lrc") != null || response.optJSONObject("tlyric") != null) {
            return response;
        }
        JSONObject normalized = new JSONObject();
        String original = response.optString("lrc", response.optString("lyric",
                response.optString("original", "")));
        String translated = response.optString("tlyric", response.optString("translation",
                response.optString("translated", "")));
        normalized.put("lrc", new JSONObject().put("lyric", original));
        normalized.put("tlyric", new JSONObject().put("lyric", translated));
        return normalized;
    }

    private String encode(String value) throws IOException {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
    }
}
