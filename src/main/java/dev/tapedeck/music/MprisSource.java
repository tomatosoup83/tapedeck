package dev.tapedeck.music;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the now-playing state from any MPRIS player on the D-Bus session bus.
 *
 * <p>Design notes, and how they differ from the macOS bridge:
 *
 * <ul>
 *   <li><b>Event-driven where possible.</b> MPRIS publishes {@code PropertiesChanged},
 *       {@code Seeked} and {@code NameOwnerChanged} signals, so a long-lived
 *       {@code busctl monitor} process replaces AppleScript's polling loop. While a
 *       track plays, the mod does almost no work.</li>
 *   <li><b>Polling is the fallback, not the norm.</b> A Flatpak or other bus proxy can
 *       forbid monitoring (it is an eavesdropping-style connection). After a few instant
 *       monitor deaths the bridge permanently switches to a timer, which is exactly the
 *       macOS strategy.</li>
 *   <li><b>{@code Position} is not signalled</b> by the specification, so it is
 *       interpolated between updates and corrected occasionally with a single
 *       {@code get-property} call, rather than re-reading every property each second.</li>
 *   <li><b>Many players, one HUD.</b> Election lives in {@link PlayerElection}, driven by
 *       {@link TapedeckConfig}; discovery is by well-known bus name ownership, which also
 *       replaces the macOS {@code ProcessHandle} liveness probe.</li>
 * </ul>
 *
 * <p>All bus access goes through short-lived {@code busctl} processes, so the mod needs no
 * native D-Bus binding and the jar stays dependency-free.
 */
public final class MprisSource implements NowPlayingSource {

	private static final Logger LOG = LoggerFactory.getLogger("tapedeck/mpris");

	private static final String NAME_PREFIX = "org.mpris.MediaPlayer2.";
	private static final String PLAYER_PATH = "/org/mpris/MediaPlayer2";
	private static final String PLAYER_IFACE = "org.mpris.MediaPlayer2.Player";
	private static final String PROPS_IFACE = "org.freedesktop.DBus.Properties";
	private static final String DBUS_IFACE = "org.freedesktop.DBus";

	private static final long SUPERVISOR_INTERVAL_MS = 2_000L;
	private static final long TICK_INTERVAL_MS = 500L;
	private static final long FAST_FAILURE_THRESHOLD_MS = 3_000L;
	private static final long MAX_BACKOFF_MS = 30_000L;
	private static final long COMMAND_TIMEOUT_MS = 2_000L;
	private static final long REPEAT_LOG_INTERVAL_MS = 30_000L;
	private static final int MONITOR_FAILURES_BEFORE_POLL = 3;

	private static final String NO_BUS_HELP =
			"No D-Bus session bus (or busctl from systemd is missing). Launch the game from "
					+ "your desktop session; SSH, systemd units and some Flatpak sandboxes "
					+ "cannot see the user bus.";

	private final TapedeckConfig config;
	private final AtomicReference<NowPlayingInfo> current =
			new AtomicReference<>(NowPlayingInfo.unavailable(""));
	private final Map<String, MprisPlayer> players = new ConcurrentHashMap<>();
	private final Object lifecycleLock = new Object();
	private final Object logLock = new Object();
	private final StringBuilder monitorErrors = new StringBuilder();

	private volatile Process monitorProcess;
	private volatile Thread supervisor;
	private volatile Thread ticker;
	private volatile boolean running;
	private volatile boolean busAvailable = true;
	private volatile boolean pollOnly;
	private volatile int monitorFailures;
	private volatile long restartNotBefore;
	private volatile String selectedName;
	private volatile long lastPollMillis;
	private volatile long lastResyncMillis;
	private volatile String lastCommandError = "";
	private volatile String lastLoggedMessage = "";
	private volatile long lastLoggedMillis;
	private final AtomicLong signalsSeen = new AtomicLong();
	private final AtomicLong refreshes = new AtomicLong();

	public MprisSource(TapedeckConfig config) {
		this.config = config;
	}

	// ------------------------------------------------------------------- lifecycle

	@Override
	public void start() {
		synchronized (lifecycleLock) {
			if (running) {
				return;
			}
			running = true;
		}
		if (config.mode() == TapedeckConfig.Mode.POLL) {
			pollOnly = true;
		}
		LOG.info("[tapedeck] MPRIS bridge starting (poll {} ms, resync {} ms, mode {})",
				config.pollMillis(), config.resyncMillis(), config.mode());

		supervisor = daemon("tapedeck-mpris-supervisor", this::supervise);
		ticker = daemon("tapedeck-mpris-ticker", this::tickLoop);
	}

