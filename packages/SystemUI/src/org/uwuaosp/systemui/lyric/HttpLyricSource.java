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
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** A configured source using the common lyric source HTTP contract. */
final class HttpLyricSource implements LyricSource {
    private static final String TAG = "HttpLyricSource";
    private final String mBaseUrl;

    HttpLyricSource(String baseUrl) {
        mBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public Lyrics fetch(String title, String artist, long durationMs) {
        try {
            String searchUrl = mBaseUrl + "/search?title=" + encode(title)
                    + "&artist=" + encode(artist == null ? "" : artist)
                    + "&durationMs=" + durationMs;
            JSONObject searchResponse = new JSONObject(NetEaseLyricProvider.request(searchUrl));
            JSONObject song = findBestSong(searchResponse, title, artist, durationMs);
            if (song == null) {
                return null;
            }

            String id = song.optString("id", "");
            if (TextUtils.isEmpty(id)) {
                return null;
            }
            String lyricUrl = mBaseUrl + "/lyric?id=" + encode(id);
            JSONObject lyricResponse = new JSONObject(NetEaseLyricProvider.request(lyricUrl));
            return NetEaseLyricProvider.parseLyrics(normalizeLyricResponse(lyricResponse));
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Unable to fetch lyrics from " + mBaseUrl, e);
            return null;
        }
    }

    private JSONObject findBestSong(JSONObject response, String title, String artist,
            long durationMs) {
        JSONObject result = response.optJSONObject("result");
        JSONArray songs = result == null
                ? response.optJSONArray("songs") : result.optJSONArray("songs");
        if (songs == null) {
            return null;
        }

        JSONObject bestSong = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null) {
                continue;
            }
            String songTitle = song.optString("title", song.optString("name", ""));
            String songArtist = song.optString("artist", "");
            if (TextUtils.isEmpty(songArtist)) {
                songArtist = getArtistNames(song.optJSONArray("artists"));
            }
            String normalizedTitle = normalize(title);
            String normalizedSongTitle = normalize(songTitle);
            if (TextUtils.isEmpty(normalizedTitle) || TextUtils.isEmpty(normalizedSongTitle)
                    || (!normalizedTitle.contains(normalizedSongTitle)
                    && !normalizedSongTitle.contains(normalizedTitle))) {
                continue;
            }
            int score = 0;
            if (normalizedTitle.equals(normalizedSongTitle)) {
                score += 100;
            } else {
                score += 50;
            }
            if (!TextUtils.isEmpty(artist) && normalize(artist).equals(normalize(songArtist))) {
                score += 40;
            }
            long songDurationMs = song.optLong("durationMs", song.optLong("duration", 0));
            if (durationMs > 0 && songDurationMs > 0
                    && Math.abs(durationMs - songDurationMs) <= 10_000) {
                score += 20;
            }
            if (score > bestScore) {
                bestScore = score;
                bestSong = song;
            }
        }
        return bestSong;
    }

    private JSONObject normalizeLyricResponse(JSONObject response) throws JSONException {
        if (response.optJSONObject("lrc") != null || response.optJSONObject("tlyric") != null) {
            return response;
        }
        JSONObject normalized = new JSONObject();
        String original = response.optString("lyric", response.optString("original", ""));
        String translated = response.optString("translation",
                response.optString("translated", ""));
        normalized.put("lrc", new JSONObject().put("lyric", original));
        normalized.put("tlyric", new JSONObject().put("lyric", translated));
        return normalized;
    }

    private String getArtistNames(JSONArray artists) {
        if (artists == null) {
            return "";
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) {
                continue;
            }
            if (names.length() > 0) {
                names.append(' ');
            }
            names.append(artist.optString("name", ""));
        }
        return names.toString();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s]+", "");
    }

    private String encode(String value) throws IOException {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
    }
}
