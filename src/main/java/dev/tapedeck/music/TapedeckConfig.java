package dev.tapedeck.music;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Settings for the Linux (MPRIS) bridge, read from {@code config/tapedeck.properties}.
 *
 * <p>Linux has many media players where macOS has one, so the two settings that matter
 * are which bus names to prefer and which to ignore. Everything else has a sane default
 * and the file is created on first launch purely so the options are discoverable.
 */
public final class TapedeckConfig {

	public enum Mode {
		/** Use the signal monitor, falling back to polling if the sandbox forbids it. */
		AUTO,
		/** Only use {@code busctl monitor}; fail loudly if it is unavailable. */
		MONITOR,
		/** Never monitor; re-read properties on a timer (the macOS strategy). */
		POLL
	}

	private static final Logger LOG = LoggerFactory.getLogger("tapedeck/config");

	private static final long MIN_POLL_MS = 250L;
	private static final long MIN_RESYNC_MS = 1_000L;
	private static final long DEFAULT_POLL_MS = 1_000L;
	private static final long DEFAULT_RESYNC_MS = 5_000L;

	/** HUD size bounds, as a percentage of the default layout. */
	public static final int MIN_HUD_SIZE = 50;
	public static final int MAX_HUD_SIZE = 200;
	public static final int DEFAULT_HUD_SIZE = 100;

	/** Match the single line each writer rewrites, so surrounding comments survive. */
	private static final Pattern HUD_SIZE_LINE = linePattern("hud-size");
	private static final Pattern HUD_HIDDEN_LINE = linePattern("hud-hidden");

