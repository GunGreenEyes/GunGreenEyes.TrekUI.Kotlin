#!/usr/bin/env python3
"""Turn match.py's match_report.json into the app asset core/src/main/assets/sounds/manifest.json.

For every app sound id: the best-matching public original (preferring the file the 5.x notes name
when it scores within 0.03 of the best), its page / direct URL / SHA-256 / size, identical copies on
other sites as download alternates, the cut (start/end seconds), gain in dB, mono-fold flag,
edge fades, and the match confidence. Sounds without a confident match are written with
"available": false (the user can still import their own file for them).

With --verify, every recipe is re-rendered in Python exactly as the app does it (cut, mono fold,
gain, fades) from the downloaded original and compared with the 5.2 reference clip; the result
goes to verify_report.json next to match_report.json (not into the asset).

    python -I tools/sounds/build_manifest.py --report <dir>/match_report.json [--src <kotlin dir>] \
        --crawl <crawl dir> --ref <ref dir> --out core/src/main/assets/sounds/manifest.json [--verify]

--src names the Kotlin source folder(s) holding Sfx.kt, Stations.kt and BootSequence.kt (repeatable;
default the core and app src/main/kotlin/com/gungreeneyes/trekui packages); --java is an alias.
"""
import argparse
import datetime
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I leaves the script folder off sys.path
import kt_source  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HIGH, MEDIUM = 0.90, 0.75
PREFER_NAMED_WITHIN = 0.03
SITE_PAGE = {
    'trekcore': 'https://www.trekcore.com/audio/',
    'github_sound_pack': 'https://github.com/wofsonofmoag/lcars-sounds',
    'mediacollege': 'https://www.mediacollege.com/downloads/sound-effects/star-trek/tos/',
    'willbswift': 'https://willbswift.github.io/treksounds/',
    'slspencer': 'https://www.slspencer.com/Sounds/Star%20Trek%20Sounds/LCARS%20Audio/',
}
SITE_RANK = ['trekcore', 'github_sound_pack', 'mediacollege', 'willbswift', 'slspencer']
EVENT_KEYS = ['button', 'navigation', 'launch', 'confirm', 'error', 'comms', 'diagnostics', 'red_alert']
NOTICE = ('The app ships no recordings. These are third-party sound effects that belong to their '
          'copyright holders, downloaded by the user from the public pages listed here, for personal '
          'use on their own device. See docs/SOUNDS.md.')


def defaults(src_dirs):
    """id -> ['command:navigation', ...] from Sfx.FACTION_DEFAULT; plus station / startup uses."""
    kt = kt_source.load(src_dirs, 'Sfx.kt')
    out = {}

    def add(i, tag):
        out.setdefault(i, []).append(tag)

    # FACTION_DEFAULT = arrayOf(DEFAULT_RES, IMPERIAL_DEFAULT, COLLECTIVE_DEFAULT, DEFAULT_RES, ...):
    # its first three rows are the Command, Imperial and Collective interface defaults.
    fac = kt.get('FACTION_DEFAULT')
    profiles = list(zip(('command', 'imperial', 'collective'), fac))
    tap_set = kt.get('TAP_SET')
    taps = kt.strings('TAP_SET_CLIPS')
    for name, arr in profiles:
        for e, i in enumerate(arr[:8]):
            if i == tap_set:
                for t in taps:
                    add(t, name + ':button')
            elif i != 'none' and not i.startswith('#'):  # '#synthtaps_kl' etc: synthesized rotations, no clip id
                add(i, name + ':' + EVENT_KEYS[e])
    stations = kt_source.find_source(src_dirs, 'Stations.kt')
    if stations:
        for i in set(re.findall(r'"((?:tk|alt|voice|ui|comms|alert|tng|system)_[a-z0-9_]+)"', open(stations, encoding='utf-8').read())):
            add(i, 'stations')
    boot = kt_source.find_source(src_dirs, 'BootSequence.kt')
    if boot:
        for i in set(re.findall(r'"(voice_[a-z0-9_]+|tk_[a-z0-9_]+)"', open(boot, encoding='utf-8').read())):
            add(i, 'startup')
    return {k: sorted(set(v)) for k, v in out.items()}


