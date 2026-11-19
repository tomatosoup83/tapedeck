# Tapedeck

A Fabric client-side mod that shows the track currently playing on your Minecraft HUD:
**Apple Music** on macOS, or **any MPRIS-capable media player** on Linux.

```
┌───────────────────────────────┐
│ So Easy (To Fall In Love)     │
│ Olivia Dean                   │
│ 0:59 / 3:39                   │
└───────────────────────────────┘
```

The panel is drawn in the top-left corner, its border is green while playing and amber
while paused, and it fades in and out as tracks come and go. It renders only the track
metadata, so it never blocks the frame.

## How it works

Both platforms sit behind one interface (`NowPlayingSource`); everything above it — the
HUD, the immutable `NowPlayingInfo` snapshot, the play-position interpolation — is
platform-agnostic.

### macOS: Apple Music via AppleScript

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

### Linux: any MPRIS player via D-Bus

1. Players are found by well-known bus name: anything owning
   `org.mpris.MediaPlayer2.*`. A **single long-lived `busctl --user monitor` process**
   reports `PropertiesChanged`, `Seeked` and `NameOwnerChanged`, so while a track plays
   the mod does no work at all.
2. Several players can be on the bus at once, so one is **elected**: `Playing` beats
   `Paused` beats `Stopped`, then the configured `priority` order, then bus name. A
   browser's own player beats KDE's `plasma-browser-integration`, which usually
   re-exports the same audio. The incumbent is kept while tied, so the HUD cannot flap.
3. Every event triggers one `Properties.GetAll` per player. Note that `mpris:length` and
   `Position` are in **microseconds**, and that `xesam:artist` is an array.
4. `Position` is not signalled by the MPRIS specification, so the HUD interpolates
   between updates and the bridge re-reads the position every `resync-ms` while playing.
5. If `busctl monitor` cannot run — a Flatpak or other bus proxy may forbid monitoring —
   the bridge retries with backoff and then falls back to polling every `poll-ms`, which
   is exactly the macOS strategy.

Nothing here needs a native D-Bus binding: all bus access is short-lived `busctl`
processes, and the tiny JSON reader is part of the mod.

## Requirements

- macOS or Linux (Wayland and X11 are both fine; nothing touches the display server)
- Minecraft 26.3 with Fabric Loader 0.19.5+
- Java 25
- macOS: Apple Music installed
- Linux: systemd (`busctl`) and a player that speaks MPRIS — Firefox, Chromium, VLC,
  Spotify, Cider, mpv with `mpv-mpris`, …

## Building

```bash
./gradlew build   # jar lands in build/libs/
./gradlew test    # parser, config and player-election tests
```

## Configuration

`config/tapedeck.properties` is written with commented defaults on first launch:

```properties
# Playing beats Paused; then this order; then the bus name.
priority = firefox, vlc

# Never mirror these. Useful for a phone surfaced by KDE Connect, or for
# plasma-browser-integration duplicating a browser's own player.
ignore = kdeconnect, plasma-browser-integration

# Property re-read interval when the signal monitor is unavailable (ms).
poll-ms = 1000

# How often to correct the interpolated position while playing (ms).
resync-ms = 5000

# auto: signals with a polling fallback; monitor: signals only; poll: timer only.
mode = auto

# HUD size, as a percentage of the default layout (50-200).
hud-size = 100

# Start with the panel hidden (also toggled in game).
hud-hidden = false
```

Entries match case-insensitively against the bus name, so `firefox` matches
`org.mpris.MediaPlayer2.firefox.instance_1_135`. Ignore entries may be regular
expressions; a malformed one is treated as a literal.

## Commands and keys

All commands are client-side, so they work in single-player and on any server without
permissions:

```
/tapedeck                  show state and size, plus usage
/tapedeck toggle           hide or show the HUD (saved)
/tapedeck size             show the current size
/tapedeck size <percent>   resize the panel (50-200), applied immediately and saved
```

`size` is a zoom around the panel's top-left corner: the margin from the screen edge stays
fixed and the panel grows right and down. `size 100` restores the default.

The toggle also has a key binding — **F8** by default — listed under
**Options -> Controls -> Tapedeck**. Rebind, clear, or add a second binding the same way
as any vanilla key; the command and the key share one saved flag, so they never disagree.

## Diagnosing Linux playback

`scripts/mpris-probe.sh` prints exactly what the bridge would see, including its verdict:

```bash
./scripts/mpris-probe.sh              # every player, plus the one Tapedeck would mirror
./scripts/mpris-probe.sh --playing    # one tab-separated line, scriptable
./scripts/mpris-probe.sh --watch      # stream the signals the bridge subscribes to
./scripts/mpris-probe.sh --json       # raw properties, for bug reports and fixtures
```

## Troubleshooting on Linux

- **Probe lists nothing while audio plays.** Firefox and Chromium register MPRIS only
  while a media session is live, and abandon it when playback ends. If a page never
  creates a session, nothing is exported; muted or blocked-autoplay tabs produce none.
- **Apple Music in a browser** may need Widevine, and its web player commonly reports no
  duration — Tapedeck then shows elapsed time only (`0:59`) instead of a total.
- **mpv** needs the `mpv-mpris` plugin; `mpd` needs `mpd-mpris`. Players without MPRIS
  are invisible to the mod.
- **Flatpak launchers** may not see the session bus at all, and may forbid monitoring; the
  bridge logs the reason and falls back to polling.
- **Phone media** mirrored by KDE Connect can win the election. Add `kdeconnect` to
  `ignore`, or pin your player in `priority`.

## macOS automation permission (TCC)

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

- Text only. Album art is not drawn yet; on Linux `mpris:artUrl` is already a local file,
  so it is the obvious next feature.
- No transport controls (play/pause/next). MPRIS exposes them, so a Linux-only first pass
  would be cheap.
- Playback position is interpolated between updates rather than queried per frame.
- HUD sizes above 100% resample Minecraft's bitmap font, so text is slightly softer at
  non-integer scales; 100% is pixel-exact.
- Radio streams with no metadata show nothing.
- Windows is not supported: the mod loads and shows no panel.

## Layout

```
src/main/java/dev/tapedeck/
  TapeDeckMod.java          client entrypoint; picks a source, registers the HUD element
  music/
    NowPlayingSource.java   the platform seam (start / snapshot / diagnostics / close)
    SourceFactory.java      macOS, Linux, or unsupported
    AppleMusicSource.java   macOS: process supervision, osascript lifecycle, line parsing
    MprisSource.java        Linux: busctl monitor, polling fallback, player tracking
    MprisPlayer.java        one MPRIS player's properties (microseconds preserved)
    PlayerElection.java     which player to mirror when several are on the bus
    TapedeckConfig.java     config/tapedeck.properties
    Json.java               minimal JSON reader for busctl --json=short output
    NowPlayingInfo.java     immutable snapshot + play-position interpolation
  hud/
    NowPlayingHud.java      layout and drawing
src/test/java/dev/tapedeck/music/
  JsonTest.java  MprisPlayerTest.java  PlayerElectionTest.java  TapedeckConfigTest.java
scripts/mpris-probe.sh      diagnose the D-Bus side of the Linux bridge
```

## Tuning

`resync-ms` trades timecode accuracy against bus traffic; the default keeps the panel
correct to well under a second while costing one property read every five seconds.
`poll-ms` only matters when signal monitoring is unavailable. Going much below 500 ms
gains nothing, because players update their own position at roughly 1 Hz anyway.

## License

MIT