	private static Pattern linePattern(String key) {
		return Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(key) + "[ \\t]*=.*$");
	}

	private static final String DEFAULT_FILE = """
			# Tapedeck settings (Linux / MPRIS).
			#
			# Several players can be on the bus at once, so Tapedeck picks one:
			#   state first (Playing > Paused > Stopped), then this priority order,
			#   then the bus name alphabetically. The choice is sticky while tied.
			#
			# Entries are case-insensitive substrings of the D-Bus name, e.g. "firefox"
			# matches org.mpris.MediaPlayer2.firefox.instance_1_135.
			#
			# priority = firefox, vlc, spotify
			priority =

			# Bus names never to mirror. Handy for a phone mirrored by KDE Connect, or for
			# plasma-browser-integration duplicating a browser's own player.
			# ignore = kdeconnect, plasma-browser-integration
			ignore =

			# Properties re-read interval when the signal monitor is unavailable (ms).
			poll-ms = 1000

			# How often to correct the interpolated play position while playing (ms).
			resync-ms = 5000

			# auto: signals, falling back to polling; monitor: signals only; poll: timer only.
			mode = auto

			# HUD size, as a percentage of the default layout (50-200). Also settable in
			# game with /tapedeck size <percent>, which updates this line.
			hud-size = 100

			# Start with the panel hidden. Toggled in game with /tapedeck toggle or the
			# "Toggle HUD" key (Options -> Controls -> Tapedeck).
			hud-hidden = false
			""";

	private final List<String> priority;
	private final List<Pattern> ignore;
	private final long pollMillis;
	private final long resyncMillis;
	private final Mode mode;
	private final int hudSize;
	private final boolean hudHidden;

	private TapedeckConfig(List<String> priority, List<Pattern> ignore,
	                       long pollMillis, long resyncMillis, Mode mode, int hudSize,
	                       boolean hudHidden) {
		this.priority = List.copyOf(priority);
		this.ignore = List.copyOf(ignore);
		this.pollMillis = pollMillis;
		this.resyncMillis = resyncMillis;
		this.mode = mode;
		this.hudSize = hudSize;
		this.hudHidden = hudHidden;
	}

	public static TapedeckConfig defaults() {
		return new TapedeckConfig(List.of(), List.of(), DEFAULT_POLL_MS, DEFAULT_RESYNC_MS,
				Mode.AUTO, DEFAULT_HUD_SIZE, false);
	}

	/** Reads {@code file}, creating it with commented defaults when it does not exist. */
	public static TapedeckConfig load(Path file) {
		if (file == null) {
			return defaults();
		}
		try {
			if (Files.notExists(file)) {
				Path parent = file.toAbsolutePath().getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				Files.writeString(file, DEFAULT_FILE, StandardCharsets.UTF_8);
				LOG.info("[tapedeck] wrote default settings to {}", file);
				return defaults();
			}
			Properties properties = new Properties();
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				properties.load(reader);
			}
			return parse(properties);
		} catch (IOException e) {
			LOG.warn("[tapedeck] could not read {}; using defaults", file, e);
			return defaults();
		}
	}

	/** Package-private so the settings can be tested without touching the filesystem. */
	static TapedeckConfig parse(Properties properties) {
		List<String> priority = splitList(properties.getProperty("priority", ""));
		List<Pattern> ignore = new ArrayList<>();
		for (String entry : splitList(properties.getProperty("ignore", ""))) {
			ignore.add(compile(entry));
		}
		long poll = clamp(parseLong(properties.getProperty("poll-ms"), DEFAULT_POLL_MS), MIN_POLL_MS);
		long resync = clamp(parseLong(properties.getProperty("resync-ms"), DEFAULT_RESYNC_MS), MIN_RESYNC_MS);
		Mode mode = parseMode(properties.getProperty("mode", "auto"));
		int hudSize = clampHudSize((int) parseLong(properties.getProperty("hud-size"), DEFAULT_HUD_SIZE));
		boolean hudHidden = parseBoolean(properties.getProperty("hud-hidden"), false);
		return new TapedeckConfig(priority, ignore, poll, resync, mode, hudSize, hudHidden);
	}

	private static List<String> splitList(String value) {
		List<String> out = new ArrayList<>();
		for (String part : value.split(",")) {
			String trimmed = part.trim().toLowerCase(Locale.ROOT);
			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}
		return out;
	}

	private static Pattern compile(String entry) {
		try {
			return Pattern.compile(entry, Pattern.CASE_INSENSITIVE);
		} catch (PatternSyntaxException e) {
			// Not a regex: treat it as a literal substring so a stray "." cannot break config.
			return Pattern.compile(Pattern.quote(entry), Pattern.CASE_INSENSITIVE);
		}
	}

	private static long parseLong(String value, long fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			LOG.warn("[tapedeck] '{}' is not a number; using {}", value.trim(), fallback);
			return fallback;
		}
	}

	private static boolean parseBoolean(String value, boolean fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		String trimmed = value.trim();
		if (trimmed.equalsIgnoreCase("true")) {
			return true;
		}
		if (trimmed.equalsIgnoreCase("false")) {
			return false;
		}
		LOG.warn("[tapedeck] '{}' is not true/false; using {}", trimmed, fallback);
		return fallback;
	}

	private static long clamp(long value, long minimum) {
		return Math.max(minimum, value);
	}

	private static Mode parseMode(String value) {
		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "monitor" -> Mode.MONITOR;
			case "poll" -> Mode.POLL;
			default -> Mode.AUTO;
		};
	}

	/** True when the bus name matches any ignore entry. */
	public boolean isIgnored(String busName) {
		for (Pattern pattern : ignore) {
			if (pattern.matcher(busName).find()) {
				return true;
			}
		}
		return false;
	}

	/** Position in the priority list, or {@code -1} when the name is not listed. */
	public int priorityIndex(String busName) {
		String lower = busName.toLowerCase(Locale.ROOT);
		for (int i = 0; i < priority.size(); i++) {
			if (lower.contains(priority.get(i))) {
				return i;
			}
		}
		return -1;
	}

	public List<String> priority() {
		return priority;
	}

	public long pollMillis() {
		return pollMillis;
	}

	public long resyncMillis() {
		return resyncMillis;
	}

	public Mode mode() {
		return mode;
	}

	public int hudSize() {
		return hudSize;
	}

	public boolean hudHidden() {
		return hudHidden;
	}

	/** Keeps a hand-edited config from asking for an unusable panel size. */
	public static int clampHudSize(int percent) {
		return Math.min(MAX_HUD_SIZE, Math.max(MIN_HUD_SIZE, percent));
	}

	/**
	 * Persists a new HUD size by rewriting only the {@code hud-size} line, so comments,
	 * {@code priority} and {@code ignore} survive a {@code /tapedeck size} change.
	 *
	 * @return true when the file was written; failures are logged and ignored, because a
	 *         read-only config directory must not break the command in game
	 */
	/** Rewrites only the {@code hud-size} line, keeping comments and other settings. */
	public static boolean writeHudSize(Path file, int percent) {
		return writeSetting(file, HUD_SIZE_LINE, "hud-size", Integer.toString(clampHudSize(percent)));
	}

	/** Rewrites only the {@code hud-hidden} line, keeping comments and other settings. */
	public static boolean writeHudHidden(Path file, boolean hidden) {
		return writeSetting(file, HUD_HIDDEN_LINE, "hud-hidden", Boolean.toString(hidden));
	}

	private static boolean writeSetting(Path file, Pattern line, String key, String value) {
		if (file == null) {
			return false;
		}
		String replacement = key + " = " + value;
		try {
			String content = Files.exists(file)
					? Files.readString(file, StandardCharsets.UTF_8)
					: "";
			String updated;
			Matcher matcher = line.matcher(content);
			if (matcher.find()) {
				updated = matcher.replaceAll(Matcher.quoteReplacement(replacement));
			} else if (content.isEmpty()) {
				updated = replacement + System.lineSeparator();
			} else {
				String separator = content.endsWith("\\n") ? "" : System.lineSeparator();
				updated = content + separator + replacement + System.lineSeparator();
			}

			Path parent = file.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, updated, StandardCharsets.UTF_8);
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException e) {
			LOG.warn("[tapedeck] could not save {} to {}", key, file, e);
			return false;
		}
	}
}
