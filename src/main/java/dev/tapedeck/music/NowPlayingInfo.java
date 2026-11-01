package dev.tapedeck.music;

/**
 * An immutable snapshot of what Apple Music is doing right now.
 *
 * <p>{@link #stamp()} is the wall-clock millisecond at which the bridge received this
 * snapshot. AppleScript only reports the play position once per poll, so the HUD
 * interpolates between snapshots rather than showing a value that ticks over in
 * visible one-second jumps.
 */
public record NowPlayingInfo(State state, String title, String artist, String album,
                             double position, double duration, long stamp, String detail) {

	public enum State {
		/** A track is playing. */
		PLAYING,
		/** A track is loaded but paused. */
		PAUSED,
		/** Music.app is running but idle. */
		STOPPED,
		/** Music.app is not running, or this mod is not running on macOS. */
		UNAVAILABLE,
		/** Something went wrong talking to Music.app. {@link #detail()} has why. */
		ERROR
	}

	public static final NowPlayingInfo UNAVAILABLE = unavailable("");
	public static final NowPlayingInfo STOPPED = new NowPlayingInfo(State.STOPPED, "", "", "", 0, 0, 0L, "");

	public static NowPlayingInfo unavailable(String detail) {
		return new NowPlayingInfo(State.UNAVAILABLE, "", "", "", 0, 0, System.currentTimeMillis(), detail);
	}

	public static NowPlayingInfo error(String detail) {
		return new NowPlayingInfo(State.ERROR, "", "", "", 0, 0, System.currentTimeMillis(), detail);
	}

	public static NowPlayingInfo of(State state, String title, String artist, String album,
	                               double position, double duration) {
		return new NowPlayingInfo(state, clean(title), clean(artist), clean(album),
				position, duration, System.currentTimeMillis(), "");
	}

	/** True when there is a track worth drawing on the HUD. */
	public boolean hasTrack() {
		return (state == State.PLAYING || state == State.PAUSED) && !title.isEmpty();
	}

	public boolean isPlaying() {
		return state == State.PLAYING;
	}

	/** True when the user should be told about {@link #detail()} (e.g. a TCC denial). */
	public boolean needsAttention() {
		return !detail.isEmpty() && (state == State.ERROR || state == State.UNAVAILABLE);
	}

	/** Play position in seconds, extrapolated from {@link #stamp()} while playing. */
	public double livePosition() {
		if (state != State.PLAYING || stamp == 0L) {
			return position;
		}
		double elapsed = (System.currentTimeMillis() - stamp) / 1000.0;
		double projected = position + Math.max(0.0, elapsed);
		return duration > 0.0 ? Math.min(projected, duration) : projected;
	}

	/** Play position in seconds, clamped to {@code [0, duration]}. */
	public double clampedPosition() {
		double pos = livePosition();
		if (pos < 0.0) {
			return 0.0;
		}
		return duration > 0.0 ? Math.min(pos, duration) : pos;
	}

	/** Progress through the track in {@code [0, 1]}, or {@code -1} if unknown. */
	public double progress() {
		return duration > 0.0 ? clampedPosition() / duration : -1.0;
	}

	/** Formats a duration in seconds as {@code m:ss}, or {@code --:--} if unknown. */
	public static String formatTime(double seconds) {
		if (seconds < 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) {
			return "--:--";
		}
		long total = (long) seconds;
		return (total / 60) + ":" + String.format("%02d", total % 60);
	}

	/** Song titles can contain tabs and newlines, which would corrupt the wire format. */
	private static String clean(String s) {
		return s == null ? "" : s.replaceAll("[\\p{Cntrl}]", " ").trim();
	}
}