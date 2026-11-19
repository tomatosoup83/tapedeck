package dev.tapedeck.music;

import java.util.Locale;

/**
 * Chooses the media source for the platform the game is running on.
 *
 * <p>This is the only place that looks at {@code os.name}: everything downstream talks to
 * {@link NowPlayingSource}, which keeps the HUD and the metadata model portable.
 */
public final class SourceFactory {

	private SourceFactory() {
	}

	public static NowPlayingSource create(TapedeckConfig config) {
		String osName = System.getProperty("os.name", "");
		String normalized = osName.toLowerCase(Locale.ROOT);
		if (normalized.contains("mac")) {
			return AppleMusicSource.create(config.pollMillis());
		}
		if (normalized.contains("linux")) {
			return new MprisSource(config);
		}
		return new UnsupportedSource(osName.isBlank() ? "this platform" : osName);
	}
}
