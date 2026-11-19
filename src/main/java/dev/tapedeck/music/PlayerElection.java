package dev.tapedeck.music;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which of several MPRIS players the HUD mirrors.
 *
 * <p>macOS has exactly one Music.app, so the port needs a policy where the original
 * needed none. The rules, in order:
 *
 * <ol>
 *   <li>Ignored names are dropped entirely.</li>
 *   <li>{@code Playing} beats {@code Paused} beats {@code Stopped}.</li>
 *   <li>Names listed in {@code priority} beat unlisted ones, in list order.</li>
 *   <li>A player with a title beats one without (a metadata-less radio stream).</li>
 *   <li>KDE's {@code plasma-browser-integration} loses to a browser's own player,
 *       because it usually re-exports the same audio.</li>
 *   <li>Bus name is the final tie-break, so the result is deterministic.</li>
 * </ol>
 *
 * <p>The incumbent is kept when it ties with the winner: two players that are both
 * playing must not make the HUD flap on every update.
 */
public final class PlayerElection {

	/** Chrome's and KDE's re-exports of audio another player already owns. */
	private static final String PLASMA_BROWSER_INTEGRATION =
			"org.mpris.MediaPlayer2.plasma-browser-integration";

	private PlayerElection() {
	}

	/**
	 * @param players     every known player; may be empty
	 * @param currentName the player mirrored last time, or {@code null}
	 * @param config      priority and ignore rules
	 * @return the player to mirror, or {@code null} when nothing is eligible
	 */
	public static MprisPlayer choose(List<MprisPlayer> players, String currentName, TapedeckConfig config) {
		List<MprisPlayer> candidates = new ArrayList<>(players.size());
		for (MprisPlayer player : players) {
			if (player != null && !config.isIgnored(player.busName())) {
				candidates.add(player);
			}
		}
		if (candidates.isEmpty()) {
			return null;
		}
		candidates.sort(comparator(config));
		MprisPlayer best = candidates.get(0);

		if (currentName != null && !currentName.equals(best.busName())) {
			for (MprisPlayer candidate : candidates) {
				if (candidate.busName().equals(currentName) && rank(candidate) == rank(best)) {
					return candidate;
				}
			}
		}
		return best;
	}

	/** 3 for Playing, 2 for Paused, 1 for anything else. */
	static int rank(MprisPlayer player) {
		return switch (player.state()) {
			case PLAYING -> 3;
			case PAUSED -> 2;
			case STOPPED -> 1;
		};
	}

	static Comparator<MprisPlayer> comparator(TapedeckConfig config) {
		return Comparator
				.comparingInt(PlayerElection::rank)
				.reversed()
				.thenComparingInt((MprisPlayer player) -> config.priorityIndex(player.busName()) < 0 ? 1 : 0)
				.thenComparingInt((MprisPlayer player) -> {
					int index = config.priorityIndex(player.busName());
					return index < 0 ? Integer.MAX_VALUE : index;
				})
				.thenComparing((MprisPlayer player) -> !player.hasTitle())
				.thenComparing((MprisPlayer player) ->
						player.busName().startsWith(PLASMA_BROWSER_INTEGRATION))
				.thenComparing(MprisPlayer::busName);
	}
}
