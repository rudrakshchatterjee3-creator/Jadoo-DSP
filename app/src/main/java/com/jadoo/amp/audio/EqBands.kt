package com.jadoo.amp.audio

object EqBands {
    // Centre frequencies shown in the UI
    val frequencies = floatArrayOf(
        25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f,
        1000f, 1600f, 2500f, 4000f, 6300f, 10000f, 16000f
    )

    // Upper-edge (cutoff) frequencies fed to DynamicsProcessing.Eq bands.
    // Each band is centred on the corresponding centre frequency; the cutoff is
    // the geometric mean of this centre and the next, so the gain applies at
    // exactly the right frequency rather than being offset by half an octave.
    val cutoffFrequencies = floatArrayOf(
        31.6f, 50.2f, 79.4f, 126.5f, 200f, 316.2f,
        502f, 794f, 1265f, 2000f, 3162f, 5020f, 7937f, 12649f, 20000f
    )

    val labels = listOf(
        "25", "40", "63", "100", "160", "250", "400", "630",
        "1k", "1.6k", "2.5k", "4k", "6.3k", "10k", "16k"
    )

    const val count = 15
}
