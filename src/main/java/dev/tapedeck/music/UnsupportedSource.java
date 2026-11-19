package dev.tapedeck.music;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Stand-in for platforms with no media bridge (Windows, and anything else).
 *
 * <p>It publishes one explanatory snapshot and does nothing else, so the rest of the mod
 * can stay platform-agnostic instead of checking the OS everywhere.
 */
public final class UnsupportedSource implements NowPlayingSource {

	private static final Logger LOG = LoggerFactory.getLogger("tapedeck/music");

	private final NowPlayingInfo info;

	public UnsupportedSource(String osName) {
		this.info = NowPlayingInfo.unavailable(
				"No media bridge for " + osName + " yet; Tapedeck supports macOS and Linux.");
	}

	@Override
	public void start() {
		LOG.warn("[tapedeck] {}", info.detail());
	}

	@Override
	public NowPlayingInfo snapshot() {
		return info;
	}

	@Override
	public List<String> diagnostics() {
		return List.of("unsupported platform");
	}

	@Override
	public void close() {
	}
}
