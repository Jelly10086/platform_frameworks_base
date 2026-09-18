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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches and parses lyrics from the public NetEase Cloud Music endpoints. */
final class NetEaseLyricProvider implements LyricSource {
    private static final String TAG = "NetEaseLyricProvider";
    private static final String SEARCH_ENDPOINT =
            "https://music.163.com/api/search/get/web?s=%s&type=1&offset=0&total=true&limit=10";
    private static final String LYRIC_ENDPOINT =
            "https://music.163.com/api/song/lyric?os=pc&id=%d&lv=-1&tv=-1";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int MAX_RESPONSE_SIZE = 2 * 1024 * 1024;
    private static final int MAX_LYRIC_CANDIDATES = 3;
    private static final long MAX_TRANSLATION_DELTA_MS = 1_000;
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "\\[(\\d{1,3}):(\\d{1,2})(?:\\.(\\d{1,3}))?\\]");
    private static final Pattern OFFSET_PATTERN = Pattern.compile("\\[offset:([+-]?\\d+)\\]");

    NetEaseLyricProvider() {
    }

    @Override
    public LyricSource.Lyrics fetch(String title, String artist, long durationMs) {
        if (TextUtils.isEmpty(title)) {
            return null;
        }

        try {
            String query = TextUtils.isEmpty(artist) ? title : title + " " + artist;
            String searchJson = request(String.format(Locale.ROOT, SEARCH_ENDPOINT,
                    URLEncoder.encode(query, StandardCharsets.UTF_8.name())));
            List<JSONObject> songs = findMatchingSongs(
                    new JSONObject(searchJson), title, artist, durationMs);
            if (songs.isEmpty()) {
                return null;
            }

            for (int i = 0; i < Math.min(songs.size(), MAX_LYRIC_CANDIDATES); i++) {
                JSONObject song = songs.get(i);
                long songId = song.optLong("id", 0);
                if (songId == 0) {
                    continue;
                }
                String lyricJson = request(String.format(Locale.ROOT, LYRIC_ENDPOINT, songId));
                LyricSource.Lyrics lyrics = parseLyrics(new JSONObject(lyricJson));
                if (lyrics != null) {
                    return lyrics;
                }
            }
            return null;
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Unable to fetch lyrics for " + title, e);
            return null;
        }
    }

    private static List<JSONObject> findMatchingSongs(JSONObject response, String title,
            String artist, long durationMs) throws JSONException {
        JSONObject result = response.optJSONObject("result");
        if (result == null) {
            return new ArrayList<>();
        }
        JSONArray songs = result.optJSONArray("songs");
        if (songs == null) {
            return new ArrayList<>();
        }

        ArrayList<JSONObject> matchingSongs = new ArrayList<>();
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null || !isTitleMatch(song.optString("name", ""), title)) {
                continue;
            }
            matchingSongs.add(song);
        }
        matchingSongs.sort((first, second) -> Integer.compare(
                scoreSong(second, title, artist, durationMs),
                scoreSong(first, title, artist, durationMs)));
        return matchingSongs;
    }

    private static boolean isTitleMatch(String candidate, String requested) {
        String normalizedCandidate = normalize(candidate);
        String normalizedRequested = normalize(requested);
        return !TextUtils.isEmpty(normalizedCandidate) && !TextUtils.isEmpty(normalizedRequested)
                && (normalizedCandidate.contains(normalizedRequested)
                || normalizedRequested.contains(normalizedCandidate));
    }

    private static int scoreSong(JSONObject song, String title, String artist, long durationMs) {
        String songTitle = song.optString("name", "");
        String songArtist = getArtistNames(song.optJSONArray("artists"));
        String normalizedTitle = normalize(title);
        String normalizedSongTitle = normalize(songTitle);
        String normalizedArtist = normalize(artist);
        String normalizedSongArtist = normalize(songArtist);
        int score = 0;

        if (TextUtils.equals(normalizedTitle, normalizedSongTitle)) {
            score += 100;
        } else if (normalizedSongTitle.contains(normalizedTitle)
                || normalizedTitle.contains(normalizedSongTitle)) {
            score += 50;
        }
        if (!TextUtils.isEmpty(normalizedArtist)) {
            if (TextUtils.equals(normalizedArtist, normalizedSongArtist)) {
                score += 40;
            } else if (normalizedSongArtist.contains(normalizedArtist)
                    || normalizedArtist.contains(normalizedSongArtist)) {
                score += 20;
            }
        }
        long songDurationMs = song.optLong("duration", 0);
        if (durationMs > 0 && songDurationMs > 0) {
            long differenceMs = Math.abs(durationMs - songDurationMs);
            if (differenceMs <= 5_000) {
                score += 25;
            } else if (differenceMs <= 15_000) {
                score += 5;
            }
        }
        return score;
    }

    private static String getArtistNames(JSONArray artists) {
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

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", "");
    }

    static LyricSource.Lyrics parseLyrics(JSONObject response) {
        JSONObject lrc = response.optJSONObject("lrc");
        JSONObject translatedLrc = response.optJSONObject("tlyric");
        String original = lrc == null ? null : lrc.optString("lyric", null);
        String translated = translatedLrc == null ? null : translatedLrc.optString("lyric", null);
        if (TextUtils.isEmpty(original) && TextUtils.isEmpty(translated)) {
            return null;
        }

        TreeMap<Long, String> originalLines = parseLrc(original, getOffsetMs(original));
        TreeMap<Long, String> translatedLines = parseLrc(translated, getOffsetMs(translated));
        TreeMap<Long, LyricSource.Cue> cues = new TreeMap<>();
        if (originalLines.isEmpty()) {
            for (Map.Entry<Long, String> entry : translatedLines.entrySet()) {
                cues.put(entry.getKey(), new LyricSource.Cue(
                        entry.getKey(), entry.getValue(), null));
            }
            return cues.isEmpty() ? null : new LyricSource.Lyrics(cues);
        }
        for (Map.Entry<Long, String> entry : originalLines.entrySet()) {
            cues.put(entry.getKey(), new LyricSource.Cue(entry.getKey(), entry.getValue(),
                    findClosestLine(translatedLines, entry.getKey())));
        }
        return cues.isEmpty() ? null : new LyricSource.Lyrics(cues);
    }

    private static String findClosestLine(TreeMap<Long, String> lines, long timestampMs) {
        Map.Entry<Long, String> floor = lines.floorEntry(timestampMs);
        Map.Entry<Long, String> ceiling = lines.ceilingEntry(timestampMs);
        Map.Entry<Long, String> closest;
        if (floor == null) {
            closest = ceiling;
        } else if (ceiling == null) {
            closest = floor;
        } else {
            closest = timestampMs - floor.getKey() <= ceiling.getKey() - timestampMs
                    ? floor : ceiling;
        }
        return closest != null && Math.abs(closest.getKey() - timestampMs)
                <= MAX_TRANSLATION_DELTA_MS ? closest.getValue() : null;
    }

    private static long getOffsetMs(String lyric) {
        if (lyric == null) {
            return 0;
        }
        Matcher matcher = OFFSET_PATTERN.matcher(lyric);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static TreeMap<Long, String> parseLrc(String lyric, long offsetMs) {
        TreeMap<Long, String> lines = new TreeMap<>();
        if (TextUtils.isEmpty(lyric)) {
            return lines;
        }
        for (String line : lyric.split("\\r?\\n")) {
            Matcher matcher = TIMESTAMP_PATTERN.matcher(line);
            TreeMap<Long, Boolean> timestamps = new TreeMap<>();
            while (matcher.find()) {
                long timestampMs = parseTimestampMs(matcher);
                if (timestampMs >= 0) {
                    timestamps.put(Math.max(0, timestampMs + offsetMs), true);
                }
            }
            if (!timestamps.isEmpty()) {
                int textStart = 0;
                Matcher prefixMatcher = TIMESTAMP_PATTERN.matcher(line);
                while (prefixMatcher.find()) {
                    textStart = prefixMatcher.end();
                }
                String lineText = textForCue(line.substring(textStart));
                if (!TextUtils.isEmpty(lineText)) {
                    for (Long timestampMs : timestamps.keySet()) {
                        lines.put(timestampMs, lineText);
                    }
                }
            }
        }
        return lines;
    }

    private static long parseTimestampMs(Matcher matcher) {
        try {
            long minutes = Long.parseLong(matcher.group(1));
            long seconds = Long.parseLong(matcher.group(2));
            if (seconds >= 60) {
                return -1;
            }
            String fraction = matcher.group(3);
            long milliseconds = fraction == null ? 0 : Long.parseLong(
                    (fraction + "00").substring(0, 3));
            return minutes * 60_000 + seconds * 1_000 + milliseconds;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String textForCue(String text) {
        text = text.trim();
        if (text.matches("(?i)^(lyricist|composer|arranger|written by|music)"
                + "\\s*(:|\\uFF1A).*")) {
            return "";
        }
        if (text.matches("^(\\u4F5C\\u8BCD|\\u4F5C\\u66F2|\\u7F16\\u66F2|"
                + "\\u5236\\u4F5C\\u4EBA|\\u6F14\\u5531)\\s*(:|\\uFF1A).*")) {
            return "";
        }
        return text;
    }

    static String request(String endpoint) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Request interrupted");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "uwuAOSP-SystemUI-Lyric/1.0");
        try {
            int status = connection.getResponseCode();
            if (status < HttpURLConnection.HTTP_OK || status >= HttpURLConnection.HTTP_MULT_CHOICE) {
                throw new IOException("HTTP status " + status);
            }
            return readResponse(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static String readResponse(InputStream inputStream) throws IOException {
        StringBuilder response = new StringBuilder();
        int responseSize = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Request interrupted");
                }
                responseSize += line.length();
                if (responseSize > MAX_RESPONSE_SIZE) {
                    throw new IOException("Response is too large");
                }
                response.append(line).append('\n');
            }
        }
        return response.toString();
    }

}
