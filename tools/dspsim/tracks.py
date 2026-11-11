"""Loads the golden masters for the offline gate (docs/SOUND_REGRESSION_SOP.md).

Put five FLAC files in one folder, named exactly as below, and point
JADOO_MUSIC_DIR at it. Each track is cut to its loudest 25 s and its own peak
is scaled to -0.1 dBFS, which is Bluetooth full scale at its worst. Results are
cached as .npy in JADOO_DSPSIM_CACHE (default: the system temp folder).
"""
import os
import tempfile

import numpy as np

import dp

FILES = {
    'through': 'through.flac',
    'tobu': 'tobu.flac',
    'monica': 'monica.flac',
    'ishq': 'ishq.flac',
    'gehra': 'gehra.flac',
}
CACHE = os.environ.get('JADOO_DSPSIM_CACHE', os.path.join(tempfile.gettempdir(), 'jadoo_dspsim_cache'))


def get(name, secs=25, sr=44100):
    os.makedirs(CACHE, exist_ok=True)
    p = os.path.join(CACHE, f'{name}_{secs}_{sr}.npy')
    if os.path.exists(p):
        return np.load(p), sr
    music = os.environ.get('JADOO_MUSIC_DIR', '')
    if not music:
        raise SystemExit('Set JADOO_MUSIC_DIR to the folder with ' + ', '.join(FILES.values()))
    x, _ = dp.load(os.path.join(music, FILES[name]), rate=sr)
    # loudest window by 1 s RMS
    n = sr
    k = len(x) // n
    r = np.sqrt(np.mean(x[:k * n].reshape(k, n, 2) ** 2, axis=(1, 2)))
    c = np.convolve(r ** 2, np.ones(secs), 'valid')
    s = int(np.argmax(c))
    seg = x[s * n:(s + secs) * n].copy()
    pk = np.abs(x).max()
    seg = seg / pk * 10 ** (-0.1 / 20)  # the file's own peak -> -0.1 dBFS
    np.save(p, seg)
    return seg, sr
