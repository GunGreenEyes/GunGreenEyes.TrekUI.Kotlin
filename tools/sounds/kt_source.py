"""Read the string arrays and constants the sound tools need out of the app's Kotlin sources.

Not a Kotlin parser: a tokenizer (string literals, names, numbers, punctuation; comments dropped)
plus a tiny evaluator for the shapes the sound tables use:

    const val TAP_SET = "#taps"
    val DEFAULT_RES = arrayOf(TAP_SET, "ui_panel_open", ...)
    val IMPERIAL_DEFAULT = arrayOf(SYNTH_KL_TAPS, SYNTH_KL_EVENT[E_LAUNCH], ...)
    val FACTION_DEFAULT = arrayOf(DEFAULT_RES, IMPERIAL_DEFAULT, ...)
    val SYNTH_KL_EVENT = family("kl", SYNTH_KL_TAPS)
    val LIBRARY: Array<String> = BASE_LIBRARY + SourceSoundLibrary.NAMES

A name resolves in its own file first, then in the other files given. String literals come back
with their escape sequences as written (the Java-era tools did the same). Sfx.family() is
mirrored here (it builds {taps, synth_<fam>_<part>, ...} from its local `parts` array).
"""
import os
import re

DEFAULT_PKG = os.path.join('src', 'main', 'kotlin', 'com', 'gungreeneyes', 'trekui')


def default_src_dirs(repo):
    """The Kotlin source folders of the sound tables: core (Sfx, SourceSoundLibrary) and app (Stations, BootSequence)."""
    return [os.path.join(repo, 'core', DEFAULT_PKG), os.path.join(repo, 'app', DEFAULT_PKG)]


def find_source(dirs, name):
    """First <dir>/<name> that exists, else None."""
    for d in dirs:
        p = os.path.join(d, name)
        if os.path.isfile(p):
            return p
    return None


def read_source(dirs, name):
    p = find_source(dirs, name)
    if p is None:
        raise FileNotFoundError('%s not found in %s' % (name, ', '.join(dirs)))
    return open(p, encoding='utf-8').read()


_TOKEN = re.compile(r'''
    (?P<ws>\s+)
  | (?P<lcomment>//[^\n]*)
  | (?P<bcomment>/\*.*?\*/)
  | (?P<str>"(?:[^"\\]|\\.)*")
  | (?P<char>'(?:[^'\\]|\\.)+')
  | (?P<num>\d[\d_]*[lL]?)
  | (?P<name>[A-Za-z_][A-Za-z0-9_]*)
  | (?P<punct>.)
''', re.S | re.X)


def tokenize(src):
    out = []
    for m in _TOKEN.finditer(src):
        kind = m.lastgroup
        if kind in ('ws', 'lcomment', 'bcomment'):
            continue
        out.append((kind, m.group(kind)))
    return out


