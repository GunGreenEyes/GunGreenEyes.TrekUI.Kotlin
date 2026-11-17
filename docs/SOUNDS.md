# Sounds

The app ships **no audio recordings**. Out of the box it plays its own synthesized sounds (below).
The Google Play (production) build also contains a manifest that describes every third-party sound the
launcher can use and where the original file is publicly posted. Those play only after the user
downloads them. With nothing present at all, the events are silent and nothing breaks.

## Out of the box: the synthesized set (LCA-T15)

Every build, including the Play build, ships the launcher's **own synthesized console-style sounds**
in `app/src/main/assets/sounds/synth/`. `tools/sounds/synth/make_synth.py` computes them from
sine/FM oscillators, odd-harmonic waveshaping and seeded low-passed noise. No recording, sample or
third-party audio is involved, so committing them is allowed. `.gitignore` has a narrow exception
for exactly that folder.

| Id | Event | Sound |
|---|---|---|
| `synth_tap_1` … `synth_tap_6` | button press (rotation) | 75 ms console beeps (E6, G6, D6, A6, F6, C7) with a small FM flick |
| `synth_nav` | navigation | two-blip rising chirp |
| `synth_launch` | app launch | four-note rising arpeggio |
| `synth_confirm` | confirmation | FM bell chime, major third |
| `synth_error` | error | low, descending, square-ish two-tone buzz |
| `synth_comms` | communications | two rising FM chirps and a blip (a hail) |
| `synth_diag` | diagnostics | seeded run of computer blips |
| `synth_red_alert` | red alert | seamless 1.0 s klaxon cycle (rising swept tone), loopable |
| `synth_boot` | start-up | power-up sweep, noise whoosh, closing chime |

The processing follows the 5.x notes: mono, 48 kHz, 4 ms edge fades (none on the loop), RMS
toward −18 dBFS within ±9 dB, peak ≤ −1 dBFS, Ogg Vorbis q6. The fixed seeds and ffmpeg's bit-exact
flags make the output reproducible:
`python -I tools/sounds/synth/make_synth.py app/src/main/assets/sounds/synth`.

**SOUND SET** (CONFIG › AUDIO) chooses what the events play when they have no imported file:

- **DOWNLOADED LIBRARY** (default): each event's library clip if it is downloaded or bundled,
  otherwise its synthesized clip. A fresh Play install therefore plays the synthesized set, and
  downloading the library upgrades each event as its clip arrives.
- **SYNTHESIZED**: only the synthesized clips.
- **SILENT**: no built-in sounds. Imported files still play.

Every interface has a sound for every event out of the box, each from its own synthesized family:

| Family | Ids | Character |
|---|---|---|
| Console (Command and the two extra eras) | `synth_*` | clean sine/FM beeps, chirps, chimes, klaxon |
| Imperial | `synth_kl_*` | harsh and low: FM metal-plate clangs, tanh-driven square tones, alternating 350/260 Hz alarm |
| Collective | `synth_bg_*` | cold and digital: quantised pure-sine clicks, data bursts, 55 Hz drone, modem-like FSK, gated drone alarm |
| Lounge (the six pipe interfaces) | `synth_pp_*` | warm and analogue: soft wood knock and brass click taps, page rustle (band-limited noise), match strike (crackle and hiss) for launch, gentle chime (confirm), low thud (error), mellow bell (comms), two-bell alarm, warm pad swell at start-up |

Each family has 4 tap beeps (the console set has 6) plus nav, launch, confirm, error, comms, diag,
a seamless 1.0 s `red_alert` loop, and `boot`. Imperial and Collective keep the library clips the 5.x
bundle chose for some events (for example `ui_denied` or the Collective computer); with the library
missing, those events fall back to the family's synthesized clip. The user can still set any event
to SILENT. The synthesized clips are listed in the sound library under SYNTHESIZED, SYNTHESIZED
IMPERIAL, SYNTHESIZED COLLECTIVE and SYNTHESIZED LOUNGE, so they can be assigned anywhere or exported as a
ringtone. Start-up (BootSequence / Stations `boot_start`) falls back to the interface's synthesized
`boot` sound.

