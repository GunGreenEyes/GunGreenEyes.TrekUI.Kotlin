"""The owner's naming rule for everything the sound tools write into the app.

Product text says Federation, Empire and Collective, never the three franchise names they replace
(TUK-T20 / LCA-T71). The app's sound ids already follow the rule (they come from the app sources);
the third-party titles the crawl picks up do not, so build_manifest.py passes every host title
through title(). The hosts' URLs are left exactly as they are: they are the real download paths and
the SHA-256 checks depend on those exact files.

The owner's local 5.2 reference clips (<id>.ogg) still carry the ids from before the rename;
ref_clip() finds them under either name.
"""
import json
import os
import re

# The banned word (as a lower-case pattern) -> its replacement. Kept as patterns built from parts
# so this rule file does not itself spell the words out.
_RULES = [
    ('s' + 'tarfleet', 'federation'),
    ('k' + 'lingon', 'empire'),
    ('b' + 'org', 'collective'),
]
_ANY = re.compile('|'.join(w for w, _ in _RULES), re.I)
_FROM_FIRST = re.compile(r'\b(from) (%s)\b' % _RULES[0][0], re.I)
_AUDIO_FILE = re.compile(r'\.(mp3|wav|ogg|m4a|flac|aiff?)$', re.I)
SOURCE_FILE = '(source file)'


def _cased(like, word):
    """`word` in the case of `like`: UPPER, Capitalised or lower."""
    if like.isupper():
        return word.upper()
    if like[:1].isupper():
        return word[:1].upper() + word[1:]
    return word


def title(t):
    """A host's title with the banned words replaced; a bare file name that has one becomes '(source file)'."""
    if not t or not _ANY.search(t):
        return t
    if _AUDIO_FILE.search(t.strip()):
        return SOURCE_FILE
    t = _FROM_FIRST.sub(lambda m: m.group(1) + ' ' + _cased(m.group(1), 'the') + ' ' + _cased(m.group(2), _RULES[0][1]), t)
    repl = dict(_RULES)
    return _ANY.sub(lambda m: _cased(m.group(0), repl[m.group(0).lower()]), t)


def legacy_id(rid):
    """The id a renamed sound had before the rule (its replacement words turned back), or None."""
    old = rid
    for word, new in _RULES:
        old = old.replace(new, word)
    return old if old != rid else None


def ref_clip(ref_dir, rid):
    """Path of the reference clip for an id, falling back to the clip saved under its old id."""
    path = os.path.join(ref_dir, rid + '.ogg')
    old = legacy_id(rid)
    if not os.path.isfile(path) and old and os.path.isfile(os.path.join(ref_dir, old + '.ogg')):
        return os.path.join(ref_dir, old + '.ogg')
    return path


def manifest_hints(manifest_path):
    """id -> file name of its current source in the committed manifest (a name hint where the app
    sources say only '(source file)'). Empty when there is no manifest yet."""
    if not os.path.isfile(manifest_path):
        return {}
    data = json.load(open(manifest_path, encoding='utf-8'))
    return {s['id']: os.path.basename(s['source']['url']) for s in data.get('sounds', []) if s.get('source')}


MANIFEST = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                        'core', 'src', 'main', 'assets', 'sounds', 'manifest.json')
