/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.systemui.lyric

import android.util.Base64
import com.android.systemui.CoreStartable
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import org.json.JSONObject
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/** Registers the manual lyric renderer command used during v2 development. */
class LyricDebugStartable @Inject constructor(
    private val commandRegistry: CommandRegistry,
) : CoreStartable {
    override fun start() {
        commandRegistry.registerCommand(COMMAND) { DebugCommand() }
    }

    private class DebugCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            if (args.isEmpty() || args[0] == "help") {
                help(pw)
                return
            }
            when (args[0]) {
                "sample" -> setLyrics(pw, SAMPLE_YRC)
                "clear" -> {
                    if (LyricViewController.setDebugLyrics(null)) {
                        pw.println("Lyric debug output cleared")
                    } else {
                        pw.println("No status bar lyric controller is attached")
                    }
                }
                "yrc" -> if (args.size == 2) {
                    try {
                        val raw = String(
                            Base64.decode(args[1], Base64.DEFAULT), StandardCharsets.UTF_8)
                        setLyrics(pw, raw)
                    } catch (e: IllegalArgumentException) {
                        pw.println("Invalid base64 YRC payload")
                    }
                } else {
                    pw.println("usage: lyric yrc <base64-yrc>")
                }
                else -> help(pw)
            }
        }

        override fun help(pw: PrintWriter) {
            pw.println("usage: cmd statusbar lyric <sample|yrc|clear>")
            pw.println("  sample                 Show a built-in word-timed lyric")
            pw.println("  yrc <base64-yrc>       Show a base64-encoded YRC payload")
            pw.println("  clear                  Return to the active media session")
        }

        private fun setLyrics(pw: PrintWriter, yrc: String) {
            val response = JSONObject()
                .put("lrc", JSONObject().put("lyric", "[00:00.00]debug"))
                .put("yrc", JSONObject().put("lyric", yrc))
            val lyrics = NetEaseLyricProvider.parseLyrics(response)
            if (lyrics == null || !lyrics.hasWordTiming()) {
                pw.println("Invalid YRC payload")
            } else if (LyricViewController.setDebugLyrics(lyrics)) {
                pw.println("Lyric debug output loaded")
            } else {
                pw.println("No status bar lyric controller is attached")
            }
        }
    }

    private companion object {
        const val COMMAND = "lyric"
        const val SAMPLE_YRC =
            "[0,4200](0,900,0)Word (900,900,0)timed (1800,1200,0)lyrics"
    }
}
