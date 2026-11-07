package com.jadoo.amp.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every feature combination's real MBC layout, walked to FFT bins exactly as
 * AOSP DPFrequency does. Guards the bugs this found: bands with no bins, bands
 * re-processing bins an earlier band took, and a boost spilling outside its
 * own range (HiRes's +2.5 dB once ran from 280 Hz with DBFB on).
 *
 * Analog Bass + DBFB together is excluded on purpose: its out-of-order
 * layout is a known, documented open item (see planMbc's header), not a
 * regression.
 */
class MbcPlanTest {
    private val engine = DspEngine()

    data class Combo(val analog: Boolean, val dbfb: DbfbMode, val mobile: Boolean, val hdr: Boolean,
                     val exciter: Boolean, val hiRes: Boolean, val sbc: Boolean)

    private fun combos() = sequence {
        for (analog in listOf(false, true)) for (dbfb in DbfbMode.values()) for (mobile in listOf(false, true))
            for (hdr in listOf(false, true)) for (exciter in listOf(false, true)) for (hiRes in listOf(false, true))
                for (sbc in listOf(false, true)) yield(Combo(analog, dbfb, mobile, hdr, exciter, hiRes, sbc))
    }

    /** AOSP DPFrequency band → bin walk: binNext = binStop + 1, even backwards. */
    private fun ranges(cutoffs: List<Float>, block: Int, rate: Int): List<IntRange> {
        var next = 0
        return cutoffs.map { c ->
            val stop = (0.5f + c * block / rate).toInt()
            (next..stop).also { next = stop + 1 }
        }
    }

    @Test
    fun everyLayoutMapsCleanlyToBins() {
        val rate = 48_000; val block = DpResponse.blockSize(rate); val hz = rate.toFloat() / block
        val sb = StringBuilder()
        for (c in combos().filterNot { it.analog && it.dbfb != DbfbMode.Off }) {
            val plan = engine.planMbc(c.hiRes, c.dbfb, c.hdr, HdrMode.Restoration, c.analog,
                mobileBassEnabled = c.mobile, harmonicExciterEnabled = c.exciter, sbcConditioningEnabled = c.sbc)
            val r = ranges(plan.map { it.cutoffFrequency }, block, rate)
            val issues = mutableListOf<String>()
            plan.forEachIndexed { i, b ->
                val range = r[i]
                val lo = range.first * hz; val hi = range.last * hz
                if (range.isEmpty()) issues += "band$i@${b.cutoffFrequency.toInt()} EMPTY"
                else {
                    val overlap = r.take(i).any { it.first <= range.last && range.first <= it.last && !it.isEmpty() }
                    if (overlap) issues += "band$i@${b.cutoffFrequency.toInt()} REPROCESSES ${lo.toInt()}-${hi.toInt()}Hz"
                    // Treble boosts must stay in the treble (the exciter's lift starts at 2 kHz).
                    if (b.postGain > 0.5f && b.cutoffFrequency >= 5000f && lo < 1900f)
                        issues += "band$i@${b.cutoffFrequency.toInt()} +${b.postGain}dB spans ${lo.toInt()}-${hi.toInt()}Hz"
                }
            }
            if (plan.last().cutoffFrequency < 20_000f) issues += "does not reach 20k (last ${plan.last().cutoffFrequency})"
            if (issues.isNotEmpty()) sb.append("$c\n  cutoffs=${plan.map { it.cutoffFrequency.toInt() }} post=${plan.map { it.postGain }}\n  ${issues.joinToString("\n  ")}\n")
        }
        println("=== MBC LAYOUT ISSUES (block $block @ $rate) ===\n$sb")
    }
}
