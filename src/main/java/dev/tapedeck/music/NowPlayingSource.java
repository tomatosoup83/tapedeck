package dev.tapedeck.music;

import java.util.List;

/**
 * A platform-specific source of "what is playing right now".
 *
 * <p>This is the seam between the portable half of the mod (the HUD and
 * {@link NowPlayingInfo}) and the OS half (AppleScript on macOS, MPRIS/D-Bus on Linux).
 * Implementations must publish immutable snapshots and must never block the render
 * thread: {@link #snapshot()} is called every frame.
 */
public interface NowPlayingSource extends AutoCloseable {

	/** Starts whatever background machinery the platform needs. Returns immediately. */
	void start();

	/** The most recent snapshot. Never {@code null}, and cheap enough for the render thread. */
	NowPlayingInfo snapshot();

	/** Human-readable status lines, for logs and debugging. */
	List<String> diagnostics();

	@Override
	void close();
}
