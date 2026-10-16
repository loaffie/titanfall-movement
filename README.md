# Titanfall Movement

Accurate Titanfall 2 movement physics for Fabric Minecraft 1.21.11.

## Movement

- **Air-strafing** &mdash; hold `A`/`D` while turning the camera to bend your mid-air velocity along the turn without losing speed.
- **Wall running** &mdash; jump into a wall while sprinting to lock onto it for up to 2 seconds. Press jump again to wall jump outward with an upward boost.
- **Sliding** &mdash; press crouch while sprinting on the ground for a low-friction slide. Jump out of a slide to keep your momentum and slide hop.
- **Double jump** &mdash; press jump once while airborne to gain extra height while keeping all horizontal momentum.

## Configuration

Every value lives in `config/titanfall_movement.json` and is read live, so edits apply without
restarting. With [Mod Menu](https://modrinth.com/mod/modmenu) installed the same options are
available as sliders in the mod list, including the sound toggle and volume.

## Sounds

Each movement action plays a Titanfall 2 sound effect (wall run, wall jump, slide, jump, double
jump, landing). The audio itself is not bundled because it is Respawn/EA copyrighted material
&mdash; see [SOUNDS.md](SOUNDS.md) for the file names and where to drop your own copies in.

## Building

Requires JDK 25 for the Gradle build:

```
./gradlew build
```

The built jar ends up in `build/libs`.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