## How it works on the phone

1. **SETTINGS › AUDIO › DOWNLOAD SOUND LIBRARY** (or **DOWNLOAD CORE SOUNDS** for only the clips the
   interfaces use by default). The dialog states the download size, the sources and the copyright
   notice before anything is fetched.
2. `SoundDownloader` reads `app/src/main/assets/sounds/manifest.json` (`SoundCatalog`). On one
   background thread it downloads each original once over HTTPS (15 s connect / 30 s read timeouts,
   40 MB cap, generic User-Agent, no personal data). It then checks the file's SHA-256 against the
   manifest. On a mismatch or error it tries the listed alternates, which are byte-identical copies
   on other sites.
3. `SoundDecoder` decodes the original with MediaExtractor/MediaCodec. `SoundProcessor` applies the
   manifest recipe: cut `[start, end)` seconds, fold to mono when `mono` is true, multiply by
   `10^(gain_db/20)`, apply linear `fade_in_ms`/`fade_out_ms` fades, clamp, then write 16-bit WAV to
   `filesDir/sounds/<id>.wav`. The original download is then deleted.
4. Playback (`Sfx`, `SoundLibrary`, `PhoneSounds`) resolves a sound id in this order (`SoundCache`):
   **downloaded cache → res/raw (personal develop builds only) → synthesized asset → silence**.

Runs that fail partway can be resumed: clips already in the cache are skipped. **DELETE DOWNLOADED
SOUNDS** removes the whole cache. Imported files (IMPORT FILES / ZIP) are separate and unaffected.

## Manifest format (`assets/sounds/manifest.json`)

```json
{
  "format": 1,
  "notice": "...",
  "sounds": [{
    "id": "tap_rot_1",               // the res/raw name the app uses; [a-z0-9_] only
    "label": "TAP BEEP 1", "group": "LAUNCHER",
    "events": ["command:button"], // interface defaults / station uses; "core" = non-empty
    "core": true, "available": true,
    "confidence": 0.999, "match": "high",
    "source": {"url": "https://www.trekcore.com/audio/computer/scrshow.mp3",
               "page": "https://www.trekcore.com/audio/", "site": "trekcore",
               "sha256": "...", "bytes": 12345, "title": "Screen Show"},
    "alternates": [{"url": "https://raw.githubusercontent.com/...", "sha256": "...same..."}],
    "start": 0.025, "end": 0.113, "gain_db": 6.4, "mono": true,
    "fade_in_ms": 2, "fade_out_ms": 4,
    "similar": [ ... ]               // other non-identical files that also match (information only)
  }]
}
```

A sound with `"available": false` has no confident public source. It stays in the list so the user
can import their own file for it.

## Sources

| Site | Page | Used for |
|---|---|---|
| trekcore.com | https://www.trekcore.com/audio/ | almost everything (computer, console, alerts, weapons, warp, transporter, ambient, ...) |
| GitHub sound pack | https://github.com/wofsonofmoag/lcars-sounds | the 38-file pack earlier private builds started from (identical copies as alternates) |
| MediaCollege | https://www.mediacollege.com/downloads/sound-effects/star-trek/tos/ | older-series effects (candidates / alternates) |
| willbswift | https://willbswift.github.io/treksounds/ | candidates / alternates |
| S. L. Spencer sound archive | https://www.slspencer.com/Sounds/Star%20Trek%20Sounds/LCARS%20Audio/ | candidates / alternates |

Attribution: the recordings belong to their copyright holders. The sites above
host them. The app does not redistribute them, and the user downloads them for personal use on their
own device. Neither the app nor its developer claims any rights in them.

## Licensing stance

- No recording is committed to this repository or packaged in any APK/AAB built by CI. `.gitignore`
  blocks `*.mp3`, `*.ogg`, `*.wav` and `personal_sounds/`.
- The manifest contains only facts about where public files are (URL, size, hash) and numeric
  processing parameters. It contains no audio.
- Downloads are started explicitly by the user after a notice. Files are stored app-private and
  excluded from backup.