def host_entry(h, sha):
    return {'url': h['url'], 'page': h.get('page') or SITE_PAGE.get(h['site'], ''), 'site': h['site'],
            'sha256': sha, 'bytes': h.get('bytes') or -1, 'title': h.get('label') or ''}


def choose(results):
    if not results:
        return None
    best = results[0]
    for x in results:
        if (x.get('named') or x.get('sha_hit')) and x['corr'] >= best['corr'] - PREFER_NAMED_WITHIN:
            return x
    return best


def build(report_path, src_dirs):
    data = json.load(open(report_path, encoding='utf-8'))
    ref, rep, cands = data['ref'], data['report'], data['cands']
    uses = defaults(src_dirs)
    sounds, table = [], []
    for rid in sorted(ref):
        r = ref[rid]
        m = rep.get(rid, {})
        res = m.get('results') or []
        pick = choose(res)
        conf = pick['corr'] if pick else 0.0
        level = 'high' if conf >= HIGH else 'medium' if conf >= MEDIUM else 'none'
        events = uses.get(rid, [])
        s = {'id': rid, 'label': r['label'], 'group': r.get('group', ''), 'events': events,
             'core': bool(events), 'available': level != 'none', 'confidence': round(conf, 3), 'match': level}
        if pick and level != 'none':
            c = cands[pick['cand']]
            hosts = sorted(c['hosts'], key=lambda h: (SITE_RANK.index(h['site']) if h['site'] in SITE_RANK else 99,
                                                      0 if h['url'].lower().endswith('.mp3') else 1))
            hosts = [h for h in hosts if h['url'].startswith('https://')]
            if not hosts:
                s['available'] = False
            else:
                dur = m['dur']
                start = pick['start'] / pick.get('fine_sr', 48000)  # exact sample, not the rounded start_s
                end = start + dur
                start = max(0.0, start)
                end = min(end, pick.get('src_len_s', end))
                excerpt = bool(r.get('excerpt'))
                s.update({
                    'source': host_entry(hosts[0], c['sha256']),
                    'alternates': [host_entry(h, c['sha256']) for h in hosts[1:]],
                    'start': round(start, 6), 'end': round(end, 6), 'gain_db': round(pick['gain_db'], 2),
                    'mono': m.get('channels', 1) == 1,
                    'fade_in_ms': 2 if rid.startswith('tap_rot_') else 4,
                    'fade_out_ms': 1500 if excerpt else 4,
                })
                similar = []
                for x in res:
                    if x is pick or x['corr'] < HIGH or x['sha256'] == c['sha256']:
                        continue
                    similar.append({'url': x['url'], 'sha256': x['sha256'], 'corr': x['corr'],
                                    'start': round(max(0.0, x['start_s']), 4), 'gain_db': round(x['gain_db'], 2)})
                if similar:
                    s['similar'] = similar[:3]
        sounds.append(s)
        table.append((rid, level, conf, s.get('source', {}).get('url', '')))
    manifest = {'format': 1, 'generated': datetime.date.today().isoformat(), 'notice': NOTICE,
                'processing': 'cut [start, end) seconds from the decoded original; fold to mono if "mono"; '
                              'multiply by 10^(gain_db/20); linear fades fade_in_ms / fade_out_ms; clamp; 16-bit WAV',
                'sounds': sounds}
    return manifest, table


