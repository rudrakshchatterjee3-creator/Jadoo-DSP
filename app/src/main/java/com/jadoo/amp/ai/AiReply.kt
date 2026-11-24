package com.jadoo.amp.ai

import com.jadoo.amp.audio.Taste

/**
 * What JadOO AI is asked and how its answer is read. Pure Kotlin, unit tested
 * in AiReplyTest; the model itself runs in [JadooAi].
 *
 * The model turns whatever the user writes (their day, their music, what
 * bothers them, in any language) into a starting nudge of -3..+3 dB for each
 * taste dimension. It only sets where the A/B choices start: the choices
 * decide, so a misreading costs a question or two, never the result.
 */
object AiReply {

    data class Interpretation(val deltasDb: FloatArray, val stereo: Boolean, val summary: String) {
        val understood: List<Pair<Taste.Dim, Int>>
            get() = Taste.DIMS.filter { kotlin.math.abs(deltasDb[it.ordinal]) >= 0.5f }
                .map { it to if (deltasDb[it.ordinal] > 0) 1 else -1 }
    }

    const val MAX_INPUT_CHARS = 600

    val SYSTEM = """
You are the sound-taste interpreter inside JadOO, a music app. The user writes about their life, habits, music and what they like or dislike about sound, in any language. Turn it into starting adjustments for four parts of the sound. Each is an integer from -3 to 3; use 0 when the text says nothing about it.
bass: weight, punch and depth (below 150 Hz).
warmth: body and fullness (around 300 Hz). Muddy, boomy or boxy means lower; thin means higher.
clarity: voices, attack and detail (around 3 kHz). Harsh, shouty or tiring means lower; dull, veiled or unclear voices means higher.
air: cymbals, sparkle and space (above 8 kHz). Sibilant or piercing means lower; dull or closed-in means higher.
stereo: true if they mention stereo width, separation, soundstage or space between instruments.
summary: one short sentence in the user's language saying what you will change.
Reason from their life as well: noisy commutes and gyms favour more bass and clarity; headaches, long sessions or sensitivity to loud sound favour less clarity and air; vocals, podcasts and audiobooks favour clarity; electronic, hip-hop and film music favour bass; classical, jazz and acoustic favour warmth and natural air.
Reply with one JSON object only, with the keys bass, warmth, clarity, air, stereo, summary.
""".trim()

    /** Worked examples the model sees before the user's text. */
    val EXAMPLES: List<Pair<String, String>> = listOf(
        "too much bass and the singers sound far away" to
            """{"bass":-2,"warmth":-1,"clarity":2,"air":0,"stereo":false,"summary":"Less bass and clearer, closer voices."}""",
        "I take the metro every day and mostly listen to hip hop. I want to feel it." to
            """{"bass":2,"warmth":1,"clarity":1,"air":0,"stereo":false,"summary":"More bass and punch that cuts through the train."}""",
        "Nach langen Tagen bekomme ich Kopfschmerzen, alles klingt zu scharf." to
            """{"bass":0,"warmth":1,"clarity":-2,"air":-2,"stereo":false,"summary":"Weicher und weniger scharf, damit es entspannt bleibt."}""",
    )

    /** JSON schema for constrained decoding, where the runtime supports it. */
    val SCHEMA = """
{"type":"object","properties":{"bass":{"type":"integer","minimum":-3,"maximum":3},"warmth":{"type":"integer","minimum":-3,"maximum":3},"clarity":{"type":"integer","minimum":-3,"maximum":3},"air":{"type":"integer","minimum":-3,"maximum":3},"stereo":{"type":"boolean"},"summary":{"type":"string"}},"required":["bass","warmth","clarity","air","stereo","summary"]}
""".trim()

    private val THINK = Regex("(?s)<think>.*?</think>")
    private fun num(key: String) = Regex("\"$key\"\\s*:\\s*\"?(-?\\d+(?:\\.\\d+)?)")
    private val STEREO = Regex("\"stereo\"\\s*:\\s*\"?(true|false)", RegexOption.IGNORE_CASE)
    private val SUMMARY = Regex("\"summary\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /**
     * Reads the model's reply. Tolerant of the ways a small model goes wrong:
     * a thinking block, text around the JSON, numbers as strings, values out
     * of range. Null when no dimension at all can be found.
     */
    fun parse(reply: String): Interpretation? {
        val text = reply.replace(THINK, "")
        val keys = listOf("bass", "warmth", "clarity", "air")
        val values = keys.map { k -> num(k).find(text)?.groupValues?.get(1)?.toFloatOrNull() }
        if (values.all { it == null }) return null
        val deltas = FloatArray(Taste.DIMS.size) { i -> (values[i] ?: 0f).coerceIn(-3f, 3f) }
        val stereo = STEREO.find(text)?.groupValues?.get(1)?.equals("true", ignoreCase = true) == true
        val summary = SUMMARY.find(text)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\n", " ")?.trim()?.take(160) ?: ""
        return Interpretation(deltas, stereo, summary)
    }
}
