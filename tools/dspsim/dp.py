"""Line-by-line port of AOSP DPFrequency.cpp (+ LE) for offline A/B of JadOO chains.

Differences from the old sim.py, which matter:
  * sqrt-Hann analysis AND synthesis (AOSP), not Hann analysis / rect synthesis,
    so pre-echo and time aliasing come out right.
  * Limiter, MBC envelopes per block at the real hop.
  * PreEQ bins above the last cutoff keep factor 1.0, EQ acts on k < N/2 only.
"""
import numpy as np, av, pyloudnorm as pyln, math

EQ_CUT = [31.6, 50.2, 79.4, 126.5, 200, 316.2, 502, 794, 1265, 2000, 3162, 5020, 7937, 12649, 20000]


def load(path, secs=None, start=0, rate=None):
    c = av.open(path); s = c.streams.audio[0]; sr = rate or s.rate
    rs = av.AudioResampler(format='flt', layout='stereo', rate=sr)
    out = []
    for f in c.decode(s):
        for g in rs.resample(f):
            out.append(g.to_ndarray().reshape(-1, 2))
    x = np.concatenate(out).astype(np.float64)
    a = int(start * sr)
    return (x[a:a + int(secs * sr)] if secs else x[a:]), sr


def bin_stop(cut, block, sr):
    return int(0.5 + cut * block / sr)


def band_bins(cuts, block, sr):
    res, nxt = [], 0
    for c in cuts:
        stop = bin_stop(c, block, sr)
        res.append((nxt, stop))
        nxt = stop + 1
    return res


def eq_factors(gains, cuts, block, sr, base=1.0):
    """AOSP updateParameters: factor vector. base = inputGain linear for PreEQ, 1 for PostEQ."""
    half = block // 2 + 1
    f = np.ones(half)
    for (a, b), g in zip(band_bins(cuts, block, sr), gains):
        if b >= a:
            f[a:min(b, half - 1) + 1] = 10 ** (g / 20) * base
    return f


def mbc_band(cut, post=0., th=0., ratio=1., att=1., rel=60., knee=0., pre=0., gate=-90., exp=1.):
    return dict(cut=cut, post=post, th=th, ratio=ratio, att=att, rel=rel, knee=knee, pre=pre, gate=gate, exp=exp)


class Stats:
    def __init__(self):
        self.lim_gr = []
        self.mbc_gr = {}