- If a rights holder objects, removing an entry from the manifest (or the whole file) disables it.
  The app keeps working silently, and import of the user's own files still works.

## Rebuilding the manifest (`tools/sounds/`)

```
tools/sounds/run_all.sh <work dir outside the repo> [<dir of the 5.2 reference clips>]
```

- `crawl.py`: fetches each index page and every linked audio file, sequentially, with a 0.7 s delay
  and a generic User-Agent. It writes `<work>/<site>/catalog.json` (URL, page, link text, section,
  size, SHA-256) and can be resumed.
- `match.py`: decodes everything with ffmpeg. A **coarse** pass slides each app clip over every
  candidate file using a gain-invariant 48-band log-mel spectrogram (per-frame mean removed) with
  FFT cross-correlation. A filename/duration prefilter only chooses which files take part. A
  **fine** pass runs normalised waveform cross-correlation at 48 kHz, the rate the 5.x pipeline used,
  to get the exact start sample. Gain is a least-squares fit over the clip interior. Confidence is
  the waveform correlation (1.0 means identical).
- `build_manifest.py`: picks a source per sound, preferring the file named in the 5.x notes when it
  scores within 0.03 of the best. It writes the asset. With `--verify` it re-renders each recipe as
  the app does and compares the result with the 5.2 reference clip.

Downloads are untrusted data. The scripts run with `python -I`, read them only as bytes or through
ffmpeg, and keep the work folder outside `tools/sounds/`. The reference clips (the owner's
gitignored `personal_sounds`) never leave the build machine.

## Match results

All **425** sound ids the app knows were found on a public page with high confidence. Confidence is
the normalised waveform correlation at the best offset: minimum 0.910, and 403 of 425 are at 0.99 or
above. Every recipe was then re-rendered from the downloaded original exactly as the app renders it
and compared with the 5.2 reference clip: all 425 correlate at 0.92 or better, with level within
±1 dB.

- Originals to download: 383 files, 110.6 MB for the full library. The **core** set (58 sounds the
  interfaces, stations and start-up use by default) is 48 files, 3.3 MB.
- Rendered cache: about 230 MB for the full library, mostly the 14 stereo 40 s ambient excerpts.
  The core set is a few MB.
- Every primary source is trekcore.com. 81 sounds also have a byte-identical alternate (the GitHub
  sound pack or willbswift).

| Group | Sounds | Available | Min confidence | Min verify corr |
|---|---|---|---|---|
| LAUNCHER | 30 | 30 | 0.910 | 0.923 |
| ALERTS | 5 | 5 | 0.998 | 0.997 |
| AMBIENT | 19 | 19 | 0.991 | 0.999 |
| COMPUTER ALERT | 36 | 36 | 0.996 | 0.993 |
| COMMS | 20 | 20 | 0.932 | 0.997 |
| COMMUNICATOR | 3 | 3 | 0.996 | 0.993 |
| CONSOLE | 103 | 103 | 0.992 | 0.988 |
| COMPUTER | 109 | 109 | 0.993 | 0.993 |
| DOORS | 12 | 12 | 0.973 | 0.973 |
| LIFT | 5 | 5 | 0.990 | 0.990 |
| SHIPBOARD | 14 | 14 | 0.987 | 0.987 |
| REPLICATOR | 1 | 1 | 0.999 | 0.999 |
| SCANNER | 12 | 12 | 0.996 | 0.996 |
| TRANSPORTER | 12 | 12 | 0.995 | 0.995 |
| VIEWSCREEN | 2 | 2 | 0.998 | 0.997 |
| WARP | 15 | 15 | 0.986 | 0.986 |
| WEAPONS | 20 | 20 | 0.981 | 0.981 |
| STATION | 7 | 7 | 0.998 | 0.998 |

Interface (launcher) clips:

| Id | Original | Cut | Gain | Confidence |
|---|---|---|---|---|
| `alert_general_short` | alarm03.mp3 | 0.020–0.525 s | -4.8 dB | 1.000 |
| `alert_red_tng` | tng_red_alert3.mp3 | 0.020–1.005 s | -7.2 dB | 0.999 |
| `alert_warning` | alert04.mp3 | 0.069–1.536 s | -3.6 dB | 0.998 |
| `alt_borg_computer` | borg_computer_beep.mp3 | 0.120–1.320 s | +1.5 dB | 0.987 |
| `alt_forcefield_touch` | forcefield_touch.mp3 | 0.140–1.057 s | +1.4 dB | 0.997 |
| `alt_klingon_transporter` | klingon_transporter_clean.mp3 | 0.440–12.400 s | +3.7 dB | 0.978 |
| `comms_hail` | hailbeep_clean.mp3 | 0.020–0.309 s | -11.9 dB | 1.000 |
| `comms_hailing_open` | hailingfrequencies_open3.mp3 | 0.020–1.180 s | +7.8 dB | 0.999 |
| `comms_incoming_transmission` | incomingtransmission_clean.mp3 | 0.020–2.208 s | -3.1 dB | 0.999 |
| `system_transfer_complete` | transfercomplete_clean.mp3 | 0.080–2.280 s | -3.3 dB | 0.998 |
| `tap_rot_1` | scrshow.mp3 | 0.025–0.110 s | +6.4 dB | 0.999 |
| `tap_rot_2` | scrshow.mp3 | 0.239–0.308 s | -3.6 dB | 0.991 |
| `tap_rot_3` | tng_viewscreen_on.mp3 | 0.015–0.106 s | +9.0 dB | 0.989 |
| `tap_rot_4` | tng_viewscreen_on.mp3 | 0.191–0.276 s | +8.9 dB | 0.985 |
| `tap_rot_5` | tng_viewscreen_off.mp3 | 0.109–0.200 s | +8.8 dB | 0.988 |
| `tap_rot_6` | tng_viewscreen_off.mp3 | 0.345–0.461 s | +8.3 dB | 0.910 |
| `tng_transporter` | tng_transporter1.mp3 | 0.020–3.680 s | -2.0 dB | 0.998 |
| `ui_button_tap` | tng_chirp_clean.mp3 | 0.024–0.409 s | -3.1 dB | 0.996 |
| `ui_denied` | denybeep3.mp3 | 0.020–0.525 s | +4.6 dB | 0.999 |
| `ui_panel_open` | scrshow.mp3 | 0.030–0.358 s | -1.0 dB | 0.999 |
| `ui_screen_close` | tng_viewscreen_off.mp3 | 0.021–0.550 s | +6.0 dB | 0.998 |
| `ui_screen_open` | tng_viewscreen_on.mp3 | 0.020–0.384 s | +6.0 dB | 0.998 |
| `voice_accessing_library` | accessinglibrarycomputerdata_clean.mp3 | 0.040–2.200 s | -4.8 dB | 0.999 |
| `voice_affirmative` | affirmative1_ep.mp3 | 0.080–1.057 s | -4.2 dB | 0.996 |
| `voice_autodefense` | autodefense_ep.mp3 | 0.080–2.300 s | -4.0 dB | 0.995 |
| `voice_command_codes_verified` | commandcodesverified_ep.mp3 | 0.020–2.232 s | -8.2 dB | 0.998 |
| `voice_command_functions_offline` | commandfunctionsoffline_ep.mp3 | 0.020–2.400 s | -4.1 dB | 0.997 |
| `voice_decoupling_sequence` | initiatingdecouplingsequence_ep.mp3 | 0.020–4.752 s | -9.3 dB | 0.998 |
| `voice_priority_one_message` | priorityonemessagefromstarfleet_ep.mp3 | 0.120–4.500 s | -6.5 dB | 0.999 |
| `voice_unable_to_comply` | unabletocomply1_ep.mp3 | 0.060–4.520 s | -8.8 dB | 0.998 |

Lowest scores: `tap_rot_6` (0.910). It is a 94 ms beep, and Vorbis pre-echo on the 5.2 reference
lowers the correlation; the audio is correct. `tk_ccomm_hail_beep_5` scores 0.932.
