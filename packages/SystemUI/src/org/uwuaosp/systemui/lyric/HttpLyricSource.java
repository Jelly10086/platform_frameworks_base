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
        return fetch(track, "/v1/lyrics");
    }

    @Override
    public Lyrics fetchEnhanced(Track track) {
        return fetch(track, "/v2/lyrics");
    }

    private Lyrics fetch(Track track, String endpoint) {
        try {
            if (track == null) {
                return null;
            }
            String lyricUrl = mBaseUrl + endpoint + "?title=" + encode(track.title)
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
        JSONObject normalized = new JSONObject();
        normalized.put("lrc", new JSONObject().put("lyric", firstNonEmpty(
                lyricValue(response, "lrc"), lyricValue(response, "lyric"),
                lyricValue(response, "original"))));
        normalized.put("tlyric", new JSONObject().put("lyric", firstNonEmpty(
                lyricValue(response, "ytlrc"), lyricValue(response, "tlyric"),
                lyricValue(response, "translation"), lyricValue(response, "translated"))));
        normalized.put("yrc", new JSONObject().put("lyric", lyricValue(response, "yrc")));
        normalized.put("ytlrc", new JSONObject().put("lyric", lyricValue(response, "ytlrc")));
        return normalized;
    }

    private String lyricValue(JSONObject response, String key) {
        Object value = response.opt(key);
        if (value instanceof JSONObject) {
            return ((JSONObject) value).optString("lyric", "");
        }
        return value instanceof String ? (String) value : "";
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String encode(String value) throws IOException {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
    }
}
