#!/usr/bin/env python3
"""Generate the launcher's ORIGINAL synthesized sound sets: the default console set (synth_*),
Imperial-style harsh/metallic (synth_kl_*) and Collective-style cold digital/drone (synth_bg_*).

Every sample is computed here from sine / FM oscillators, simple waveshaping and seeded filtered
noise. No recording, sample or third-party audio is used, so the output may be committed and
shipped in the Play build. Same input -> same output (fixed seeds, bit-exact ffmpeg flags).

Processing matches the 5.x notes: mono 48 kHz, 4 ms edge fades, RMS moved toward -18 dBFS
(within +-9 dB), peak <= -1 dBFS, Ogg Vorbis q6.

    python -I tools/sounds/synth/make_synth.py app/src/main/assets/sounds/synth [--wav-dir DIR]

Needs numpy and ffmpeg on PATH.
"""
import argparse
import os
import subprocess
import sys
import tempfile

import numpy as np

SR = 48000
TARGET_RMS_DB = -18.0
MAX_GAIN_DB = 9.0
PEAK_DB = -1.0


def t_axis(sec):
    return np.arange(int(round(sec * SR))) / SR


def env_ad(n, attack=0.004, decay=0.06):
    """Fast attack, exponential decay envelope of n samples."""
    t = np.arange(n) / SR
    a = np.clip(t / max(attack, 1e-6), 0, 1)
    return a * np.exp(-t / max(decay, 1e-6))


def env_hold(n, attack=0.004, release=0.02):
    """Attack, flat sustain, linear release."""
    e = np.ones(n)
    a, r = int(attack * SR), int(release * SR)
    if a:
        e[:a] = np.linspace(0, 1, a)
    if r:
        e[-r:] *= np.linspace(1, 0, r)
    return e


def tone(freq, sec, harm=(1.0, 0.18, 0.06), fm_ratio=0.0, fm_index=0.0, phase=0.0):
    """Additive tone (a few harmonics) with optional sine FM; freq may be an array (sweep)."""
    n = int(round(sec * SR))
    f = np.broadcast_to(np.asarray(freq, dtype=np.float64), (n,)) if np.ndim(freq) else np.full(n, float(freq))
    t = np.arange(n) / SR
    mod = fm_index * np.sin(2 * np.pi * fm_ratio * f * t) if fm_index else 0.0
    ph = 2 * np.pi * np.cumsum(f) / SR + phase
    out = np.zeros(n)
    for k, amp in enumerate(harm, 1):
        out += amp * np.sin(k * ph + mod)
    return out


def beep(freq, sec, decay=0.05, **kw):
    x = tone(freq, sec, **kw)
    return x * env_ad(len(x), decay=decay)


def blip(freq, sec):
    """Flat TrekUI console blip: steady tone, quick release."""
    x = tone(freq, sec, harm=(1.0, 0.25, 0.08))
    return x * env_hold(len(x), 0.003, 0.012)


def silence(sec):
    return np.zeros(int(round(sec * SR)))


def cat(*parts):
    return np.concatenate(parts)


def mix(*parts):
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[:len(p)] += p
    return out


def lowpass(x, cutoff):
    """One-pole low-pass (deterministic, no scipy)."""
    a = np.exp(-2 * np.pi * cutoff / SR)
    y = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc
        y[i] = acc
    return y


def noise(sec, seed, cutoff=3000):
    rng = np.random.default_rng(seed)
    return lowpass(rng.standard_normal(int(round(sec * SR))), cutoff)


# ---------------------------------------------------------------- the sounds

TAP_FREQS = [1318.5, 1568.0, 1174.7, 1760.0, 1396.9, 2093.0]


def snd_tap(i):
    f = TAP_FREQS[i]
    # short console beep with a tiny upward FM flick for an "electronic" edge
    return beep(f, 0.075, decay=0.035, fm_ratio=0.5, fm_index=0.4 + 0.1 * i)


def snd_nav():
    return cat(blip(880.0, 0.055), silence(0.015), blip(1318.5, 0.07))


def snd_launch():
    notes = [784.0, 1046.5, 1318.5, 1568.0]
    parts = []
    for k, f in enumerate(notes):
        parts.append(blip(f, 0.05 if k < 3 else 0.11))
        parts.append(silence(0.012))
    return cat(*parts)


def snd_confirm():
    # bell-like FM chime, major third
    a = beep(1318.5, 0.45, decay=0.14, fm_ratio=1.4, fm_index=1.2, harm=(1.0, 0.1))
    b = beep(1661.2, 0.40, decay=0.12, fm_ratio=1.4, fm_index=1.0, harm=(1.0, 0.1))
    return mix(a, cat(silence(0.07), b))


