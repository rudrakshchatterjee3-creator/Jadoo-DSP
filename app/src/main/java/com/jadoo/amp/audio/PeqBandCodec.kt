package com.jadoo.amp.audio

/**
 * Version-tolerant PEQ persistence codec.
 *
 * The delimiter format is retained so existing profiles and backups remain
 * compatible. Decoding always returns a complete 16-band bank: old 8-band
 * profiles populate the first eight slots and receive eight disabled defaults.
 */
object PeqBandCodec {
    fun encode(bands: List<DigitalFilterEngine.FilterBand>): String =
        (0 until DigitalFilterEngine.MAX_BANDS).joinToString("|") { index ->
            val band = DigitalFilterEngine.sanitizeBand(
                bands.getOrElse(index) { DigitalFilterEngine.defaultBand(index) },
                index
            )
            "${band.type.name},${band.frequencyHz},${band.gainDb},${band.q},${band.enabled}"
        }

    fun decode(serialized: String): List<DigitalFilterEngine.FilterBand> {
        val result = MutableList(DigitalFilterEngine.MAX_BANDS) { index ->
            DigitalFilterEngine.defaultBand(index)
        }
        if (serialized.isBlank()) return result

        serialized.split('|').take(DigitalFilterEngine.MAX_BANDS).forEachIndexed { index, part ->
            val fields = part.split(',')
            if (fields.size < 5) return@forEachIndexed
            val type = DigitalFilterEngine.FilterType.entries
                .firstOrNull { it.name == fields[0] } ?: DigitalFilterEngine.FilterType.Peak
            val parsed = DigitalFilterEngine.FilterBand(
                enabled = fields[4].toBooleanStrictOrNull() ?: false,
                type = type,
                frequencyHz = fields[1].toFloatOrNull()
                    ?: DigitalFilterEngine.DEFAULT_FREQUENCIES_HZ[index],
                gainDb = fields[2].toFloatOrNull() ?: 0f,
                q = fields[3].toFloatOrNull() ?: 1f
            )
            result[index] = DigitalFilterEngine.sanitizeBand(parsed, index)
        }
        return result
    }
}
