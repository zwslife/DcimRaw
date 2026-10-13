package com.dcimgallery.raw.ui

import com.dcimgallery.raw.core.tone.ToneParams
import kotlin.math.abs

fun main() {
    var combinations = 0
    var controls = 0
    repeat(20) {
        for (section in listOf(0, 1, 2, 3, 4, 5, 2, 1, 4, 0)) {
            for (hsl in 0 until ToneParams.HSL_COUNT) for (grade in 0..3) for (bw in listOf(false, true)) {
                val params = ToneParams(blackWhite = bw)
                val entries = toneControls(section, hsl, grade, bw)
                check(entries.isNotEmpty())
                check(entries.map { it.id }.distinct().size == entries.size)
                for (entry in entries) {
                    check(entry.default in entry.range)
                    check(entry.get(params).isFinite())
                    check(entry.format(entry.get(params)).isNotBlank())
                    for (value in listOf(entry.range.start, entry.default, entry.range.endInclusive)) {
                        val edited = entry.set(params, value)
                        check(abs(entry.get(edited) - value) < 0.00001f) { "${entry.id}: getter/setter mismatch" }
                        check(entry.format(entry.get(edited)).isNotBlank())
                        check(edited.effectsArray().all { it.isFinite() })
                        check(edited.hslArray().all { it.isFinite() })
                    }
                    controls++
                }
                combinations++
            }
        }
    }
    println("PASS: $combinations repeated tab/channel/mode combinations; $controls controls and endpoint round trips")
}