def snd_error():
    def buzz(f, sec):
        x = tone(f, sec, harm=(1.0, 0.0, 0.33, 0.0, 0.2, 0.0, 0.14))  # odd harmonics: square-ish
        return lowpass(x * env_hold(len(x), 0.004, 0.03), 2500)
    return cat(buzz(392.0, 0.13), silence(0.03), buzz(293.7, 0.22))


def snd_comms():
    # rising FM chirp pair, like a hail
    def chirp(f0, f1, sec):
        f = np.linspace(f0, f1, int(round(sec * SR)))
        x = tone(f, sec, harm=(1.0, 0.2), fm_ratio=2.0, fm_index=0.6)
        return x * env_hold(len(x), 0.004, 0.025)
    return cat(chirp(1200, 2200, 0.09), silence(0.05), chirp(1400, 2600, 0.11), silence(0.03), blip(2093.0, 0.08))


def snd_diag():
    rng = np.random.default_rng(1701)
    scale = [1046.5, 1174.7, 1318.5, 1568.0, 1760.0, 2093.0, 2349.3]
    parts = []
    for _ in range(9):
        f = scale[rng.integers(len(scale))]
        parts.append(blip(f, float(rng.choice([0.035, 0.045, 0.06]))))
        parts.append(silence(float(rng.choice([0.012, 0.02, 0.03]))))
    return cat(*parts)


def snd_red_alert():
    """One seamless 1.0 s klaxon cycle (loopable): rising swept saw-like tone plus a low pulse."""
    sec = 1.0
    n = int(sec * SR)
    t = np.arange(n) / SR
    sweep = 420 + 360 * np.clip(t / 0.75, 0, 1) ** 0.8          # 420 -> 780 Hz over 0.75 s
    x = tone(sweep, sec, harm=(1.0, 0.5, 0.33, 0.25, 0.2))
    x = lowpass(x, 3200)
    e = np.ones(n)
    a, r = int(0.01 * SR), int(0.12 * SR)
    e[:a] = np.linspace(0, 1, a)
    e[int(0.78 * SR):int(0.78 * SR) + r] = np.linspace(1, 0, r)
    e[int(0.78 * SR) + r:] = 0
    return x * e


def snd_boot():
    """Power-up sweep with a soft noise whoosh and a closing chime."""
    sec = 1.2
    n = int(sec * SR)
    t = np.arange(n) / SR
    f = 180 * (2200 / 180) ** (t / sec)
    sw = tone(f, sec, harm=(1.0, 0.3, 0.1)) * np.clip(t / 0.15, 0, 1) * np.clip((sec - t) / 0.08, 0, 1) * 0.6
    wh = noise(sec, 47, cutoff=1800) * np.sin(np.pi * t / sec) ** 2 * 0.35
    end = cat(silence(sec - 0.05), beep(1568.0, 0.4, decay=0.12, fm_ratio=1.4, fm_index=0.8))
    return mix(sw + wh, end)


# ---------------------------------------------------------------- Imperial family: harsh, metallic, low

def drive(x, amount=3.0):
    """tanh waveshaper: grit without changing pitch."""
    return np.tanh(amount * x) / np.tanh(amount)


def clang(f, sec, decay, ratio=1.41, index=3.0):
    """Inharmonic FM strike (metal plate)."""
    return beep(f, sec, decay=decay, fm_ratio=ratio, fm_index=index, harm=(1.0, 0.0, 0.25))


KL_TAP = [(392.0, 1.41), (466.2, 1.53), (349.2, 1.37), (523.3, 1.61)]


def kl_tap(i):
    f, r = KL_TAP[i]
    x = clang(f, 0.08, 0.03, ratio=r, index=4.0) + 0.5 * beep(f / 2, 0.08, decay=0.02, harm=(1.0, 0.0, 0.33, 0.0, 0.2))
    return drive(x * 0.6, 2.5)


def kl_nav():
    def blk(f, sec):
        x = tone(f, sec, harm=(1.0, 0.0, 0.33, 0.0, 0.2)) * env_hold(int(round(sec * SR)), 0.003, 0.015)
        return drive(x * 0.7, 3.0)
    return cat(blk(330.0, 0.06), silence(0.012), blk(440.0, 0.08))


def kl_launch():
    sec = 0.32
    t = t_axis(sec)
    f = 200 * (900 / 200) ** (t / sec)
    x = tone(f, sec, harm=(1.0, 0.5, 0.33, 0.25)) * env_hold(len(t), 0.005, 0.05)
    return lowpass(drive(x * 0.6, 3.5), 4000)


def kl_confirm():
    return mix(clang(440.0, 0.35, 0.09), cat(silence(0.09), clang(587.3, 0.4, 0.11)))