def process(x, sr, block=512, pre_l=None, pre_r=None, in_gain=0., mbc=None, post_eq=None, lim=None, stats=None):
    """x: (n,2). mbc: list of mbc_band. post_eq: list[(cut,gain)]. lim: dict(th,ratio,att,rel,post) or None."""
    pre_l = pre_l if pre_l is not None else [0.] * 15
    pre_r = pre_r if pre_r is not None else pre_l
    hop = block // 2
    half = block // 2 + 1
    maxbin = block // 2
    # RDsp.h fillHanning at overlap N/2, then sqrt: analysis and synthesis
    w = np.sqrt(0.5 * (1 - np.cos(2 * np.pi * np.arange(block) / (block - 1))))  # RDsp.h fillHanning: symmetric
    wrms = max(math.sqrt(np.mean(w * w)), 1e-6)
    bps = sr / (block - hop)
    ig = 10 ** (in_gain / 20)
    pre = np.stack([eq_factors(pre_l, EQ_CUT, block, sr, ig), eq_factors(pre_r, EQ_CUT, block, sr, ig)])
    pe = None
    if post_eq:
        cuts = [c for c, _ in post_eq]
        pe = eq_factors([g for _, g in post_eq], cuts, block, sr)
    mbc = mbc or []
    mb = band_bins([m['cut'] for m in mbc], block, sr)
    env = np.zeros((len(mbc), 2))
    lenv = np.zeros(2)
    n = x.shape[0]
    nblocks = (n - block) // hop + 1
    y = np.zeros_like(x)
    norm = block * wrms
    # precompute per-band theta
    th_att = [math.exp(-1.0 / (m['att'] / 1000 * bps)) if m['att'] > 0 else 0. for m in mbc]
    th_rel = [math.exp(-1.0 / (m['rel'] / 1000 * bps)) if m['rel'] > 0 else 0. for m in mbc]
    pre2 = [10 ** (m['pre'] / 10) for m in mbc]
    postg = [10 ** (m['post'] / 20) for m in mbc]
    if lim:
        la = math.exp(-1.0 / (lim['att'] / 1000 * bps))
        lr = math.exp(-1.0 / (lim['rel'] / 1000 * bps))
        lpost = 10 ** (lim.get('post', 0.) / 20)
    else:
        lpost = 1.0
    for b in range(nblocks):
        seg = x[b * hop:b * hop + block].T * w
        S = np.fft.rfft(seg, axis=1)
        S[:, :maxbin] *= pre[:, :maxbin]
        for i, m in enumerate(mbc):
            a, c = mb[i]
            if c < a:
                # AOSP still runs the energy path on an empty range: energy 0, no bins change
                e = np.zeros(2)
            else:
                e = np.sqrt(np.sum(np.abs(S[:, a:c + 1]) ** 2, axis=1) * pre2[i] * 2) / norm
            for ch in (0, 1):
                th = th_att[i] if e[ch] > env[i, ch] else th_rel[i]
                fe = (1 - th) * e[ch] + th * env[i, ch]
                env[i, ch] = fe
                if fe < 1e-6:
                    fe = 1e-6
                ed = 20 * math.log10(fe)
                nl = ed
                t = m['th']; r = m['ratio']; kh = m['knee'] / 2
                if ed > t + kh:
                    nl = ed + (1 / r - 1) * (ed - t)
                elif ed > t - kh:
                    # kh==0 cannot reach here unless ed==t
                    tmp = ed - t + kh
                    nl = ed + (1 / r - 1) * tmp * tmp / (kh * 4) if kh > 0 else ed
                elif ed < m['gate']:
                    nl = m['gate'] - m['exp'] * (m['gate'] - ed)
                f = 10 ** ((nl - ed) / 20) * postg[i]
                if c >= a:
                    S[ch, a:c + 1] *= f
                if stats is not None and ch == 0:
                    stats.mbc_gr.setdefault(i, []).append(nl - ed)
        if pe is not None:
            S[:, :maxbin] *= pe[:maxbin]
        lf = 1.0
        if lim:
            e = np.sqrt(np.sum(np.abs(S[:, :maxbin]) ** 2, axis=1) * 2) / norm
            fac = [1.0, 1.0]
            for ch in (0, 1):
                th = la if e[ch] > lenv[ch] else lr
                fe = (1 - th) * e[ch] + th * lenv[ch]
                lenv[ch] = fe
                ed = 20 * math.log10(max(fe, 1e-12))
                if ed > lim['th']:
                    fac[ch] = 10 ** (((1 / lim['ratio']) - 1) * (ed - lim['th']) / 20)
            lf = min(fac)
            if stats is not None:
                stats.lim_gr.append(20 * math.log10(lf))
        of = lf * lpost
        if of != 1.0:
            S[:, :maxbin] *= of
        o = np.fft.irfft(S, block, axis=1) * w
        y[b * hop:b * hop + block] += o.T
    return y


def le(x, gain_db):
    """AOSP LoudnessEnhancer: gain then per-sample linked compressor, hard clip at full scale."""
    tg = 10 ** (gain_db / 20)
    knee_db = np.interp(tg, [1, 2, 3, 4, 5], [-8, -8, -8.5, -9, -10])
    knee = 0.1151292546 * knee_db + 10.39717719
    sr = SR
    aa, ar = math.exp(-1 / (0.001 * sr)), math.exp(-1 / (0.015 * sr))
    slope = 1 / 7 - 1
    y = x * tg * 32768
    mag = np.log(np.maximum(np.abs(y).max(axis=1), 0.032767))
    cv = np.maximum(mag - knee, 0) * slope
    st = 0.0
    n = len(y)
    state = np.empty(n)
    for i in range(n):
        c = cv[i]
        st = aa * st + (1 - aa) * c if c <= st else ar * st + (1 - ar) * c
        state[i] = st
    g = np.exp(state)  # compressor_gain_ telescopes to exp(state)
    out = np.clip(y * g[:, None], -32767, 32767)
    return out / 32768


SR = 44100
_meter = {}


def lufs(z, sr):
    m = _meter.setdefault(sr, pyln.Meter(sr))
    return m.integrated_loudness(z)


def band_db(z, sr, lo, hi):
    Z = np.fft.rfft(z[:, 0]); fr = np.fft.rfftfreq(len(z), 1 / sr)
    Z2 = np.fft.rfft(z[:, 1])
    sel = (fr > lo) & (fr < hi)
    return 10 * np.log10(np.sum(np.abs(Z[sel]) ** 2 + np.abs(Z2[sel]) ** 2) + 1e-20)


