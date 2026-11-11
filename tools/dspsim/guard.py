"""Sound regression table (docs/SOUND_REGRESSION_SOP.md): feature sets and variants on the v1.6.9 Bluetooth plans. Run from anywhere; needs JADOO_MUSIC_DIR (see tracks.py)."""
import json, os, sys, copy, numpy as np, dp, codec, tracks, chains as C
from dp import mbc_band as B
from scipy.ndimage import minimum_filter1d
SR = 44100
HERE = os.path.dirname(os.path.abspath(__file__))
P = {p['name']: p for p in json.load(open(os.path.join(HERE, 'plans_guard.json')))}
HOT = [-8, -9, -10, -12, -16, -19, -26, -33]
NAMES = ['through', 'tobu', 'monica', 'ishq', 'gehra']
LIM = dict(th=-.3, ratio=10, att=12, rel=90, post=0)
X = {n: tracks.get(n)[0] for n in NAMES}
CODEC = '--codec' in sys.argv


def bands(p, nolift=False):
    out = []
    for z, b in enumerate(p['bands']):
        post, th, r = b['post'], b['th'], b['ratio']
        if nolift:
            span = 6. if r < 2.99 else None
            boost = (span * (1 - 1 / r)) if span else None
            if span is None:  # ratio 3: span = 1.5*boost
                boost = (th and None)
                # solve: span = 1.5*boost, ratio = 3 -> boost = span*(2/3); need p: th = HOT+p-span
                # post known; boost = post (+p for z!=3); p unknown -> use pre from curve: p = boost - post
                # from th: p - span = th - HOT ; with span=1.5*(p+post) (z!=3) -> p - 1.5p - 1.5post = th-HOT
                pz = 0. if z == 3 else (-(th - HOT[z]) - 1.5 * post) / 0.5
                boost = post + pz; span = 1.5 * boost
            pz = th - HOT[z] + span
            post -= 1.
            nb = boost - 1.
            if nb > .05:
                sp = max(6., nb * 1.5)
                th, r = HOT[z] + pz - sp, 1 / (1 - nb / sp)
            else:
                th, r = 0., 1.
        out.append(B(b['cut'], post, th, r, b['att'], b['rel'], b['knee']))
    return out


def ideal(y, ceil=1.0):
    """Look-ahead brickwall, 1.5 ms. NOT buildable on Android session-0 effects."""
    a = np.abs(y).max(1)
    g = np.minimum(1., ceil / np.maximum(a, 1e-9))
    w = 65
    g = minimum_filter1d(g, 2 * w + 1)
    k = np.hanning(w + 2)[1:-1]; k /= k.sum()
    g = np.convolve(g, k, 'same')
    return y * np.minimum(g, ceil / np.maximum(a, 1e-9))[:, None]


def run(name, var, x):
    p = P[name]
    pe = C.analog_post(p['e'], p['pb'], p['pc'], p['w'], p['fi']) if p['analog'] else None
    ig = p['trim']
    z = x
    if var in ('B', 'D'): z = dp.le(x, 0.)
    if var == 'C1': ig -= 1
    if var == 'C2': ig -= 2
    if var == 'D': ig -= 1
    y = dp.process(z, SR, 2048, p['left'], p['right'], ig, bands(p, var == 'E'), pe, LIM)
    if var == 'REF': y = ideal(y)
    return y


def met(y, x):
    m = dp.metrics(y, x, SR)
    pad = int(.1 * SR); yr = y[pad:-pad]; yc = np.clip(yr, -1, 1); e = yr - yc
    m['mask'] = codec.masked(e, yc, SR)[0] if np.any(e) else 0.
    return m


VARS = ['A', 'B', 'C1', 'C2', 'D', 'E', 'REF']
K = ['L', 'mid', 'bass_p5', 'pk', 'over', 'clip', 'mask']


def table(sets, level):
    print(f'\n===== {level}: mean / worst over 5 masters (worst = max for pk/over/clip/mask, min for L/mid/bass) =====')
    print('%-14s %-4s' % ('set', 'var') + ''.join('%15s' % k for k in K))
    for s in sets:
        for v in VARS:
            rows = []
            for n in NAMES:
                x = X[n]
                if level != 'hot': x = x * 10 ** ((-14 - dp.lufs(x, SR)) / 20)
                rows.append(met(run(s, v, x), x))
            out = ''
            for k in K:
                a = np.array([r[k] for r in rows])
                w = a.min() if k in ('L', 'mid', 'bass_p5') else a.max()
                out += '%8.2f/%6.2f' % (a.mean(), w)
            print('%-14s %-4s' % (s, v) + out, flush=True)


if __name__ == '__main__':
    print('plans:', {k: [(round(b['post'], 2), round(b['th'], 1), round(b['ratio'], 2)) for b in v['bands']] for k, v in P.items() if k == 'Heavy'})
    print('E heavy:', [(round(b['post'], 2), round(b['th'], 1), round(b['ratio'], 2)) for b in bands(P['Heavy'], True)])
    print('E default:', [(round(b['post'], 2), round(b['th'], 1), round(b['ratio'], 2)) for b in bands(P['Default'], True)])
    print('\n===== LE guard alone (0 dB), no DP, vs bypass: transparency =====')
    for n in NAMES:
        for lvl in ('hot', '-14'):
            x = X[n] if lvl == 'hot' else X[n] * 10 ** ((-14 - dp.lufs(X[n], SR)) / 20)
            y = dp.le(x, 0.); m = dp.metrics(y, x, SR)
            gr = 20 * np.log10(np.maximum(np.abs(y).max(1), 1e-9) / np.maximum(np.abs(x).max(1), 1e-9))
            act = np.abs(x).max(1) > 10 ** (-40 / 20)
            res = 10 * np.log10(np.sum((y - x) ** 2) / np.sum(x ** 2))
            print(f'{n:8s} {lvl:4s} inPk {20*np.log10(np.abs(x).max()):+6.2f} outPk {m["pk"]:+6.2f} LU {m["L"]:+5.2f} bass {m["bass"]:+5.2f} mid {m["mid"]:+5.2f} tre {m["tre"]:+5.2f} '
                  f'bassP5 {m["bass_p5"]:+5.2f} GR>0.5dB {np.mean(gr[act] < -.5)*100:5.1f}% GRmax {gr[act].min():+5.2f} residual {res:6.1f} dB', flush=True)
    if not CODEC:
        table(['Heavy', 'Analog+DBFB N', 'BassEQ4', 'Default'], 'hot')
        table(['Heavy', 'Analog+DBFB N'], '-14 LUFS')
    else:
        print('\n===== codecs (hot masters): decoded full-scale samples and decoded peak; mean/worst over 5 masters =====')
        for cod, rate in (('sbc', 328000), ('aptx', 352800), ('aac', 256000)):
            res = {}
            for s in ['bypass', 'Heavy', 'Analog+DBFB N']:
                for v in (['-'] if s == 'bypass' else VARS):
                    fs = []; pk = []; L = []
                    for n in NAMES:
                        y = X[n] if s == 'bypass' else run(s, v, X[n])
                        d, r, lag = codec.roundtrip(y, SR, cod, rate)
                        fs.append(int(np.sum(np.abs(d) >= 32767 / 32768))); pk.append(20 * np.log10(np.abs(d).max() + 1e-12))
                    print(f'{cod:5s} {s:14s} {v:4s} FS samples mean {np.mean(fs):7.1f} worst {max(fs):6d} | dec peak mean {np.mean(pk):+6.2f} worst {max(pk):+6.2f}', flush=True)
