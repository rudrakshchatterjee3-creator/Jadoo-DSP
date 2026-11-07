"""Real Bluetooth codecs through FFmpeg (PyAV): encode + decode, delay-align, measure."""
import numpy as np, av, math
from fractions import Fraction


def to_s16(y):
    """What the HAL hands the encoder: hard clip, 16-bit."""
    return np.clip(np.round(y * 32767.0), -32768, 32767).astype(np.int16)


def roundtrip(y, sr, name='sbc', bit_rate=328000):
    pcm = to_s16(y)
    enc = av.CodecContext.create(name, 'w')
    enc.sample_rate = sr
    enc.layout = 'stereo'
    fmt = {'sbc': 's16', 'aptx': 's32p', 'aptx_hd': 's32p', 'aac': 'fltp'}[name]
    enc.format = fmt
    enc.bit_rate = bit_rate
    enc.time_base = Fraction(1, sr)
    enc.open()
    fs = enc.frame_size or 1024
    packets = []
    n = (len(pcm) // fs) * fs
    for i in range(0, n, fs):
        chunk = pcm[i:i + fs]
        if fmt == 's16':
            f = av.AudioFrame.from_ndarray(chunk.reshape(1, -1), format='s16', layout='stereo')
        elif fmt == 's32p':
            f = av.AudioFrame.from_ndarray(np.ascontiguousarray(chunk.T.astype(np.int32) * 65536), format='s32p', layout='stereo')
        else:
            f = av.AudioFrame.from_ndarray(np.ascontiguousarray((chunk.T / 32768.0).astype(np.float32)), format='fltp', layout='stereo')
        f.sample_rate = sr
        f.pts = i
        packets += enc.encode(f)
    packets += enc.encode(None)
    dec = av.CodecContext.create(name, 'r')
    dec.sample_rate = sr
    dec.layout = 'stereo'
    if name == 'aac':
        dec.extradata = enc.extradata
    out = []
    for p in packets:
        for fr in dec.decode(p):
            a = fr.to_ndarray()
            if fr.format.name == 's16':
                a = a.reshape(-1, 2).astype(np.float64) / 32768.0
            elif fr.format.name in ('s32p',):
                a = a.T.astype(np.float64) / 2147483648.0
            elif fr.format.name in ('fltp',):
                a = a.T.astype(np.float64)
            elif fr.format.name in ('s16p',):
                a = a.T.astype(np.float64) / 32768.0
            else:
                raise RuntimeError(fr.format.name)
            out.append(a)
    d = np.concatenate(out)
    x = pcm[:n].astype(np.float64) / 32768.0
    # delay by cross-correlation on a mid segment, FFT based
    m = min(len(d), len(x), sr * 8)
    a = x[sr:sr + m // 2, 0]
    b = d[sr:sr + m // 2 + 8192, 0]
    L = 1 << int(math.ceil(math.log2(len(a) + len(b))))
    c = np.fft.irfft(np.fft.rfft(b, L) * np.conj(np.fft.rfft(a, L)), L)
    lag = int(np.argmax(c[:8192]))
    d = d[lag:]
    k = min(len(d), len(x))
    return d[:k], x[:k], lag


def bands_snr(dec, ref, sr, edges=((20, 300), (300, 2000), (2000, 6000), (6000, 12000), (12000, 20000))):
    e = dec - ref
    E = np.fft.rfft(e, axis=0); R = np.fft.rfft(ref, axis=0); fr = np.fft.rfftfreq(len(e), 1 / sr)
    res = []
    for lo, hi in edges:
        s = (fr >= lo) & (fr < hi)
        res.append(10 * np.log10((np.sum(np.abs(R[s]) ** 2) + 1e-20) / (np.sum(np.abs(E[s]) ** 2) + 1e-20)))
    return res


# --- crude masking model: is an error signal above what the music masks? ---
def bark(f):
    return 13 * np.arctan(0.00076 * f) + 3.5 * np.arctan((f / 7500.0) ** 2)


def masked(err, sig, sr, N=2048, offset_db=14.0):
    """Fraction of frames where the error pokes above a simple masking threshold in any Bark band.
    Spreading -25 dB/Bark below, -10 dB/Bark above the masker. Mono sum of channels' power."""
    w = np.hanning(N); hop = N // 2
    fr = np.fft.rfftfreq(N, 1 / sr); bk = np.floor(bark(fr)).astype(int); nb = 25
    nf = (len(sig) - N) // hop
    idx = np.arange(nb)
    sp = np.where(idx[:, None] >= idx[None, :], -10.0 * (idx[:, None] - idx[None, :]), -25.0 * (idx[None, :] - idx[:, None]))  # to, from
    spread = 10 ** (sp / 10)
    audible = 0; over = []
    any_err = 0
    for i in range(nf):
        s = sig[i * hop:i * hop + N]; e = err[i * hop:i * hop + N]
        if not np.any(e):
            continue
        any_err += 1
        S = np.sum(np.abs(np.fft.rfft(s * w[:, None], axis=0)) ** 2, axis=1)
        E = np.sum(np.abs(np.fft.rfft(e * w[:, None], axis=0)) ** 2, axis=1)
        sb = np.bincount(bk, S, nb); eb = np.bincount(bk, E, nb)
        mask = spread @ sb * 10 ** (-offset_db / 10)
        nmr = 10 * np.log10((eb + 1e-30) / (mask + 1e-30))
        m = nmr[:24].max()
        if m > 0:
            audible += 1; over.append(m)
    return audible / max(nf, 1) * 100, any_err / max(nf, 1) * 100, (float(np.mean(over)) if over else 0.0)
