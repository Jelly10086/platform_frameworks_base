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

/** A source that resolves a media track to timestamped lyrics. */
interface LyricSource {
    Lyrics fetch(Track track);

    default Lyrics fetchEnhanced(Track track) {
        return null;
    }

    final class Track {
        final String packageName;
        final String mediaId;
        final String title;
        final String artist;
        final String album;
        final long durationMs;

        Track(String packageName, String mediaId, String title, String artist, String album,
                long durationMs) {
            this.packageName = packageName;
            this.mediaId = mediaId;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.durationMs = durationMs;
        }

        String getKey() {
            return String.valueOf(packageName) + "\u0000" + String.valueOf(mediaId)
                    + "\u0000" + String.valueOf(title) + "\u0000" + String.valueOf(artist)
                    + "\u0000" + String.valueOf(album) + "\u0000" + durationMs;
        }
    }

    final class Lyrics {
        private final java.util.TreeMap<Long, Cue> mCues;

        Lyrics(java.util.TreeMap<Long, Cue> cues) {
            mCues = cues;
        }

        Cue getCueAt(long positionMs) {
            java.util.Map.Entry<Long, Cue> entry = mCues.floorEntry(Math.max(0, positionMs));
            return entry == null ? null : entry.getValue();
        }

        boolean hasWordTiming() {
            for (Cue cue : mCues.values()) {
                if (cue.hasWordTiming()) {
                    return true;
                }
            }
            return false;
        }
    }

    final class Cue {
        final long timestampMs;
        final String text;
        final String translatedText;
        final java.util.List<Word> words;

        Cue(long timestampMs, String text, String translatedText) {
            this(timestampMs, text, translatedText, null);
        }

        Cue(long timestampMs, String text, String translatedText, java.util.List<Word> words) {
            this.timestampMs = timestampMs;
            this.text = text;
            this.translatedText = translatedText;
            this.words = words;
        }

        boolean hasWordTiming() {
            return words != null && !words.isEmpty();
        }
    }

    final class Word {
        final long beginMs;
        final long endMs;
        final String text;

        Word(long beginMs, long endMs, String text) {
            this.beginMs = beginMs;
            this.endMs = Math.max(beginMs, endMs);
            this.text = text;
        }
    }
}
