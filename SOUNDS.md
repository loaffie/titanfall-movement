# Movement sounds

The mod plays a Titanfall 2 sound for every movement action, but it does **not** ship any audio
files: Titanfall 2's sound effects are Respawn/EA copyrighted assets, so the `.ogg` files have to
come from your own copy of the game. Add them once and every action plays the real thing.

## Required files

Drop these files into `src/main/resources/assets/titanfall_movement/sounds/movement/` and rebuild
with `./gradlew build`:

| File | Played when |
| --- | --- |
| `wallrun_start.ogg` | latching onto a wall |
| `wallrun_loop.ogg` | while wall running (loops) |
| `walljump.ogg` | jumping off a wall |
| `slide_start.ogg` | starting a slide |
| `slide_loop.ogg` | while sliding (loops) |
| `jump.ogg` | hopping out of a slide |
| `double_jump.ogg` | mid-air double jump |
| `land.ogg` | landing after a fall of a block or more |

The paths are gitignored, so your extracted audio never ends up in the repository. Minecraft
only accepts Ogg Vorbis (`.ogg`) files; convert them first if your rips are in another format.

## Without rebuilding

Any resource pack can provide the same files under
`assets/titanfall_movement/sounds/movement/`. The mod's `sounds.json` points at exactly those
paths, so a pack containing only the `.ogg` files is enough. Silence is what you get until then.

## Adjusting

`config/titanfall_movement.json` (or the Mod Menu config screen) has `soundsEnabled` and
`movementVolume` if you want the sounds quieter or off.
