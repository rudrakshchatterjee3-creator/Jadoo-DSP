"""JadOO feature definitions for dp.py. v165 = commit 4c76484 / HEAD; cur = working tree on 2026-10-30."""
import numpy as np, dp
from dp import mbc_band as B

W = np.array([1.0, 1.4, 1.6, 1.5, 1.2, 1.0, 1.0, 0.9, 0.9, 0.8, 0.7, 0.5, 0.35, 0.2, 0.08]); W = W / W.sum()
FLAT = [0.] * 15
WIDE = [2.0, 3.2, 3.0, 1.5, 0.5, 0, 0, -0.5, 0, 0, 0, 1.5, 3.0, 3.8, 4.5]
WIDE_D = [0.] * 11 + [1.0, -2.0, 2.5, -2.5]
TRAD = [1.5, 2.2, 2.0, 1.0, 0.3, 0, 0, -0.3, 0, 0, 0, 1.0, 2.0, 2.9, 3.6]
TRAD_D = [0.] * 11 + [1.0, -1.0, 1.5, -1.5]
SBC_NEW = [0.] * 10 + [-0.4, -0.8, 0.5, 2.0, -6.5]
SBC_CLASSIC_OLD = [0, 0, 1.2, 0.8, 0, 0, 0, 0, 0, -0.5, -1.2, -2.5, 1.5, 4.5, 7.0]
SBC_CLASSIC = [0, 0, 0, 0, 0, 0, 0, 0, 0, -0.5, -1.2, -2.5, 1.5, 4.5, 7.0]
HDR_AIR = [0.] * 13 + [0.6, 1.0]


def tube_pre(i):
    return [0, 0, 1.2 * i, 0.8 * i] + [0.] * 9 + [-1.2 * i, -2.5 * i]


def add(*curves):
    return [sum(v) for v in zip(*curves)]


def lr(center, diff=None):
    d = diff or FLAT
    return [c + x / 2 for c, x in zip(center, d)], [c - x / 2 for c, x in zip(center, d)]


def analog(e=1., drive=.4, warmth=.7, drift=.2):
    dg = drive ** .75 * 9 * e; wg = warmth * 3 * e; k = 8 + drift * 20
    return [B(60, wg + dg * .2, -24 + drive * 12, 1.8 + drive * 3.2, 8, 280, k, dg * .8, -85),
            B(120, wg * 1.5, -24, 1.2 + warmth * .8, 12, 240, k, wg * .5, -88),
            B(300, wg * .6, -20, 1.3 + drive * .3, 16, 200, k, wg * .3, -86)]


def analog_post(e=1., boost=.5, cut=.3, warmth=.7, fi=2):
    f = [20, 30, 60, 100][fi]
    return [(min(max(f * 1.5, 25), 200), boost * 8 * e), (min(max(f * 4, 80), 500), -cut * 5), (500, warmth * 2.5 * e), (20000, 0)]


def dbfb(high=True, e=1.):
    sub = (2.5 if high else 1.5) * e; punch = (1.4 if high else .8) * e
    return [B(72, sub, -20, 1.3, 22, 280, 8, 0, -80, 1.1), B(145, punch, -18, 1.5, 22, 280, 6, 0, -82, 1.05),
            B(260, -.6 if high else -.4, -18, 1.2, 22, 280, 6, 0, -85, 1)]


def hdr(cut, thr=-38, knee=14, exp=1.12):
    return [B(cut, 0, thr, 1, 30, 320, knee, 0, -90, exp)]


def exciter(hires, intensity=.5):
    return [B(5200 if hires else 8000, intensity * 5, -12, 1.3 + intensity * .4, 20, 150, 8, 0, -85)]


GUARD2K = [B(2000, 0, 0, 1, 5, 65)]


def hires(e=1., sbc=False, safety=True):
    s = [B(5200, 0, 0, 1, 9, 70)] if safety else []
    return s + [B(9600, 2.5 * e, 0, 1, 2, 50),
                B(14500, 4 * e * (.7 if sbc else 1), -24 if sbc else 0, 2.4 if sbc else 1, 1.5 if sbc else 2, 60 if sbc else 50, 6 if sbc else 0),
                B(20000, 5.5 * e * (.4 if sbc else 1), -26 if sbc else 0, 3.2 if sbc else 1, 1.5 if sbc else 2, 60 if sbc else 50, 6 if sbc else 0)]


def closing(db=False, sbc_cut=None):
    a, r = (6, 80) if db else (5, 65)
    res = [B(sbc_cut or 20000, 0, 0, 1, a, r)]
    if sbc_cut:
        res.append(B(20000, 0, -26, 3.2, 1.5, 60, 6, 0, -90))
    return res


def plan(hires_on=False, dbfb_mode=None, hdr_on=False, analog_on=False, exc=False, sbc_new=False, e=1., te=1., sr=44100,
         version='cur', intensity=.5):
    """planMbc() port. version 'v165' reproduces the Hi-Res/DBFB missing safety band."""
    b = []
    if analog_on:
        b += analog(e)
    if dbfb_mode:
        b += dbfb(dbfb_mode == 'high', e)
    crest = min(max(sr / 4, 9000), 16000) if sbc_new else None
    if hdr_on:
        if not hires_on and not exc:
            b += hdr(crest or 20000)
            if crest:
                b.append(B(20000, 0, -26, 3.2, 1.5, 60, 6, 0, -90))
            return b
        b += hdr(2000 if exc else 5200)
    if exc:
        if not hdr_on:
            b += GUARD2K
        b += exciter(hires_on, intensity)
    if hires_on:
        safety = (not hdr_on and not exc) and (version == 'cur' or not dbfb_mode)
        b += hires(te, sbc_new, safety)
        return b
    b += closing(bool(dbfb_mode), crest)
    return b


def budget_v165(dbfb_mode=None, analog_on=False, surround=None, hires_on=False, bass_peak=0., treble_peak=0., e=1., te=1.):
    bz = {'high': 3.9, 'normal': 2.3, None: 0.}[dbfb_mode] * e
    if analog_on:
        bz += 5 * e
    bz += {'wide': 5.5, 'trad': 3.5, 'front': 1.8, None: 0.}[surround]
    bz += max(bass_peak, 0)
    tz = 0.
    if hires_on:
        tz = max(tz, 5.5 * te)
    if surround in ('wide', 'trad'):
        tz = max(tz, {'wide': 4.5, 'trad': 3.6}[surround])
    tz = max(tz, treble_peak)
    return max(bz, tz)


def lim_v165(budget):
    """-0.3 threshold lowered by min(budget,2), +same as post gain. The default branch."""
    off = min(max(budget, 0), 2)
    return dict(th=-.3 - off, ratio=10, att=12, rel=90, post=off)


def lim_cur(budget):
    off = min(max(budget, 0), 2)
    return dict(th=-.3 - off, ratio=10, att=12, rel=90, post=0.)


def trim(pl, pr, stage=None):
    stage = stage or FLAT
    p = sum(W[i] * (10 ** ((pl[i] + stage[i]) / 10) + 10 ** ((pr[i] + stage[i]) / 10)) / 2 for i in range(15))
    return -round(max(0, min(12, 10 * np.log10(p))) * 10) / 10
