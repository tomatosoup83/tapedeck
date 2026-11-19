package dev.tapedeck.music;

import java.util.List;
import java.util.Map;

/**
 * One MPRIS player's state, as read from a {@code Properties.GetAll} reply.
 *
 * <p>Immutable: {@link #withPosition(long)} returns a copy, which is how the poller
 * refreshes the play position without re-reading the whole property set.
 *
 * <p>MPRIS reports {@code Position} and {@code mpris:length} in <b>microseconds</b>;
 * both are kept that way here and converted at the edge so the interpolation code
 * keeps working in seconds.
 */
public record MprisPlayer(
		String busName,
		State state,
		String title,
		String artist,
		String album,
		String trackId,
		String url,
		String artUrl,
		long lengthMicros,
		long positionMicros,
		double rate,
		boolean canControl) {

	public enum State {
		PLAYING,
		PAUSED,
		STOPPED
	}

	/** ASCII Unit Separator: cannot occur in a title, so keys never collide. */
	private static final char KEY_SEPARATOR = '\u001f';

	/**
	 * Reads one player from the JSON of a {@code busctl --json=short} reply to
	 * {@code org.freedesktop.DBus.Properties.GetAll}.
	 *
	 * @return the player, or {@code null} when the reply is not a property map
	 * @throws Json.JsonException when the payload is malformed
	 */
	public static MprisPlayer parse(String busName, String json) {
		Object parsed = Json.parse(json);
		List<Object> data = Json.list(Json.path(parsed, "data"));
		if (data.isEmpty()) {
			return null;
		}
		Map<String, Object> properties = Json.asMap(data.get(0));
		if (properties == null) {
			return null;
		}

		Map<String, Object> metadata = Json.asMap(field(properties, "Metadata"));
		if (metadata == null) {
			metadata = Map.of();
		}

		return new MprisPlayer(
				busName,
				state(Json.asString(field(properties, "PlaybackStatus"), "Stopped")),
				text(metadata, "xesam:title"),
				joined(metadata, "xesam:artist"),
				text(metadata, "xesam:album"),
				text(metadata, "mpris:trackid"),
				text(metadata, "xesam:url"),
				text(metadata, "mpris:artUrl"),
				Json.asLong(field(metadata, "mpris:length"), 0L),
				Json.asLong(field(properties, "Position"), 0L),
				Json.asDouble(field(properties, "Rate"), 1.0),
				Json.asBoolean(field(properties, "CanControl"), false));
	}

	private static State state(String playbackStatus) {
		return switch (playbackStatus) {
			case "Playing" -> State.PLAYING;
			case "Paused" -> State.PAUSED;
			default -> State.STOPPED;
		};
	}

	/** {@code busctl} wraps every property as {@code {"type": ..., "data": ...}}. */
	private static Object field(Map<String, Object> container, String key) {
		return Json.path(container, key, "data");
	}

	private static String text(Map<String, Object> container, String key) {
		return Json.asString(field(container, key), "");
	}

	/** {@code xesam:artist} is an array of strings; join for display. */
	private static String joined(Map<String, Object> container, String key) {
		List<Object> values = Json.asList(field(container, key));
		if (values == null || values.isEmpty()) {
			return text(container, key);
		}
		StringBuilder sb = new StringBuilder();
		for (Object value : values) {
			String artist = Json.asString(value, "").trim();
			if (artist.isEmpty()) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append(", ");
			}
			sb.append(artist);
		}
		return sb.toString();
	}

	public boolean hasTitle() {
		return !title.isEmpty();
	}

	public double positionSeconds() {
		return positionMicros / 1_000_000.0;
	}

	public double durationSeconds() {
		return lengthMicros / 1_000_000.0;
	}

	public MprisPlayer withPosition(long micros) {
		return new MprisPlayer(busName, state, title, artist, album, trackId, url, artUrl,
				lengthMicros, micros, rate, canControl);
	}

	/**
	 * Identity of the current track, used to anchor interpolation and to reset the HUD
	 * fade. Firefox exports a constant {@code mpris:trackid} for every track, so the
	 * URL is part of the key; players that do vary their trackid are covered too.
	 */
	public String trackKey() {
		return title + KEY_SEPARATOR + artist + KEY_SEPARATOR + lengthMicros
				+ KEY_SEPARATOR + url + KEY_SEPARATOR + trackId;
	}

	/** Short description for diagnostics, e.g. {@code firefox: Playing "Title"}. */
	public String describe() {
		return busName + ": " + state + (title.isEmpty() ? "" : " \"" + title + "\"");
	}
}
