# Tapedeck

A Fabric client-side mod that shows the track currently playing in **Apple Music** on your
Minecraft HUD.

```
┌──────────────────────────────┐
│▌ Title of the Song            │
│▌ Artist - Album               │
│▌ ▬▬▬▬▬▬▬▬▬▬░░░░░░  1:23/3:45 │
└──────────────────────────────┘
```

The accent stripe and progress bar are green while playing and amber while paused. The
panel fades in and out as tracks change.

## How it works

Minecraft has no notion of a system media player, so the mod asks macOS:

1. A **supervisor thread** checks whether a process named `Music` is alive, using
   `ProcessHandle`. (`NSWorkspace` is not used — it does not list Music when the app runs
   headless or in a background session.)
2. If it is alive, a **single long-lived `osascript -l JavaScript` process** is spawned.
   The embedded JXA script loops forever, calling `playerState`, `currentTrack`,
   `playerPosition` and `duration` once per poll.
3. Each result is written as one `US`-delimited line to stdout via the `NSFileHandle`
   bridge. (JXA's `console.log` writes to *stderr*.)
4. A reader thread parses the lines into an immutable `NowPlayingInfo` record and
   publishes it through an `AtomicReference`.
5. The HUD element reads that snapshot on the render thread. Nothing blocks on the bridge.

If Music quits, the supervisor tears the `osascript` process down instead of letting the
script relaunch it.

## Requirements

- macOS (tested on macOS 27.0.1)
- Minecraft 26.3 with Fabric Loader 0.19.5+
- Java 25
- Apple Music installed

## Building

```bash
./gradlew build
```

The jar lands in `build/libs/`.

## Automation permission (TCC)

The first time the mod talks to Music.app, macOS shows an automation consent dialog. If
you dismiss it, no track will ever appear and the log will say so.

The grant is attached to **whatever launched the JVM** — Terminal, IntelliJ, Prism
Launcher — not to Minecraft itself. So switching launchers means re-granting the
permission.

To fix a denial: **System Settings → Privacy & Security → Automation**, find your
launcher, and enable "Music".

If you launch from a terminal in an SSH session or a headless context, the dialog cannot
appear at all; run the game from a logged-in GUI session.

## Limitations

- Text only. Album artwork is deliberately omitted — AppleScript returns it as raw PICT
  data, and reading it without the private `MediaRemote` framework is fiddly.
- Playback position is reported once per poll, so the HUD interpolates between updates
  instead of querying more aggressively.
- Radio streams with no metadata show nothing.
- Only Apple Music. Spotify's equivalent is a different API entirely.

## Layout

```
src/main/java/dev/tapedeck/
  TapeDeckMod.java          client entrypoint; owns the bridge, registers the HUD element
  music/
    MusicBridge.java        process supervision, osascript lifecycle, line parsing
    NowPlayingInfo.java     immutable snapshot + play-position interpolation
  hud/
    NowPlayingHud.java      layout and drawing
```

## Tuning

`MusicBridge.create(pollIntervalMs)` takes a poll interval; the default is 1000 ms. Going
much below 500 ms increases AppleScript load for little benefit, since Music.app updates
its own position at roughly 1 Hz anyway.

## License

MIT