	@Override
	public NowPlayingInfo snapshot() {
		return current.get();
	}

	@Override
	public void close() {
		running = false;
		Thread supervisorThread = supervisor;
		if (supervisorThread != null) {
			supervisorThread.interrupt();
		}
		Thread tickerThread = ticker;
		if (tickerThread != null) {
			tickerThread.interrupt();
		}
		stopMonitor();
		LOG.info("[tapedeck] MPRIS bridge stopped");
	}

	// ------------------------------------------------------------------ supervising

	private void supervise() {
		if (!hasSessionBus()) {
			busAvailable = false;
			NowPlayingInfo info = NowPlayingInfo.unavailable(NO_BUS_HELP);
			current.set(info);
			LOG.warn("[tapedeck] {}", NO_BUS_HELP);
			return;
		}
		refreshAll();

		while (running) {
			try {
				if (pollOnly) {
					stopMonitor();
				} else if (monitorProcess == null || !monitorProcess.isAlive()) {
					long wait = restartNotBefore - System.currentTimeMillis();
					if (wait > 0L) {
						Thread.sleep(Math.min(wait, SUPERVISOR_INTERVAL_MS));
						continue;
					}
					startMonitor();
				}
				Thread.sleep(SUPERVISOR_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				LOG.warn("[tapedeck] supervisor error", e);
				sleepQuietly(SUPERVISOR_INTERVAL_MS);
			}
		}
	}

	private boolean hasSessionBus() {
		// 'call' exercises the full round trip, which is stricter than checking an env var:
		// busctl happily falls back to $XDG_RUNTIME_DIR/bus when the variable is unset.
		return run("busctl", "--user", "call", DBUS_IFACE, "/org/freedesktop/DBus",
				DBUS_IFACE, "GetId") != null;
	}

	// --------------------------------------------------------------- signal monitor

	private void startMonitor() {
		Process process;
		try {
			process = new ProcessBuilder(
					"busctl", "--user", "monitor", "--json=short",
					"--match", "type='signal',interface='" + PROPS_IFACE + "'",
					"--match", "type='signal',interface='" + PLAYER_IFACE + "'",
					"--match", "type='signal',member='NameOwnerChanged'")
					.redirectErrorStream(false)
					.start();
		} catch (IOException e) {
			restartNotBefore = System.currentTimeMillis() + MAX_BACKOFF_MS;
			reportOnce("could not start busctl monitor: " + e.getMessage());
			return;
		}

		long startedAt = System.currentTimeMillis();
		monitorProcess = process;
		clearMonitorErrors();
		daemon("tapedeck-mpris-monitor-reader", () -> readMonitor(process));
		daemon("tapedeck-mpris-monitor-stderr", () -> drainMonitorErrors(process));
		daemon("tapedeck-mpris-monitor-reaper", () -> reapMonitor(process, startedAt));
		LOG.debug("[tapedeck] monitoring MPRIS signals (pid {})", process.pid());
	}

	/**
	 * stderr must be drained even though it is only used for diagnostics: an unread pipe
	 * blocks the monitor once it fills.
	 */
	private void drainMonitorErrors(Process process) {
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				appendMonitorError(line);
			}
		} catch (IOException e) {
			LOG.trace("[tapedeck] monitor stderr drain ended", e);
		}
	}

	private void readMonitor(Process process) {
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while (running && (line = in.readLine()) != null) {
				if (!line.startsWith("{")) {
					continue;
				}
				try {
					onSignal(line);
				} catch (RuntimeException e) {
					LOG.debug("[tapedeck] unparseable bus message: {}", e.toString());
				}
			}
		} catch (IOException e) {
			LOG.debug("[tapedeck] monitor reader stopped", e);
		}
	}

	/**
	 * Maps the three signals worth reacting to onto a single "re-read the properties"
	 * action. Events are rare (a track change, a pause, a player appearing), so the
	 * simplicity of refreshing everything beats tracking senders and objects.
	 */
	private void onSignal(String line) {
		Map<String, Object> message = Json.asMap(Json.parse(line));
		if (message == null) {
			return;
		}
		String member = Json.asString(message.get("member"), "");
		List<Object> data = Json.list(Json.path(message, "payload", "data"));
		String first = data.isEmpty() ? "" : Json.asString(data.get(0), "");

		switch (member) {
			case "NameOwnerChanged" -> {
				if (first.startsWith(NAME_PREFIX)) {
					signalsSeen.incrementAndGet();
					refreshAll();
				}
			}
			case "PropertiesChanged" -> {
				if (PLAYER_IFACE.equals(first)) {
					signalsSeen.incrementAndGet();
					refreshAll();
				}
			}
			case "Seeked" -> {
				signalsSeen.incrementAndGet();
				refreshAll();
			}
			default -> {
			}
		}
	}

	private void reapMonitor(Process process, long startedAt) {
		try {
			process.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		synchronized (lifecycleLock) {
			if (monitorProcess == process) {
				monitorProcess = null;
			}
		}
		if (!running) {
			return;
		}

		if (System.currentTimeMillis() - startedAt < FAST_FAILURE_THRESHOLD_MS) {
			monitorFailures++;
			restartNotBefore = System.currentTimeMillis() + backoffMillis(monitorFailures);
			String detail = monitorErrorText();
			reportOnce("busctl monitor exited immediately"
					+ (detail.isBlank() ? "" : ": " + detail));
			if (config.mode() == TapedeckConfig.Mode.AUTO
					&& monitorFailures >= MONITOR_FAILURES_BEFORE_POLL && !pollOnly) {
				pollOnly = true;
				LOG.warn("[tapedeck] falling back to polling: bus monitoring is unavailable "
						+ "(a Flatpak or bus proxy can forbid it)");
			}
		} else {
			monitorFailures = 0;
			restartNotBefore = 0L;
		}
	}

	private void stopMonitor() {
		Process process;
		synchronized (lifecycleLock) {
			process = monitorProcess;
			monitorProcess = null;
		}
		if (process == null) {
			return;
		}
		process.destroy();
		try {
			if (!process.waitFor(500L, TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}

	private long backoffMillis(int failures) {
		return Math.min(MAX_BACKOFF_MS, 1_000L << Math.min(Math.max(failures - 1, 0), 5));
	}

	// ---------------------------------------------------------------------- ticking

	private void tickLoop() {
		while (running) {
			try {
				if (busAvailable) {
					tick();
				}
			} catch (Exception e) {
				LOG.debug("[tapedeck] tick failed", e);
			}
			sleepQuietly(TICK_INTERVAL_MS);
		}
	}

	private void tick() {
		long now = System.currentTimeMillis();
		boolean monitoring = monitorProcess != null && monitorProcess.isAlive();

		if (!monitoring) {
			// No signal stream: this is the macOS strategy, re-read everything on a timer.
			if (now - lastPollMillis >= config.pollMillis()) {
				lastPollMillis = now;
				refreshAll();
			}
			return;
		}

		// Position is exempt from change notification, so correct the interpolation now
		// and then. One property read, only while actually playing.
		String selected = selectedName;
		if (selected == null) {
			return;
		}
		MprisPlayer player = players.get(selected);
		if (player == null || player.state() != MprisPlayer.State.PLAYING) {
			return;
		}
		if (now - lastResyncMillis < config.resyncMillis()) {
			return;
		}
		lastResyncMillis = now;
		refreshPosition(selected);
		publish();
	}

	// ------------------------------------------------------------------ bus queries

	private List<String> listPlayers() {
		String json = run("busctl", "--user", "--json=short", "call", DBUS_IFACE,
				"/org/freedesktop/DBus", DBUS_IFACE, "ListNames");
		if (json == null) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		try {
			List<Object> values = callResult(json);
			for (Object value : values) {
				String name = Json.asString(value, "");
				if (name.startsWith(NAME_PREFIX)) {
					names.add(name);
				}
			}
		} catch (RuntimeException e) {
			LOG.debug("[tapedeck] could not list bus names: {}", e.toString());
		}
		return names;
	}

	/**
	 * Unwraps a {@code busctl ... call --json=short} reply. Unlike {@code get-property},
	 * which returns the value directly, {@code call} wraps the method's return values in a
	 * tuple, so a single {@code as} result looks like {@code data: [[a, b, c]]}.
	 */
	private static List<Object> callResult(String json) {
		List<Object> tuple = Json.list(Json.path(Json.parse(json), "data"));
		if (tuple.size() == 1) {
			List<Object> inner = Json.asList(tuple.get(0));
			if (inner != null) {
				return inner;
			}
		}
		return tuple;
	}

	/** One round trip for every property of one player. */
	private MprisPlayer readPlayer(String busName) {
		String json = run("busctl", "--user", "--json=short", "call", busName, PLAYER_PATH,
				PROPS_IFACE, "GetAll", "s", PLAYER_IFACE);
		if (json == null) {
			return null;
		}
		try {
			return MprisPlayer.parse(busName, json);
		} catch (RuntimeException e) {
			LOG.debug("[tapedeck] could not parse {}: {}", busName, e.toString());
			return null;
		}
	}

	private void refreshPosition(String busName) {
		String json = run("busctl", "--user", "--json=short", "get-property", busName,
				PLAYER_PATH, PLAYER_IFACE, "Position");
		if (json == null) {
			return;
		}
		try {
			long micros = Json.asLong(Json.path(Json.parse(json), "data"), -1L);
			if (micros >= 0L) {
				players.computeIfPresent(busName, (name, player) -> player.withPosition(micros));
			}
		} catch (RuntimeException e) {
			LOG.debug("[tapedeck] could not read position: {}", e.toString());
		}
	}

	private void refreshAll() {
		refreshes.incrementAndGet();
		List<String> names = listPlayers();
		if (names.isEmpty()) {
			boolean changed = !players.isEmpty();
			players.clear();
			selectedName = null;
			if (changed) {
				current.set(NowPlayingInfo.unavailable(""));
			}
			return;
		}
		players.keySet().removeIf(name -> !names.contains(name));
		for (String name : names) {
			MprisPlayer player = readPlayer(name);
			if (player != null) {
				players.put(name, player);
			}
		}
		publish();
	}

	// -------------------------------------------------------------------- publishing

	private void publish() {
		MprisPlayer best = PlayerElection.choose(
				new ArrayList<>(players.values()), selectedName, config);
		if (best == null) {
			selectedName = null;
			current.set(NowPlayingInfo.unavailable(""));
			return;
		}
		selectedName = best.busName();

		if (best.state() == MprisPlayer.State.STOPPED || !best.hasTitle()) {
			// Idle player, or a radio stream with no metadata: nothing worth drawing.
			current.set(NowPlayingInfo.STOPPED);
			return;
		}

		NowPlayingInfo.State state = best.state() == MprisPlayer.State.PLAYING
				? NowPlayingInfo.State.PLAYING
				: NowPlayingInfo.State.PAUSED;
		current.set(NowPlayingInfo.of(state, best.title(), best.artist(), best.album(),
				best.positionSeconds(), best.durationSeconds(), best.trackKey()));
	}

	// -------------------------------------------------------------------- utilities

	/**
	 * Runs a busctl command and returns stdout, or {@code null} on any failure. The last
	 * failure is kept for {@link #diagnostics()} and for the log message shown when the
	 * bridge cannot talk to the bus at all.
	 */
	private String run(String... command) {
		Process process = null;
		try {
			process = new ProcessBuilder(command).start();
			// Output is a few kilobytes at most, so it fits in the pipe buffer and the
			// wait-then-read order cannot deadlock.
			if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				lastCommandError = "timed out: " + command[0];
				return null;
			}
			String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
			if (process.exitValue() != 0) {
				lastCommandError = err.trim().isEmpty() ? "exit " + process.exitValue() : err.trim();
				return null;
			}
			return out;
		} catch (IOException e) {
			lastCommandError = e.getMessage() == null ? e.toString() : e.getMessage();
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	private Thread daemon(String name, Runnable body) {
		Thread thread = new Thread(body, name);
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	/** Logs at most once per distinct message per interval, so failures cannot flood. */
	private void reportOnce(String message) {
		long now = System.currentTimeMillis();
		synchronized (logLock) {
			if (message.equals(lastLoggedMessage) && now - lastLoggedMillis < REPEAT_LOG_INTERVAL_MS) {
				return;
			}
			lastLoggedMessage = message;
			lastLoggedMillis = now;
		}
		LOG.warn("[tapedeck] {}", message);
	}

	private void appendMonitorError(String line) {
		String trimmed = line.trim();
		if (trimmed.isEmpty()) {
			return;
		}
		synchronized (monitorErrors) {
			if (!monitorErrors.isEmpty()) {
				monitorErrors.append(" | ");
			}
			monitorErrors.append(trimmed);
			if (monitorErrors.length() > 600) {
				monitorErrors.delete(0, monitorErrors.length() - 600);
			}
		}
	}

	private void clearMonitorErrors() {
		synchronized (monitorErrors) {
			monitorErrors.setLength(0);
		}
	}

	private String monitorErrorText() {
		synchronized (monitorErrors) {
			return monitorErrors.toString().trim();
		}
	}

	private void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public List<String> diagnostics() {
		List<String> lines = new ArrayList<>();
		lines.add("mode: " + (pollOnly ? "polling" : "signals") + " (" + config.mode() + ")");
		lines.add("bus available: " + busAvailable);
		lines.add("selected: " + (selectedName == null ? "(none)" : selectedName));
		for (MprisPlayer player : players.values()) {
			lines.add("player " + player.describe());
		}
		lines.add("signals: " + signalsSeen.get() + ", property refreshes: " + refreshes.get());
		lines.add("last command error: " + (lastCommandError.isBlank() ? "(none)" : lastCommandError));
		return lines;
	}
}
