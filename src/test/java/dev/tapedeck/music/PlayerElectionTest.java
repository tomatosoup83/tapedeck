package dev.tapedeck.music;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PlayerElectionTest {

	private static final String FIREFOX = "org.mpris.MediaPlayer2.firefox.instance_1_135";
	private static final String VLC = "org.mpris.MediaPlayer2.vlc";
	private static final String PLASMA = "org.mpris.MediaPlayer2.plasma-browser-integration";

	private static MprisPlayer player(String busName, MprisPlayer.State state, String title) {
		return new MprisPlayer(busName, state, title, "Artist", "Album", "trackid", "url", "",
				180_000_000L, 0L, 1.0, true);
	}

	private static TapedeckConfig config(String priority, String ignore) {
		Properties properties = new Properties();
		properties.setProperty("priority", priority);
		properties.setProperty("ignore", ignore);
		return TapedeckConfig.parse(properties);
	}

	@Test
	void playingBeatsPaused() {
		MprisPlayer paused = player(FIREFOX, MprisPlayer.State.PAUSED, "A");
		MprisPlayer playing = player(VLC, MprisPlayer.State.PLAYING, "B");

		assertSame(playing, PlayerElection.choose(List.of(paused, playing), null, TapedeckConfig.defaults()));
	}

	@Test
	void pausedBeatsStopped() {
		MprisPlayer stopped = player(VLC, MprisPlayer.State.STOPPED, "");
		MprisPlayer paused = player(FIREFOX, MprisPlayer.State.PAUSED, "A");

		assertSame(paused, PlayerElection.choose(List.of(stopped, paused), null, TapedeckConfig.defaults()));
	}

	@Test
	void ignoredPlayersAreDropped() {
		MprisPlayer playing = player(VLC, MprisPlayer.State.PLAYING, "B");
		MprisPlayer paused = player(FIREFOX, MprisPlayer.State.PAUSED, "A");

		TapedeckConfig withoutVlc = config("", "vlc");
		assertSame(paused, PlayerElection.choose(List.of(paused, playing), null, withoutVlc));
	}

	@Test
	void priorityBreaksTies() {
		MprisPlayer firefox = player(FIREFOX, MprisPlayer.State.PLAYING, "A");
		MprisPlayer vlc = player(VLC, MprisPlayer.State.PLAYING, "B");

		assertSame(firefox, PlayerElection.choose(List.of(vlc, firefox), null, config("firefox, vlc", "")));
		assertSame(vlc, PlayerElection.choose(List.of(vlc, firefox), null, config("vlc, firefox", "")));
	}

	@Test
	void plasmaBrowserIntegrationLosesToTheBrowser() {
		MprisPlayer plasma = player(PLASMA, MprisPlayer.State.PLAYING, "Same Song");
		MprisPlayer firefox = player(FIREFOX, MprisPlayer.State.PLAYING, "Same Song");

		// Alphabetically the KDE name sorts first, so this only passes because of the rule.
		assertSame(firefox, PlayerElection.choose(List.of(plasma, firefox), null, TapedeckConfig.defaults()));
	}

	@Test
	void incumbentIsKeptWhileTied() {
		MprisPlayer firefox = player(FIREFOX, MprisPlayer.State.PLAYING, "A");
		MprisPlayer vlc = player(VLC, MprisPlayer.State.PLAYING, "B");

		assertSame(vlc, PlayerElection.choose(List.of(firefox, vlc), VLC, TapedeckConfig.defaults()));
	}

	@Test
	void incumbentIsReplacedWhenItStopsPlaying() {
		MprisPlayer paused = player(FIREFOX, MprisPlayer.State.PAUSED, "A");
		MprisPlayer playing = player(VLC, MprisPlayer.State.PLAYING, "B");

		assertSame(playing, PlayerElection.choose(List.of(paused, playing), FIREFOX, TapedeckConfig.defaults()));
	}

	@Test
	void aTitlelessStreamStillCountsAsTheWinner() {
		// Matches macOS behaviour: a radio stream with no metadata wins the election and
		// the HUD then hides, rather than showing a stale paused track.
		MprisPlayer radio = player(VLC, MprisPlayer.State.PLAYING, "");
		MprisPlayer paused = player(FIREFOX, MprisPlayer.State.PAUSED, "A");

		assertSame(radio, PlayerElection.choose(List.of(paused, radio), null, TapedeckConfig.defaults()));
	}

	@Test
	void nothingEligibleGivesNull() {
		assertNull(PlayerElection.choose(List.of(), null, TapedeckConfig.defaults()));

		MprisPlayer vlc = player(VLC, MprisPlayer.State.PLAYING, "B");
		assertNull(PlayerElection.choose(List.of(vlc), null, config("", "vlc")));
	}

	@Test
	void deterministicWhenEverythingTies() {
		MprisPlayer a = player("org.mpris.MediaPlayer2.aaa", MprisPlayer.State.PLAYING, "A");
		MprisPlayer b = player("org.mpris.MediaPlayer2.bbb", MprisPlayer.State.PLAYING, "B");

		assertEquals(a.busName(),
				PlayerElection.choose(List.of(b, a), null, TapedeckConfig.defaults()).busName());
	}
}
