# Owned sounds

Only **self-made or properly licensed audio** may go in this folder. Whatever is here is published to
the public sound update channel on mindattic.com by `tools/sounds/publish-sfx.py` and downloaded by every
launcher, so it must be audio this project has the right to redistribute.

Never put show recordings here (trekcore, MediaCollege, the GitHub sound pack, or any other third-party
source in `core/src/main/assets/sounds/manifest.json`). Those stay user-downloaded from their original
public pages. The publish script refuses a file whose SHA-256 matches one of those originals, and an id
that is already a show-sound id.

- The file name is the sound id: `a-z`, `0-9` and `_` only, for example `owned_door_chime.ogg`.
- Ogg Vorbis is preferred (`.wav`, `.mp3`, `.m4a` and `.flac` also play). At most 5 MB per file.
- Record where each file came from (made by whom, or its license) in the commit that adds it.

See docs/SOUNDS.md, "Sound update channel (mindattic.com)".