class KtSource:
    """Lazy resolver over one or more Kotlin files. get(name) -> str | int | list."""

    def __init__(self, texts):
        self.files = [tokenize(t) for t in texts]
        self.defs = [self._index(toks) for toks in self.files]
        self.cache = {}

    @staticmethod
    def _index(toks):
        """name -> token index of the expression after `val NAME [: Type] =` (first definition wins)."""
        defs = {}
        for i, (k, v) in enumerate(toks):
            if k != 'name' or v != 'val' or i + 2 >= len(toks) or toks[i + 1][0] != 'name':
                continue
            name, j = toks[i + 1][1], i + 2
            if toks[j] == ('punct', ':'):  # skip a type: Name(.Name)*(<...>)?(?)?
                j += 1
                depth, expect_name = 0, True
                while j < len(toks):
                    k2, v2 = toks[j]
                    if expect_name and k2 == 'name':
                        expect_name = False
                    elif not expect_name and v2 in ('.', ','):
                        expect_name = True
                    elif v2 == '<':
                        depth += 1
                        expect_name = True
                    elif v2 == '>' and depth:
                        depth -= 1
                    elif v2 == '?' and not expect_name:
                        pass
                    else:
                        break
                    j += 1
            if j < len(toks) and toks[j] == ('punct', '=') and name not in defs:
                defs[name] = j + 1
        return defs

    def get(self, name, prefer=None):
        order = list(range(len(self.files)))
        if prefer is not None:
            order.remove(prefer)
            order.insert(0, prefer)
        for fi in order:
            if name in self.defs[fi]:
                key = (fi, name)
                if key not in self.cache:
                    self.cache[key] = None  # cycle guard
                    val, _ = self._expr(fi, self.defs[fi][name])
                    self.cache[key] = val
                if self.cache[key] is None:
                    raise ValueError('circular definition of %s' % name)
                return self.cache[key]
        raise KeyError('no val/const val named %s' % name)

    def strings(self, name):
        """A string array as a list of strings (raises if any element is not a string)."""
        v = self.get(name)
        if not isinstance(v, list) or not all(isinstance(x, str) for x in v):
            raise ValueError('%s is not an array of strings' % name)
        return v

    # ---- evaluator: expr := term ('+' term)* ; term := atom ('[' expr ']')*
    def _expr(self, fi, i):
        val, i = self._term(fi, i)
        toks = self.files[fi]
        while i < len(toks) and toks[i] == ('punct', '+'):
            rhs, i = self._term(fi, i + 1)
            val = val + rhs
        return val, i

    def _term(self, fi, i):
        val, i = self._atom(fi, i)
        toks = self.files[fi]
        while i < len(toks) and toks[i] == ('punct', '['):
            idx, i = self._expr(fi, i + 1)
            i = self._expect(fi, i, ']')
            val = val[idx]
        return val, i

    def _atom(self, fi, i):
        toks = self.files[fi]
        k, v = toks[i]
        if k == 'str':
            if '$' in re.sub(r'\\.', '', v):
                raise ValueError('string template not supported: %s' % v)
            return v[1:-1], i + 1
        if k == 'num':
            return int(v.replace('_', '').rstrip('lL')), i + 1
        if k == 'punct' and v == '(':
            val, i = self._expr(fi, i + 1)
            return val, self._expect(fi, i, ')')
        if k == 'name':
            while i + 2 < len(toks) and toks[i + 1] == ('punct', '.') and toks[i + 2][0] == 'name':
                i += 2  # Owner.NAME -> NAME
                v = toks[i][1]
            if i + 1 < len(toks) and toks[i + 1] == ('punct', '('):
                args, i = self._args(fi, i + 2)
                return self._call(fi, v, args), i
            return self.get(v, prefer=fi), i + 1
        raise ValueError('unsupported token %r' % (v,))

    def _args(self, fi, i):
        toks = self.files[fi]
        args = []
        while toks[i] != ('punct', ')'):
            a, i = self._expr(fi, i)
            args.append(a)
            if toks[i] == ('punct', ','):
                i += 1
            elif toks[i] != ('punct', ')'):
                raise ValueError('expected , or ) but got %r' % (toks[i][1],))
        return args, i + 1

    def _expect(self, fi, i, ch):
        if self.files[fi][i] != ('punct', ch):
            raise ValueError('expected %s but got %r' % (ch, self.files[fi][i][1]))
        return i + 1

    def _call(self, fi, fn, args):
        if fn == 'arrayOf':
            return args
        if fn == 'family':  # Sfx.family(fam, taps)
            fam, taps = args
            return [taps] + ['synth_' + fam + '_' + p for p in self._family_parts()]
        raise ValueError('unsupported call %s()' % fn)

    def _family_parts(self):
        """The `val parts = arrayOf(...)` inside `fun family`."""
        for fi, toks in enumerate(self.files):
            for i in range(len(toks) - 1):
                if toks[i] == ('name', 'fun') and toks[i + 1] == ('name', 'family'):
                    for j in range(i, len(toks) - 3):
                        if toks[j] == ('name', 'val') and toks[j + 1] == ('name', 'parts') and toks[j + 2] == ('punct', '='):
                            return self._expr(fi, j + 3)[0]
        raise ValueError('fun family / val parts not found')


def load(dirs, *names):
    """KtSource over the named files (e.g. 'Sfx.kt', 'SourceSoundLibrary.kt') found in dirs."""
    return KtSource([read_source(dirs, n) for n in names])