def verify(manifest, crawl_dir, ref_dir, out_path):
    """Re-render each recipe like the app does and compare with the 5.2 reference clip."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import numpy as np
    import match as M
    by_url = {}
    for site in os.listdir(crawl_dir):
        cat = os.path.join(crawl_dir, site, 'catalog.json')
        if os.path.isfile(cat):
            for e in json.load(open(cat, encoding='utf-8')):
                by_url[e['url']] = os.path.join(crawl_dir, e['local'])
    sr = 48000
    out = {}
    for s in manifest['sounds']:
        if not s['available']:
            continue
        x = M.decode(by_url[s['source']['url']], sr, 1 if s['mono'] else 2)
        y = M.decode(os.path.join(ref_dir, s['id'] + '.ogg'), sr, 1 if s['mono'] else 2)
        a, b = int(round(s['start'] * sr)), int(round(s['end'] * sr))
        z = x[a:b].astype(np.float64) * 10 ** (s['gain_db'] / 20)
        fi, fo = int(sr * s['fade_in_ms'] / 1000), int(sr * s['fade_out_ms'] / 1000)
        n = len(z)
        if n:
            fi, fo = min(fi, n // 2), min(fo, n - min(fi, n // 2))
            ramp_in = (np.arange(fi) + 1) / (fi + 1)
            ramp_out = ((np.arange(fo) + 1) / (fo + 1))[::-1]
            if z.ndim == 1:
                z[:fi] *= ramp_in
                z[n - fo:] *= ramp_out
            else:
                z[:fi] *= ramp_in[:, None]
                z[n - fo:] *= ramp_out[:, None]
            z = np.clip(z, -1, 1)
        k = min(len(z), len(y))
        zf, yf = z[:k].reshape(-1), y[:k].reshape(-1).astype(np.float64)
        corr = float(np.dot(zf, yf) / (np.linalg.norm(zf) * np.linalg.norm(yf) + 1e-12)) if k else 0.0
        lvl = float(20 * np.log10((np.sqrt(np.mean(zf ** 2)) + 1e-12) / (np.sqrt(np.mean(yf ** 2)) + 1e-12))) if k else 0.0
        out[s['id']] = {'corr': round(corr, 4), 'level_diff_db': round(lvl, 2), 'len_diff_s': round((len(z) - len(y)) / sr, 4)}
    json.dump(out, open(out_path, 'w', encoding='utf-8'), indent=1)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--report', required=True)
    ap.add_argument('--src', '--java', dest='src', action='append',
                    help='Kotlin source folder with Sfx.kt / Stations.kt / BootSequence.kt (repeatable; default: core and app trekui packages)')
    ap.add_argument('--out', required=True)
    ap.add_argument('--crawl')
    ap.add_argument('--ref')
    ap.add_argument('--verify', action='store_true')
    o = ap.parse_args()
    manifest, table = build(o.report, o.src or kt_source.default_src_dirs(REPO))
    os.makedirs(os.path.dirname(os.path.abspath(o.out)), exist_ok=True)
    with open(o.out, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(manifest, f, indent=1, ensure_ascii=False)
        f.write('\n')
    levels = {}
    for _, lv, _, _ in table:
        levels[lv] = levels.get(lv, 0) + 1
    print('sounds: %d  %s' % (len(table), ', '.join('%s=%d' % kv for kv in sorted(levels.items()))))
    print('core sounds available: %d / %d' % (sum(1 for s in manifest['sounds'] if s['core'] and s['available']),
                                               sum(1 for s in manifest['sounds'] if s['core'])))
    for rid, lv, conf, url in table:
        if lv != 'high':
            print('  %-48s %-6s %.3f %s' % (rid, lv, conf, url))
    if o.verify:
        v = verify(manifest, o.crawl, o.ref, os.path.join(os.path.dirname(os.path.abspath(o.report)), 'verify_report.json'))
        bad = {k: x for k, x in v.items() if x['corr'] < 0.9 or abs(x['level_diff_db']) > 1.0}
        print('verify: %d rendered, %d below corr 0.9 or off by >1 dB' % (len(v), len(bad)))
        for k, x in sorted(bad.items()):
            print('  %-48s %s' % (k, x))


if __name__ == '__main__':
    sys.exit(main())