def kl_error():
    sec = 0.36
    x = tone(110.0, sec, harm=(1.0, 0.5, 0.33, 0.25, 0.2)) + tone(82.4, sec, harm=(1.0, 0.5, 0.33))
    return lowpass(drive(x * 0.5 * env_hold(len(x), 0.004, 0.06), 4.0), 1800)


def kl_comms():
    parts = []
    for k in range(3):
        sec = 0.07
        f = np.linspace(600 + 80 * k, 1200 + 80 * k, int(round(sec * SR)))
        x = tone(f, sec, fm_ratio=1.5, fm_index=2.5) * env_hold(len(f), 0.003, 0.02)
        parts += [drive(x * 0.6, 2.0), silence(0.035)]
    return cat(*parts)


def kl_diag():
    rng = np.random.default_rng(1040)
    parts = []
    for _ in range(8):
        f = float(rng.choice([220.0, 261.6, 293.7, 349.2, 392.0]))
        x = tone(f, 0.045, harm=(1.0, 0.0, 0.33, 0.0, 0.2)) * env_hold(int(round(0.045 * SR)), 0.002, 0.01)
        parts += [drive(x * 0.7, 2.5), silence(float(rng.choice([0.015, 0.025])))]
    return cat(*parts)


def kl_red_alert():
    """Seamless 1.0 s loop: two harsh alternating tones (175 + 130 whole cycles, phase-continuous)."""
    n = SR
    f = np.tile(np.where(np.arange(n) < n // 2, 350.0, 260.0), 2)
    x = tone(f, 2.0, harm=(1.0, 0.0, 0.33, 0.0, 0.2, 0.0, 0.14))
    # filter two cycles and keep the second, so the filter state at the loop point is the steady one
    return lowpass(drive(x * 0.6, 3.0), 3500)[n:]


def kl_boot():
    hits = []
    for k, f in enumerate([65.4, 65.4, 98.0]):
        hits += [drive(beep(f, 0.18, decay=0.06, harm=(1.0, 0.5, 0.2)) + 0.3 * noise(0.18, 300 + k, 900) * env_ad(int(0.18 * SR), decay=0.03), 2.0),
                 silence(0.06)]
    return cat(*hits, kl_launch())


# ---------------------------------------------------------------- Collective family: cold, digital, drone

def crush(x, levels=12):
    """Amplitude quantisation: the cold, stepped 'digital' edge."""
    return np.round(x * levels) / levels


BG_TAP = [2637.0, 3136.0, 2349.3, 3520.0]


def bg_tap(i):
    x = tone(BG_TAP[i], 0.04, harm=(1.0,)) * env_hold(int(0.04 * SR), 0.001, 0.008)
    return crush(x * 0.8, 6)


def bg_burst(seed, count, sec_each=0.018):
    rng = np.random.default_rng(seed)
    parts = []
    for _ in range(count):
        f = float(rng.choice([1760.0, 2093.0, 2637.0, 3136.0, 3520.0]))
        parts += [tone(f, sec_each, harm=(1.0,)) * env_hold(int(round(sec_each * SR)), 0.001, 0.003), silence(0.006)]
    return crush(cat(*parts) * 0.8, 8)


def bg_nav():
    return bg_burst(2001, 6)


def drone(sec, base=55.0):
    return tone(base, sec, harm=(1.0, 0.7, 0.5, 0.0, 0.3))


def bg_launch():
    sec = 0.5
    t = t_axis(sec)
    x = drone(sec) * np.clip(t / 0.3, 0, 1) * np.clip((sec - t) / 0.08, 0, 1)
    return mix(x * 0.7, cat(silence(0.3), bg_burst(2002, 4)))


def bg_confirm():
    a = tone(1318.5, 0.14, harm=(1.0,)) * env_hold(int(0.14 * SR), 0.002, 0.02)
    b = tone(1975.5, 0.18, harm=(1.0,)) * env_hold(int(0.18 * SR), 0.002, 0.03)
    return crush(cat(a, silence(0.02), b) * 0.8, 10)


def bg_error():
    sec = 0.32
    t = t_axis(sec)
    gate = (np.sin(2 * np.pi * 12.5 * t) > 0).astype(float)
    return drone(sec, 65.4) * gate * env_hold(len(t), 0.003, 0.03)


def bg_comms():
    """Modem-like frequency-shift keying (seeded bit pattern)."""
    rng = np.random.default_rng(2003)
    bits = rng.integers(0, 2, 18)
    f = np.repeat(np.where(bits == 1, 2200.0, 1200.0), int(0.02 * SR))
    x = tone(f, len(f) / SR, harm=(1.0,)) * env_hold(len(f), 0.004, 0.02)
    return crush(x * 0.7, 10)


def bg_diag():
    return bg_burst(2004, 14, 0.022)


def bg_red_alert():
    """Seamless 1.0 s loop: 55 Hz drone + an 880 Hz tone gated at 4 Hz (all whole cycles)."""
    t = np.arange(SR) / SR
    gate = 0.5 - 0.5 * np.cos(2 * np.pi * 4 * t)
    return 0.6 * drone(1.0) + 0.5 * tone(880.0, 1.0, harm=(1.0,)) * gate


def bg_boot():
    sec = 1.1
    t = t_axis(sec)
    f = 40 * (110 / 40) ** (t / sec)
    x = tone(f, sec, harm=(1.0, 0.7, 0.5, 0.3)) * np.clip(t / 0.4, 0, 1) * np.clip((sec - t) / 0.1, 0, 1)
    return mix(x * 0.7, cat(silence(0.7), bg_burst(2005, 10)))


SOUNDS = {
    'synth_tap_1': lambda: snd_tap(0), 'synth_tap_2': lambda: snd_tap(1), 'synth_tap_3': lambda: snd_tap(2),
    'synth_tap_4': lambda: snd_tap(3), 'synth_tap_5': lambda: snd_tap(4), 'synth_tap_6': lambda: snd_tap(5),
    'synth_nav': snd_nav, 'synth_launch': snd_launch, 'synth_confirm': snd_confirm, 'synth_error': snd_error,
    'synth_comms': snd_comms, 'synth_diag': snd_diag, 'synth_red_alert': snd_red_alert, 'synth_boot': snd_boot,
}
for _fam, _tap, _fns in (
        ('kl', kl_tap, (kl_nav, kl_launch, kl_confirm, kl_error, kl_comms, kl_diag, kl_red_alert, kl_boot)),
        ('bg', bg_tap, (bg_nav, bg_launch, bg_confirm, bg_error, bg_comms, bg_diag, bg_red_alert, bg_boot))):
    for _i in range(4):
        SOUNDS['synth_%s_tap_%d' % (_fam, _i + 1)] = (lambda f, i: (lambda: f(i)))(_tap, _i)
    for _part, _fn in zip(('nav', 'launch', 'confirm', 'error', 'comms', 'diag', 'red_alert', 'boot'), _fns):
        SOUNDS['synth_%s_%s' % (_fam, _part)] = _fn
# no fades/trim beyond what the generator does: these must loop seamlessly
LOOPS = {'synth_red_alert', 'synth_kl_red_alert', 'synth_bg_red_alert'}


# ---------------------------------------------------------------- processing / encoding

def finish(name, x):
    """Edge fades, gentle level match toward -18 dBFS RMS (+-9 dB), peak <= -1 dBFS."""
    x = np.asarray(x, dtype=np.float64)
    x = x * (0.5 / max(np.max(np.abs(x)), 1e-9))  # raw oscillator sums are unscaled; start from -6 dBFS peak
    if name not in LOOPS:
        f = min(int(0.004 * SR), len(x) // 4)
        x[:f] *= np.linspace(0, 1, f)
        x[-f:] *= np.linspace(1, 0, f)
    active = x[np.abs(x) > np.max(np.abs(x)) * 10 ** (-45 / 20)]
    rms = np.sqrt(np.mean(active ** 2))
    g = float(np.clip(TARGET_RMS_DB - 20 * np.log10(max(rms, 1e-9)), -MAX_GAIN_DB, MAX_GAIN_DB))
    x = x * 10 ** (g / 20)
    pk = np.max(np.abs(x))
    if pk > 10 ** (PEAK_DB / 20):
        x *= 10 ** (PEAK_DB / 20) / pk
    return x.astype(np.float32)


def encode(x, dst):
    with tempfile.NamedTemporaryFile(suffix='.f32', delete=False) as tmp:
        tmp.write(x.tobytes())
        raw = tmp.name
    try:
        subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-y', '-f', 'f32le', '-ar', str(SR), '-ac', '1', '-i', raw,
                        '-map_metadata', '-1', '-fflags', '+bitexact', '-flags:a', '+bitexact',
                        '-c:a', 'libvorbis', '-q:a', '6', dst], check=True)
    finally:
        os.remove(raw)


def write_wav(x, dst):
    import wave
    with wave.open(dst, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype('<i2').tobytes())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('out')
    ap.add_argument('--wav-dir', help='also write WAV previews here (not for committing)')
    o = ap.parse_args()
    os.makedirs(o.out, exist_ok=True)
    for name, fn in SOUNDS.items():
        x = finish(name, fn())
        encode(x, os.path.join(o.out, name + '.ogg'))
        if o.wav_dir:
            os.makedirs(o.wav_dir, exist_ok=True)
            write_wav(x, os.path.join(o.wav_dir, name + '.wav'))
        print('%-18s %.3f s' % (name, len(x) / SR))


if __name__ == '__main__':
    sys.exit(main())
