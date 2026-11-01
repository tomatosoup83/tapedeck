package dev.tapedeck.music;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Reads the Apple Music now-playing state and publishes it as an {@link NowPlayingInfo}.
 *
 * <p>Design notes:
 * <ul>
 *   <li><b>One long-lived {@code osascript} process</b>, not one per poll. Spawning
 *       {@code osascript} costs tens of milliseconds, so a per-second process spawn
 *       would be wasteful. The embedded JXA script loops and emits one delimited line
 *       per update; Java just reads the stream.</li>
 *   <li><b>Java owns the process lifecycle.</b> The JXA script deliberately does not
 *       check whether Music.app is running — merely referencing {@code Application('Music')}
 *       would <i>launch</i> it. A supervisor thread checks for a live {@code Music}
 *       process via {@link ProcessHandle} and only keeps the script alive while there
 *       is something to read from.</li>
 *   <li><b>stdout only.</b> JXA's {@code console.log} writes to <i>stderr</i>, so the
 *       script writes through the {@code NSFileHandle} bridge to get unbuffered stdout.</li>
 * </ul>
 *
 * <p>{@link #snapshot()} is safe to call from the render thread.
 */
public final class MusicBridge implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger("tapedeck/music");

	/** ASCII Unit Separator: cannot occur in a song title, so it is a safe field delimiter. */
	private static final String US = String.valueOf((char) 0x1F);
	private static final Pattern FIELD_SPLIT = Pattern.compile(Pattern.quote(US));

	private static final long SUPERVISOR_INTERVAL_MS = 2000L;
	private static final long FAST_FAILURE_THRESHOLD_MS = 3000L;
	private static final long MAX_BACKOFF_MS = 30_000L;
	private static final long REPEAT_LOG_INTERVAL_MS = 30_000L;

	/**
	 * Long-lived JXA bridge. Writes one line per poll to stdout:
	 * {@code P|S <US> title <US> artist <US> album <US> position <US> duration},
	 * or {@code X <US> reason} when idle, or {@code E <US> message} on failure.
	 *
	 * <p>{@code __POLL_SECONDS__} is substituted before launch.
	 */
	private static final String SCRIPT = """
			ObjC.import('Foundation');
			const OUT = $.NSFileHandle.fileHandleWithStandardOutput;
			const US = String.fromCharCode(31);
			const STRIP = new RegExp('[' + US + '\\\\r\\\\n\\\\t]', 'g');

			function emit(s) {
				OUT.writeData($(s + '\\n').dataUsingEncoding($.NSUTF8StringEncoding));
			}
			function clean(v) {
				return (v === null || v === undefined) ? '' : String(v).replace(STRIP, ' ');
			}
			function num(v) {
				var n = Number(v);
				return isFinite(n) ? n : 0;
			}

			const Music = Application('Music');
			const POLL = __POLL_SECONDS__;

			while (true) {
				var line;
				try {
					const st = String(Music.playerState());
					if (st === 'playing' || st === 'paused') {
						const t = Music.currentTrack();
						line = [
							st === 'playing' ? 'P' : 'S',
							clean(t.name()),
							clean(t.artist()),
							clean(t.album()),
							String(num(Music.playerPosition())),
							String(num(t.duration()))
						].join(US);
					} else {
						line = ['X', clean(st)].join(US);
					}
				} catch (e) {
					line = ['E', clean((e && e.message) || e)].join(US);
				}
				emit(line);
				delay(POLL);
			}
			""";

	private final AtomicReference<NowPlayingInfo> current =
			new AtomicReference<>(NowPlayingInfo.unavailable(""));

	private final Object lifecycleLock = new Object();
	private final Object stderrLock = new Object();
	private final StringBuilder stderrTail = new StringBuilder();
	private final double pollSeconds;

	private volatile Process child;
	private volatile Thread supervisor;
	private volatile boolean running;
	private volatile int consecutiveFastFailures;
	private volatile long restartNotBefore;

	private String lastLoggedMessage = "";
	private long lastLogMillis;

	private MusicBridge(long pollIntervalMs) {
		this.pollSeconds = Math.max(200L, pollIntervalMs) / 1000.0;
	}

	/** True when this JVM is running on macOS, the only supported platform. */
	public static boolean isSupportedPlatform() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}

	public static MusicBridge create() {
		return create(1000L);
	}

	public static MusicBridge create(long pollIntervalMs) {
		return new MusicBridge(pollIntervalMs);
	}

	/** The most recent snapshot. Never {@code null}. */
	public NowPlayingInfo snapshot() {
		return current.get();
	}

	public void start() {
		synchronized (lifecycleLock) {
			if (running) {
				return;
			}
			running = true;
		}

		supervisor = new Thread(this::supervise, "tapedeck-music-supervisor");
		supervisor.setDaemon(true);
		supervisor.start();
	}

	// ------------------------------------------------------------------ lifecycle

	private void supervise() {
		LOG.info("Apple Music bridge starting (poll every {} ms)", Math.round(pollSeconds * 1000));

		while (running) {
			try {
				if (!isMusicRunning()) {
					stopChild();
					publishQuietly(NowPlayingInfo.unavailable(""));
					Thread.sleep(SUPERVISOR_INTERVAL_MS);
					continue;
				}

				long waitFor = restartNotBefore - System.currentTimeMillis();
				if (waitFor > 0L) {
					Thread.sleep(Math.min(waitFor, SUPERVISOR_INTERVAL_MS));
					continue;
				}

				synchronized (lifecycleLock) {
					if (child == null || !child.isAlive()) {
						startChild();
					}
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

	/**
	 * {@code NSWorkspace} is unreliable here — it does not list Music when the app runs
	 * headless or in a background session — so consult the process table directly.
	 */
	private static boolean isMusicRunning() {
		try {
			return ProcessHandle.allProcesses().anyMatch(handle ->
					handle.isAlive()
							&& handle.info().command()
									.map(cmd -> cmd.equals("Music") || cmd.endsWith("/Music"))
									.orElse(false));
		} catch (Exception e) {
			// If the process table is unreadable, assume Music is running and let the
			// bridge fail on its own, rather than silently showing nothing.
			LOG.debug("[tapedeck] could not enumerate processes", e);
			return true;
		}
	}

	private void startChild() throws IOException {
		clearStderr();

		String script = SCRIPT.replace("__POLL_SECONDS__", Double.toString(pollSeconds));
		Process process = new ProcessBuilder("osascript", "-l", "JavaScript", "-e", script)
				.redirectErrorStream(false)
				.start();

		long startedAt = System.currentTimeMillis();
		child = process;

		drainStderr(process);
		spawnDaemon("tapedeck-music-reader", () -> readLoop(process));
		spawnDaemon("tapedeck-music-reaper", () -> reap(process, startedAt));

		LOG.debug("[tapedeck] spawned osascript (pid {})", process.pid());
	}

	/** Consumes stdout until EOF, publishing each parsed line. */
	private void readLoop(Process process) {
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while (running && (line = in.readLine()) != null) {
				parse(line);
			}
		} catch (IOException e) {
			LOG.debug("[tapedeck] reader stopped", e);
		} finally {
			clearChild(process);
		}
	}

	/** Waits for the child, then records whether it failed suspiciously fast. */
	private void reap(Process process, long startedAt) {
		try {
			process.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}

		if (!running) {
			return;
		}

		if (System.currentTimeMillis() - startedAt < FAST_FAILURE_THRESHOLD_MS) {
			consecutiveFastFailures++;
			// Do not spin: a child that dies instantly would otherwise be respawned
			// once per supervisor tick, forever.
			restartNotBefore = System.currentTimeMillis() + backoffMillis();
			reportOnce(readStderr());
		} else {
			// A long-lived child that exits is normal: Music.app probably quit.
			consecutiveFastFailures = 0;
			restartNotBefore = 0L;
		}
	}

	private void stopChild() {
		Process process;
		synchronized (lifecycleLock) {
			process = child;
			child = null;
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

	private void clearChild(Process process) {
		synchronized (lifecycleLock) {
			if (child == process) {
				child = null;
			}
		}
	}

	/**
	 * Delay before respawning, doubling per consecutive instant failure and capped. The
	 * counter is only cleared by {@link #reap} observing a child that lived long enough
	 * to be considered healthy.
	 */
	private long backoffMillis() {
		int failures = consecutiveFastFailures;
		if (failures <= 0) {
			return 0L;
		}
		return Math.min(MAX_BACKOFF_MS, 1000L << Math.min(failures - 1, 5));
	}

	@Override
	public void close() {
		running = false;
		Thread thread = supervisor;
		if (thread != null) {
			thread.interrupt();
		}
		stopChild();
		LOG.info("Apple Music bridge stopped");
	}

	// --------------------------------------------------------------------- parsing

	private void parse(String raw) {
		if (raw.isBlank()) {
			return;
		}
		String[] parts = FIELD_SPLIT.split(raw, -1);
		switch (parts[0]) {
			case "P", "S" -> parseTrack(parts);
			case "X" -> current.set(NowPlayingInfo.of(NowPlayingInfo.State.STOPPED, "", "", "", 0, 0));
			case "E" -> parseError(parts);
			default -> LOG.debug("[tapedeck] unrecognised line: {}", raw);
		}
	}

	private void parseTrack(String[] parts) {
		if (parts.length < 6) {
			LOG.debug("[tapedeck] short track line ({} fields)", parts.length);
			return;
		}
		if (parts[1].isBlank()) {
			// A radio stream with no metadata: nothing worth drawing.
			current.set(NowPlayingInfo.of(NowPlayingInfo.State.STOPPED, "", "", "", 0, 0));
			return;
		}
		NowPlayingInfo.State state = "P".equals(parts[0])
				? NowPlayingInfo.State.PLAYING
				: NowPlayingInfo.State.PAUSED;
		current.set(NowPlayingInfo.of(state, parts[1], parts[2], parts[3],
				parseDouble(parts[4]), parseDouble(parts[5])));
	}

	private void parseError(String[] parts) {
		String message = parts.length > 1 ? parts[1] : "unknown error";
		String stderr = readStderr();

		if (isPermissionProblem(message) || isPermissionProblem(stderr)) {
			current.set(NowPlayingInfo.error(permissionHelp(message, stderr)));
			reportOnce(message);
		} else {
			// Transient scripting hiccups (e.g. Music.app mid-quit) are not worth surfacing.
			LOG.debug("[tapedeck] script error: {}", message);
			current.set(NowPlayingInfo.error(message));
		}
	}

	private static double parseDouble(String s) {
		try {
			double d = Double.parseDouble(s);
			return Double.isFinite(d) ? d : 0.0;
		} catch (NumberFormatException e) {
			return 0.0;
		}
	}

	/**
	 * Distinguishes "macOS refused to let us script Music.app" from an ordinary scripting
	 * error, because the fix is completely different.
	 */
	private static boolean isPermissionProblem(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		String lower = text.toLowerCase();
		return lower.contains("-1743")
				|| lower.contains("not authorized")
				|| lower.contains("not allowed")
				|| lower.contains("assistive access")
				|| lower.contains("user canceled")
				|| lower.contains("apple event");
	}

	private static String permissionHelp(String message, String stderr) {
		StringBuilder help = new StringBuilder(
				"macOS has not granted permission to control Music.app. Open System Settings > "
						+ "Privacy & Security > Automation, then allow your launcher to control Music.");
		appendDetail(help, message);
		appendDetail(help, stderr);
		return help.toString();
	}

	private static void appendDetail(StringBuilder sb, String detail) {
		if (detail == null || detail.isBlank()) {
			return;
		}
		sb.append(" (").append(detail.trim()).append(')');
	}

	// ------------------------------------------------------------------ diagnostics

	/** Logs at most once per distinct message per interval, so a 1 Hz failure is not a flood. */
	private void reportOnce(String message) {
		long now = System.currentTimeMillis();
		synchronized (stderrLock) {
			if (message.equals(lastLoggedMessage) && now - lastLogMillis < REPEAT_LOG_INTERVAL_MS) {
				return;
			}
			lastLoggedMessage = message;
			lastLogMillis = now;
		}
		LOG.warn("[tapedeck] {}", message.isBlank() ? "Music.app bridge exited unexpectedly" : message);
	}

	private void drainStderr(Process process) {
		spawnDaemon("tapedeck-music-stderr", () -> {
			try (BufferedReader in = new BufferedReader(
					new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = in.readLine()) != null) {
					appendStderr(line);
				}
			} catch (IOException e) {
				LOG.trace("[tapedeck] stderr drain ended", e);
			}
		});
	}

	private void appendStderr(String line) {
		String trimmed = line.trim();
		if (trimmed.isEmpty()) {
			return;
		}
		synchronized (stderrLock) {
			if (stderrTail.length() > 0) {
				stderrTail.append(" | ");
			}
			stderrTail.append(trimmed);
			if (stderrTail.length() > 600) {
				stderrTail.delete(0, stderrTail.length() - 600);
			}
		}
	}

	private String readStderr() {
		synchronized (stderrLock) {
			return stderrTail.toString();
		}
	}

	private void clearStderr() {
		synchronized (stderrLock) {
			stderrTail.setLength(0);
		}
	}

	/**
	 * Publishes a status change without churning the snapshot every poll — the HUD only
	 * redraws when something it cares about actually changed.
	 */
	private void publishQuietly(NowPlayingInfo info) {
		NowPlayingInfo previous = current.get();
		if (previous.state() != info.state() || !previous.detail().equals(info.detail())) {
			current.set(info);
		}
	}

	private static void spawnDaemon(String name, Runnable body) {
		Thread thread = new Thread(body, name);
		thread.setDaemon(true);
		thread.start();
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Diagnostics for a status command. */
	public List<String> diagnostics() {
		Process process = child;
		String stderr = readStderr();
		return List.of(
				"osascript alive: " + (process != null && process.isAlive()),
				"Music.app running: " + isMusicRunning(),
				"fast failures: " + consecutiveFastFailures,
				"stderr: " + (stderr.isBlank() ? "(empty)" : stderr));
	}
}