def hp_err_ratio(err, sig, sr, f0=2000.):
    E = np.fft.rfft(err[:, 0]); Sg = np.fft.rfft(sig[:, 0]); fr = np.fft.rfftfreq(len(err), 1 / sr)
    sel = fr > f0
    e = np.sum(np.abs(E[sel]) ** 2) + 1e-30
    s = np.sum(np.abs(Sg[sel]) ** 2) + 1e-30
    return 10 * np.log10(e / s)


def short_gain(y, x, sr, win=0.1):
    """Per-window gain of y vs x in dB on the louder half of x. Shows pumping."""
    n = int(win * sr); k = min(len(y), len(x)) // n
    a = np.sqrt(np.mean(y[:k * n].reshape(k, n, 2) ** 2, axis=(1, 2)) + 1e-20)
    b = np.sqrt(np.mean(x[:k * n].reshape(k, n, 2) ** 2, axis=(1, 2)) + 1e-20)
    g = 20 * np.log10(a / b)
    loud = b > np.median(b)
    return g[loud]


def report(name, y_raw, x, sr, stats=None, delay=None):
    # DP adds block latency only in real time; this port is zero-latency, so y aligns with x.
    pad = int(0.1 * sr)
    y_raw = y_raw[pad:-pad]; xr = x[pad:-pad]
    yc = np.clip(y_raw, -1, 1)
    err = y_raw - yc
    L = lufs(yc, sr) - lufs(xr, sr)
    pk = 20 * np.log10(np.abs(y_raw).max() + 1e-12)
    over = np.mean(np.abs(y_raw) > 1.0) * 100
    bass = band_db(yc, sr, 40, 150) - band_db(xr, sr, 40, 150)
    mid = band_db(yc, sr, 500, 4000) - band_db(xr, sr, 500, 4000)
    tre = band_db(yc, sr, 5000, 12000) - band_db(xr, sr, 5000, 12000)
    dist = 10 * np.log10(np.sum(err ** 2) / np.sum(yc ** 2) + 1e-30)
    dist_hf = hp_err_ratio(err, yc, sr)
    g = short_gain(yc, xr, sr)
    msg = (f"{name:44s} LU{L:+6.2f} bass{bass:+6.2f} mid{mid:+6.2f} tre{tre:+6.2f} pk{pk:+6.2f} over{over:6.3f}% "
           f"clip{dist:7.1f} hf{dist_hf:7.1f} pump{np.std(g):5.2f}")
    if stats is not None and stats.lim_gr:
        gr = np.array(stats.lim_gr)
        on = gr < -0.1
        msg += f" lim{on.mean() * 100:5.1f}% max{gr.min():+.1f}"
    print(msg, flush=True)
    return dict(L=L, bass=bass, mid=mid, tre=tre, pk=pk, over=over, dist=dist, dist_hf=dist_hf)


def bandpass(z, sr, lo, hi):
    Z = np.fft.rfft(z, axis=0); fr = np.fft.rfftfreq(len(z), 1 / sr)
    Z[(fr < lo) | (fr > hi)] = 0
    return np.fft.irfft(Z, len(z), axis=0)


def metrics(y_raw, x, sr):
    pad = int(0.1 * sr)
    y_raw = y_raw[pad:-pad]; xr = x[pad:-pad]
    yc = np.clip(y_raw, -1, 1); err = y_raw - yc
    m = dict(L=lufs(yc, sr) - lufs(xr, sr), bass=band_db(yc, sr, 40, 150) - band_db(xr, sr, 40, 150),
             mid=band_db(yc, sr, 500, 4000) - band_db(xr, sr, 500, 4000), tre=band_db(yc, sr, 5000, 12000) - band_db(xr, sr, 5000, 12000),
             pk=20 * np.log10(np.abs(y_raw).max() + 1e-12), over=np.mean(np.abs(y_raw) > 1.0) * 100,
             clip=10 * np.log10(np.sum(err ** 2) / np.sum(yc ** 2) + 1e-30), hf=hp_err_ratio(err, yc, sr))
    yb = bandpass(yc, sr, 40, 150); xb = bandpass(xr, sr, 40, 150)
    g = short_gain(yb, xb, sr, 0.05)
    m['bass_p5'] = float(np.percentile(g, 5)); m['bass_p50'] = float(np.percentile(g, 50)); m['bass_p95'] = float(np.percentile(g, 95))
    return m
