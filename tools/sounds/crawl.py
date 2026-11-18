#!/usr/bin/env python3
"""Crawl the public sound-effect index pages the TrekUI sound manifest points at.

For each site: fetch its index page, collect every linked audio file (href= or src=, .mp3/.wav/
.ogg), download each one ONCE (sequentially, with a polite delay, generic User-Agent), and write
<out>/<site>/catalog.json describing every file: page URL, direct URL, link label, section heading,
local path, size and SHA-256.

Downloads are untrusted data. They are only ever read as bytes / decoded by ffmpeg; run this and
match.py with `python -I` and keep the output folder separate from this script folder.

    python -I tools/sounds/crawl.py <out_dir> [--site trekcore ...] [--delay 0.7] [--catalog-only]

Re-running skips files already on disk (same size as the server reported), so it is resumable.
Standard library only.
"""
import argparse
import hashlib
import html
import json
import os
import re
import sys
import time
from html.parser import HTMLParser
from urllib.parse import urljoin, urlparse, unquote
from urllib.request import Request, urlopen

UA = 'Mozilla/5.0 (compatible; sound-catalog/1.0)'
TIMEOUT = 30
MAX_BYTES = 40 * 1024 * 1024
AUDIO_RE = re.compile(r'\.(mp3|wav|ogg)$', re.I)

SITES = {
    'trekcore': 'https://www.trekcore.com/audio/',
    'willbswift': 'https://willbswift.github.io/treksounds/',
    # Sources whose addresses carry the franchise or console-trademark names are not used (owner rule, 2026-11-18).
}


class LinkParser(HTMLParser):
    """Collects audio links with their anchor text and the most recent heading / image alt."""

    def __init__(self, base):
        super().__init__(convert_charrefs=True)
        self.base = base
        self.section = ''
        self.href = None
        self.parts = []
        self.entries = []
        self._heading = False

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag in ('h1', 'h2', 'h3', 'h4'):
            self._heading = True
            self.parts = []
        if tag == 'img':
            alt = (a.get('alt') or '').strip()
            if alt and 4 < len(alt) < 110:
                self.section = alt
        if tag == 'a' and a.get('href'):
            self.href = a['href']
            self.parts = []
        src = a.get('src')
        if tag in ('audio', 'source') and src and AUDIO_RE.search(urlparse(src).path):
            self._add(src, a.get('title') or a.get('data-name') or '')

    def handle_data(self, data):
        if self.href is not None or self._heading:
            self.parts.append(data)

    def handle_endtag(self, tag):
        if tag in ('h1', 'h2', 'h3', 'h4') and self._heading:
            t = ' '.join(''.join(self.parts).split())
            if t:
                self.section = t
            self._heading = False
            self.parts = []
        if tag == 'a' and self.href is not None:
            label = ' '.join(''.join(self.parts).split())
            if AUDIO_RE.search(urlparse(self.href).path):
                self._add(self.href, label)
            self.href = None
            self.parts = []

    def _add(self, ref, label):
        url = urljoin(self.base, html.unescape(ref.strip()))
        self.entries.append({'url': url, 'label': label, 'section': self.section})


def fetch(url, attempts=3):
    last = None
    for i in range(attempts):
        try:
            with urlopen(Request(url, headers={'User-Agent': UA, 'Accept': '*/*'}), timeout=TIMEOUT) as r:
                data = r.read(MAX_BYTES + 1)
                if len(data) > MAX_BYTES:
                    raise ValueError('larger than %d bytes' % MAX_BYTES)
                return data
        except Exception as e:  # noqa: BLE001 - report and retry
            last = e
            time.sleep(1.5 * (i + 1))
    raise RuntimeError('%s: %s' % (url, last))


def list_site(site, page):
    raw = fetch(page)
    if site.startswith('github_'):
        items = json.loads(raw.decode('utf-8'))
        return [{'url': it['download_url'], 'label': it['name'], 'section': 'repository root',
                 'page': page}
                for it in items if it.get('type') == 'file' and AUDIO_RE.search(it['name'])]
    p = LinkParser(page)
    p.feed(raw.decode('utf-8', errors='replace'))
    seen, out = set(), []
    for e in p.entries:
        if e['url'] in seen:
            continue
        seen.add(e['url'])
        e['page'] = page
        out.append(e)
    return out


def local_path(root, site, url):
    path = unquote(urlparse(url).path).lstrip('/')
    path = re.sub(r'[^A-Za-z0-9._/-]+', '_', path)
    parts = [p for p in path.split('/') if p not in ('', '.', '..')]
    return os.path.join(root, site, *parts)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('out')
    ap.add_argument('--site', action='append', choices=sorted(SITES))
    ap.add_argument('--delay', type=float, default=0.7)
    ap.add_argument('--catalog-only', action='store_true')
    o = ap.parse_args()
    for site in o.site or list(SITES):
        page = SITES[site]
        print('== %s  %s' % (site, page), flush=True)
        entries = list_site(site, page)
        print('   %d audio links' % len(entries), flush=True)
        cat_path = os.path.join(o.out, site, 'catalog.json')
        old = {}
        if os.path.isfile(cat_path):
            old = {e['url']: e for e in json.load(open(cat_path, encoding='utf-8'))}
        for i, e in enumerate(entries, 1):
            dst = local_path(o.out, site, e['url'])
            e['local'] = os.path.relpath(dst, o.out).replace('\\', '/')
            prev = old.get(e['url'])
            if prev and prev.get('sha256') and os.path.isfile(dst):
                e.update({k: prev[k] for k in ('sha256', 'bytes') if k in prev})
                continue
            if o.catalog_only:
                continue
            try:
                data = fetch(e['url'])
                if len(data) < 64 or data[:64].lstrip().lower().startswith((b'<!doctype', b'<html')):
                    raise ValueError('server returned HTML, not audio')
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                with open(dst, 'wb') as f:
                    f.write(data)
                e['bytes'] = len(data)
                e['sha256'] = hashlib.sha256(data).hexdigest()
                print('   [%d/%d] %s (%d B)' % (i, len(entries), e['local'], len(data)), flush=True)
            except Exception as ex:  # noqa: BLE001
                e['error'] = str(ex)
                print('   [%d/%d] FAILED %s' % (i, len(entries), ex), flush=True)
            time.sleep(o.delay)
        os.makedirs(os.path.dirname(cat_path), exist_ok=True)
        json.dump(entries, open(cat_path, 'w', encoding='utf-8'), indent=1)
        print('   wrote', cat_path, flush=True)


if __name__ == '__main__':
    sys.exit(main())
