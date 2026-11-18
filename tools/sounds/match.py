#!/usr/bin/env python3
"""Identify where every app sound came from, and how it was cut, by spectral matching.

Inputs
  --ref      folder of the processed app clips as built into 5.2 (<id>.ogg, i.e. the personal
             res/raw folder). Local reference only; never committed.
  --crawl    output folder of crawl.py (one sub-folder + catalog.json per site).
  --src      Kotlin source folder(s) holding Sfx.kt and SourceSoundLibrary.kt (they give the ids,
             labels and the original file names). Repeatable; default core/ and app/
             src/main/kotlin/com/gungreeneyes/trekui. --java is accepted as an alias.
  --history  _sources/history-docs/SOUND_MANIFEST-5.x.md (local only; gives SHA-256 prefixes of the source originals).
  --out      writes match_report.json (every candidate score) and manifest.json (the app asset).

Method
  1. Decode every clip and every crawled file with ffmpeg to mono float32 at 22.05 kHz.
  2. Features: 48-band log-mel spectrogram (hop 256 = 11.6 ms), floored at peak-80 dB, with the
     per-frame mean removed. A gain change is a constant offset in log-mel, so removing the
     frame mean makes the features gain-invariant; only spectral shape over time is compared.
  3. Coarse search: all candidate feature matrices are concatenated and the app clip is slid over
     the whole thing with FFT cross-correlation (normalised by the window energy), so every offset
     in every file is scored in one pass. A filename / duration prefilter only decides which
     files take part, it never decides the winner.
  4. Fine search: around the best frames of the best few files, normalised waveform
     cross-correlation at sample resolution (+-2 frames) gives the exact start, and RMS of clip vs
     source segment gives the gain in dB.
  5. Confidence = waveform correlation at the best lag (1.0 = identical). >= 0.90 is "high",
     >= 0.75 "medium", else the sound is marked unavailable.

    python -I tools/sounds/match.py --ref <dir> --crawl <dir> [--src <dir>] --history <md> --out <dir>
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I leaves the script folder off sys.path
import kt_source  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

SR = 22050
FINE_SR = 48000  # the 5.x pipeline decoded every original with ffmpeg -ar 48000
HOP = 256
NFFT = 1024
NMEL = 48
HIGH, MEDIUM = 0.90, 0.75
CONFIRMED = 0.95  # a named / hashed file at least this close ends the search

# Preferred download source when the same audio is hosted in several places (first = preferred).
SITE_RANK = ['trekcore', 'github_sound_pack', 'mediacollege', 'willbswift', 'slspencer']


# ---------------------------------------------------------------- decoding / features

def decode(path, sr=SR, channels=1):
    """ffmpeg -> float32 numpy (mono, or (n, ch) when channels > 1). Empty array on failure."""
    cmd = ['ffmpeg', '-v', 'error', '-nostdin', '-i', path, '-f', 'f32le', '-ac', str(channels), '-ar', str(sr), '-']
    try:
        raw = subprocess.run(cmd, capture_output=True, timeout=120).stdout
    except Exception:  # noqa: BLE001
        return np.zeros(0, np.float32)
    x = np.frombuffer(raw, dtype=np.float32)
    if channels > 1:
        x = x[:len(x) // channels * channels].reshape(-1, channels)
    return x


def probe_channels(path):
    out = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'a:0', '-show_entries', 'stream=channels',
                          '-of', 'csv=p=0', path], capture_output=True, text=True).stdout.strip()
    try:
        return int(out.splitlines()[0].split(',')[0])
    except (ValueError, IndexError):
        return 1


def mel_filterbank(sr=SR, nfft=NFFT, nmel=NMEL, fmin=60.0, fmax=None):
    fmax = fmax or sr / 2
    hz2mel = lambda f: 2595 * np.log10(1 + f / 700.0)  # noqa: E731
    mel2hz = lambda m: 700 * (10 ** (m / 2595.0) - 1)  # noqa: E731
    pts = mel2hz(np.linspace(hz2mel(fmin), hz2mel(fmax), nmel + 2))
    bins = np.floor((nfft + 1) * pts / sr).astype(int)
    fb = np.zeros((nmel, nfft // 2 + 1), np.float32)
    for m in range(1, nmel + 1):
        a, b, c = bins[m - 1], bins[m], bins[m + 1]
        for k in range(a, b):
            fb[m - 1, k] = (k - a) / max(1, b - a)
        for k in range(b, c):
            fb[m - 1, k] = (c - k) / max(1, c - b)
    return fb


FB = mel_filterbank()
WIN = np.hanning(NFFT).astype(np.float32)


def logmel(x):
    """(frames, NMEL) gain-invariant log-mel features of a mono signal."""
    if len(x) < NFFT:
        x = np.pad(x, (0, NFFT - len(x)))
    n = 1 + (len(x) - NFFT) // HOP
    idx = np.arange(NFFT)[None, :] + HOP * np.arange(n)[:, None]
    spec = np.abs(np.fft.rfft(x[idx] * WIN, axis=1)) ** 2
    mel = spec @ FB.T
    db = 10 * np.log10(mel + 1e-12)
    db = np.maximum(db, db.max() - 80)
    return (db - db.mean(axis=1, keepdims=True)).astype(np.float32)


def xcorr_valid(a, b):
    """Valid-mode cross-correlation of 2-D features: out[o] = sum_t,b a[t] . b[o+t]."""
    m, n = len(a), len(b)
    size = 1 << int(np.ceil(np.log2(n + m)))
    fa = np.fft.rfft(a[::-1], size, axis=0)
    fb = np.fft.rfft(b, size, axis=0)
    full = np.fft.irfft(fa * fb, size, axis=0).sum(axis=1)
    return full[m - 1:n]


# ---------------------------------------------------------------- reference (the app's own sounds)

def build_reference(src_dirs, history_md):
    kt = kt_source.load(src_dirs, 'Sfx.kt', 'SourceSoundLibrary.kt')
    # The first entry of the BASE_ tables is the TAP_SET rotation row (no clip of its own): dropped.
    ids = kt.strings('BASE_LIBRARY')[1:]
    labels = kt.strings('BASE_LABELS')[1:]
    origs = kt.strings('BASE_ORIGINAL')[1:]
    ref = {}
    for i, lab, o in zip(ids, labels, origs):
        ref[i] = {'id': i, 'label': lab, 'group': 'LAUNCHER', 'hint': [o.split(' ')[0]]}
    tap_src = {1: 'scrshow.mp3', 2: 'scrshow.mp3', 3: 'tng_viewscreen_on.mp3', 4: 'tng_viewscreen_on.mp3',
               5: 'tng_viewscreen_off.mp3', 6: 'tng_viewscreen_off.mp3'}
    for k, s in tap_src.items():
        ref['tap_rot_%d' % k] = {'id': 'tap_rot_%d' % k, 'label': 'TAP BEEP %d' % k, 'group': 'LAUNCHER', 'hint': [s],
                                 'cut': True}
    for i, lab, o in zip(kt.strings('NAMES'), kt.strings('LABELS'), kt.strings('ORIGINALS')):
        ref[i] = {'id': i, 'label': lab, 'group': lab.split(': ')[0], 'hint': [o.split(' ')[0]]}
    # history table: | `tk_x` | LABEL | src path | a -> b s | ch | gain | `sha…` |
    for line in open(history_md, encoding='utf-8'):
        m = re.match(r'\|\s*`(tk_[a-z0-9_]+)`\s*\|\s*(.*?)\s*\|\s*(.*?)\s*\|\s*([\d.]+)\s*→\s*([\d.]+)\s*s\s*\|\s*(\w+)\s*\|'
                     r'\s*([+-][\d.]+) dB\s*\|\s*`([0-9a-f]+)', line)
        if m and m.group(1) in ref:
            r = ref[m.group(1)]
            r.update(src_path=m.group(3), in_s=float(m.group(4)), out_s=float(m.group(5)), channels=m.group(6),
                     hist_gain=float(m.group(7)), sha_prefix=m.group(8), excerpt='40 S EXCERPT' in m.group(2))
            r['hint'].append(os.path.basename(m.group(3)))
    return ref


# ---------------------------------------------------------------- candidates (crawled files)

def load_candidates(crawl_dir):
    files = {}
    for site in os.listdir(crawl_dir):
        cat = os.path.join(crawl_dir, site, 'catalog.json')
        if not os.path.isfile(cat):
            continue
        for e in json.load(open(cat, encoding='utf-8')):
            if not e.get('sha256'):
                continue
            path = os.path.join(crawl_dir, e['local'])
            if not os.path.isfile(path):
                continue
            e['site'] = site
            e['path'] = path
            files.setdefault(e['sha256'], []).append(e)
    cands = []
    for sha, hosts in files.items():
        hosts.sort(key=lambda h: (SITE_RANK.index(h['site']) if h['site'] in SITE_RANK else 99,
                                  0 if h['url'].lower().endswith('.mp3') else 1))
        cands.append({'sha256': sha, 'hosts': hosts, 'path': hosts[0]['path']})
    return cands


def norm_name(s):
    s = os.path.basename(s).lower()
    s = re.sub(r'\.(mp3|wav|ogg)$', '', s)
    s = re.sub(r'(_clean|_ep)$', '', s)
    return re.sub(r'[^a-z0-9]', '', s)


def cand_names(c):
    out = set()
    for h in c['hosts']:
        out.add(norm_name(h['url']))
        if h.get('label'):
            out.add(norm_name(h['label']))
            out.add(norm_name(re.sub(r'(?i)^tng\s+', '', h['label'])))
    return out


# ---------------------------------------------------------------- matching

def fine_align(clip, src, center, radius):
    """Sample-accurate lag of clip inside src within center +- radius samples; returns (corr, start, gain_db)."""
    m = len(clip)
    lo = max(0, center - radius)
    hi = min(len(src) - m, center + radius)
    if hi < lo:
        return -1.0, 0, 0.0
    seg = src[lo:hi + m]
    num = np.correlate(seg, clip, mode='valid') if m < 4096 else _fft_corr(seg, clip)
    c2 = np.concatenate([[0.0], np.cumsum(seg.astype(np.float64) ** 2)])
    energy = np.sqrt(np.maximum(c2[m:] - c2[:-m], 1e-12))[:len(num)]
    cn = num / (energy * (np.linalg.norm(clip) + 1e-12))
    k = int(np.argmax(cn))
    start = lo + k
    part = src[start:start + m].astype(np.float64)
    # Gain = least-squares fit clip ~ g * part over the interior (the edges carry the de-click fades).
    e = min(m // 4, int(0.012 * FINE_SR))
    a, b = clip[e:m - e].astype(np.float64), part[e:m - e]
    g_lin = float(np.dot(a, b) / max(np.dot(b, b), 1e-12))
    g = 20 * np.log10(max(g_lin, 1e-6))
    return float(cn[k]), start, float(g)


def _fft_corr(seg, clip):
    n, m = len(seg), len(clip)
    size = 1 << int(np.ceil(np.log2(n + m)))
    r = np.fft.irfft(np.fft.rfft(seg, size) * np.conj(np.fft.rfft(clip, size)), size)
    return r[:n - m + 1]


def match_one(rid, r, clip, feats, cand, cat_feats, offsets, sel_idx, top=6):
    """Coarse log-mel search over the selected candidates, then fine waveform alignment."""
    cf = feats
    m = len(cf)
    best = []
    # The processed clip may run a little past either end of the original (trim pre-roll / tail),
    # so the original is padded with silent frames on both sides.
    pad = min(m // 2, 40)
    for ci in sel_idx:
        F = cat_feats[ci]
        if F is None or len(F) + 2 * pad < m:
            continue  # the original must be (about) as long as the clip
        F = np.pad(F, ((pad, pad), (0, 0)))
        num = xcorr_valid(cf, F)
        e = np.concatenate([[0.0], np.cumsum((F.astype(np.float64) ** 2).sum(axis=1))])
        wn = np.sqrt(np.maximum(e[m:] - e[:-m], 1e-9))[:len(num)]
        s = num / (wn * (np.linalg.norm(cf) + 1e-9))
        k = int(np.argmax(s))
        best.append((float(s[k]), ci, k - pad))
    best.sort(reverse=True)
    results = []
    for score, ci, k in best[:top]:
        results.append({'cand': ci, 'mel': round(score, 4), 'frame': k})
    return results


def refine(results, clip_hi, cand, hi_cache):
    """Fine alignment at FINE_SR (resampling to 22.05 or 44.1 kHz blurs the phase of bright beeps; 48 kHz is what the original pipeline used)."""
    for x in results:
        ci = x['cand']
        src = hi_cache.get(ci)
        if src is None:
            src = decode(cand[ci]['path'], FINE_SR)
            if cand[ci]['dur'] < 30:  # keep short files only; long ambient loops would use GBs
                hi_cache[ci] = src
        pad = len(clip_hi)
        srcp = np.concatenate([np.zeros(pad, np.float32), src, np.zeros(pad, np.float32)])
        ratio = FINE_SR / SR
        corr, start, g = fine_align(clip_hi, srcp, pad + int(x['frame'] * HOP * ratio), int(3 * HOP * ratio))
        start -= pad
        x['src_len_s'] = round(len(src) / FINE_SR, 4)
        x.update(corr=round(corr, 4), start=start, start_s=round(start / FINE_SR, 6), fine_sr=FINE_SR, gain_db=round(g, 2))
    results.sort(key=lambda x: (-x['corr'], -x['mel']))
    return results


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--ref', required=True)
    ap.add_argument('--crawl', required=True)
    ap.add_argument('--src', '--java', dest='src', action='append',
                    help='Kotlin source folder with Sfx.kt / SourceSoundLibrary.kt (repeatable; default: core and app trekui packages)')
    ap.add_argument('--history', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--only', help='comma-separated ids to (re)match, for debugging')
    o = ap.parse_args()

    ref = build_reference(o.src or kt_source.default_src_dirs(REPO), o.history)
    if o.only:
        ref = {k: v for k, v in ref.items() if k in o.only.split(',')}
    cand = load_candidates(o.crawl)
    print('reference sounds: %d, unique crawled files: %d' % (len(ref), len(cand)), flush=True)

    with ThreadPoolExecutor(8) as ex:
        pcms = list(ex.map(lambda c: decode(c['path']), cand))
    for c, p in zip(cand, pcms):
        c['pcm'] = p
        c['dur'] = len(p) / SR
        c['names'] = cand_names(c)
    feats = [logmel(c['pcm']) if len(c['pcm']) > 0 else None for c in cand]
    by_sha_prefix = {}
    for i, c in enumerate(cand):
        by_sha_prefix.setdefault(c['sha256'][:16], []).append(i)

    report = {}
    hi_cache = {}
    for n, (rid, r) in enumerate(sorted(ref.items()), 1):
        path = os.path.join(o.ref, rid + '.ogg')
        if not os.path.isfile(path):
            report[rid] = {'error': 'no reference clip'}
            continue
        clip = decode(path)
        ch = probe_channels(path)
        if len(clip) == 0:
            report[rid] = {'error': 'reference decode failed'}
            continue
        dur = len(clip) / SR
        cf = logmel(clip)
        hints = {norm_name(h) for h in r['hint']}
        named = [i for i, c in enumerate(cand) if c['names'] & hints]
        sha_hit = by_sha_prefix.get(r.get('sha_prefix', '')[:16], [])
        # Stage 1: the files the 5.x notes name (by file name / label, or by SHA-256 prefix).
        # Stage 2 (only if stage 1 is not convincing): every file long enough to contain the clip
        # and at most 60 s long (ambient excerpts are matched against named / hashed files only).
        clip_hi = decode(path, FINE_SR)
        first = sorted(set(named) | set(sha_hit))
        stage1 = match_one(rid, r, clip, cf, cand, feats, None, first) if first else []
        if r.get('excerpt'):
            # Ambient loops repeat, so a 40 s excerpt matches at several offsets; also try the start of
            # the file, which is where the 5.x pipeline cut it, and prefer it when it is as good.
            stage1 += [dict(x, frame=0, early=True) for x in stage1]
        res = refine(stage1, clip_hi, cand, hi_cache) if stage1 else []
        if r.get('excerpt') and res:
            top = res[0]['corr']
            res.sort(key=lambda x: (x['corr'] < top - 0.005, x['start']))
        if not res or res[0]['corr'] < CONFIRMED:
            wide = [i for i, c in enumerate(cand) if dur - 0.05 <= c['dur'] <= 60 and i not in first]                 if not r.get('excerpt') else []
            more = refine(match_one(rid, r, clip, cf, cand, feats, None, wide), clip_hi, cand, hi_cache) if wide else []
            res = sorted(res + more, key=lambda x: (-x['corr'], -x['mel']))
        for x in res:
            c = cand[x['cand']]
            x['sha256'] = c['sha256']
            x['url'] = c['hosts'][0]['url']
            x['named'] = x['cand'] in named
            x['sha_hit'] = x['cand'] in sha_hit
            x['src_dur'] = round(c['dur'], 3)
        report[rid] = {'dur': round(dur, 3), 'channels': ch, 'results': res}
        b = res[0] if res else None
        print('[%d/%d] %-48s %s' % (n, len(ref), rid, ('%.3f %s %+.1fdB @%.3fs' % (b['corr'], os.path.basename(b['url']),
                                                                                 b['gain_db'], b['start_s'])) if b else 'none'),
              flush=True)
    os.makedirs(o.out, exist_ok=True)
    slim = {k: v for k, v in report.items()}
    json.dump({'ref': ref, 'report': slim, 'cands': [{'sha256': c['sha256'], 'dur': c['dur'],
                                                      'hosts': [{k: h.get(k) for k in ('site', 'url', 'page', 'label', 'section', 'bytes')}
                                                                for h in c['hosts']]} for c in cand]},
              open(os.path.join(o.out, 'match_report.json'), 'w', encoding='utf-8'), indent=1)
    print('wrote', os.path.join(o.out, 'match_report.json'))


if __name__ == '__main__':
    sys.exit(main())
