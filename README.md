# Dragon Ghast

A Paper plugin (Minecraft 26.3, Java 25). Right-click a grown happy ghast with **dragon's breath** and it changes for good:

- **Faster**: it flies twice as fast while someone rides it (`ridden-speed-multiplier`).
- **Looks like a regular ghast**, but is still tame: it still wears a harness, can be ridden and sheared as usual.
- **Subtle purple breath** drips off its tentacles, with a wake when it flies fast.

One breath is used per ghast (the glass bottle comes back; creative mode uses nothing). Baby ghasts and ghasts that already had it refuse.

## The ghast look needs a resource pack

The regular-ghast face is drawn by a resource pack, sent to players by another plugin (FieryWings, pack id
`nameUUIDFromBytes("fierywings:pack")`; see `LOOK_PACK` in `GhastBreathPlugin`). Players without the pack just see a normal happy ghast; the speed and particles still work.

The pack files are in `pack/` (equipment assets `ghastbreath:ghast_face` and `ghastbreath:ghast_<colour>_harness`, plus the regular ghast body laid out for the harness model). Merge that folder into whatever pack you send. `tools/make_pack_assets.py [pack-folder]` regenerates them from the game's own textures.

## Build

```
./gradlew build
```

The jar lands in `build/libs/`. Config is in `config.yml`: `item`, `ridden-speed-multiplier`, `ghast-look`, `particles`.
