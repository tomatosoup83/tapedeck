package dev.tapedeck.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixtures are verbatim shapes captured from Firefox (Apple Music web player) and Sidra
 * on a real KDE session, so the tests pin the two quirks that shaped the port: a missing
 * {@code mpris:length} and a constant {@code mpris:trackid}.
 */
class MprisPlayerTest {

	private static final String FIREFOX_APPLE_MUSIC = """
			{"type":"a{sv}","data":[{\
			"PlaybackStatus":{"type":"s","data":"Playing"},\
			"Rate":{"type":"d","data":1.000000000000000000000e+00},\
			"Metadata":{"type":"a{sv}","data":{\
			"mpris:trackid":{"type":"o","data":"/org/mpris/MediaPlayer2/firefox"},\
			"xesam:title":{"type":"s","data":"So Easy (To Fall In Love)"},\
			"xesam:album":{"type":"s","data":"The Art of Loving"},\
			"xesam:artist":{"type":"as","data":["Olivia Dean"]},\
			"mpris:artUrl":{"type":"s","data":"file:///tmp/71848_2.png"},\
			"xesam:url":{"type":"s","data":"https://music.apple.com/sg/album/the-art-of-loving/1817609404"}}},\
			"Position":{"type":"x","data":59000000},\
			"CanControl":{"type":"b","data":true}}]}""";

	private static final String SIDRA_PAUSED = """
			{"type":"a{sv}","data":[{\
			"PlaybackStatus":{"type":"s","data":"Paused"},\
			"Metadata":{"type":"a{sv}","data":{\
			"mpris:trackid":{"type":"o","data":"/org/sidra/tracklist/d2_i1_o2"},\
			"xesam:title":{"type":"s","data":"hate that i made you love me"},\
			"xesam:artist":{"type":"as","data":["Ariana Grande"]},\
			"xesam:album":{"type":"s","data":"petal"},\
			"mpris:length":{"type":"x","data":198000000},\
			"xesam:url":{"type":"s","data":"https://music.apple.com/sg/album/x"}}},\
			"Position":{"type":"x","data":43000000},\
			"Rate":{"type":"d","data":1.0},\
			"CanControl":{"type":"b","data":true}}]}""";

	private static final String SIDRA_IDLE = """
			{"type":"a{sv}","data":[{\
			"PlaybackStatus":{"type":"s","data":"Stopped"},\
			"Metadata":{"type":"a{sv}","data":{\
			"mpris:trackid":{"type":"o","data":"/org/mpris/MediaPlayer2/TrackList/NoTrack"}}},\
			"Position":{"type":"x","data":0}}]}""";

	@Test
	void parsesFirefoxWithoutDuration() {
		MprisPlayer player = MprisPlayer.parse("org.mpris.MediaPlayer2.firefox.instance_1", FIREFOX_APPLE_MUSIC);

		assertEquals(MprisPlayer.State.PLAYING, player.state());
		assertEquals("So Easy (To Fall In Love)", player.title());
		assertEquals("Olivia Dean", player.artist());
		assertEquals("The Art of Loving", player.album());
		assertEquals(0L, player.lengthMicros());
		assertEquals(0.0, player.durationSeconds());
		assertEquals(59.0, player.positionSeconds());
		assertEquals(1.0, player.rate());
		assertTrue(player.canControl());
		assertTrue(player.hasTitle());
	}

	@Test
	void parsesPausedPlayerWithDuration() {
		MprisPlayer player = MprisPlayer.parse("org.mpris.MediaPlayer2.sidra", SIDRA_PAUSED);

		assertEquals(MprisPlayer.State.PAUSED, player.state());
		assertEquals(198_000_000L, player.lengthMicros());
		assertEquals(198.0, player.durationSeconds());
		assertEquals(43.0, player.positionSeconds());
		assertEquals("/org/sidra/tracklist/d2_i1_o2", player.trackId());
	}

	@Test
	void idlePlayerHasNoTrack() {
		MprisPlayer player = MprisPlayer.parse("org.mpris.MediaPlayer2.sidra", SIDRA_IDLE);

		assertEquals(MprisPlayer.State.STOPPED, player.state());
		assertFalse(player.hasTitle());
	}

	@Test
	void trackKeyIgnoresPosition() {
		MprisPlayer player = MprisPlayer.parse("sidra", SIDRA_PAUSED);

		assertEquals(player.trackKey(), player.withPosition(1L).trackKey());
		assertEquals(1L, player.withPosition(1L).positionMicros());
		assertEquals(player.title(), player.withPosition(1L).title());
	}

	@Test
	void trackKeyChangesWhenTheTrackChanges() {
		MprisPlayer first = MprisPlayer.parse("firefox", FIREFOX_APPLE_MUSIC);
		MprisPlayer second = new MprisPlayer(first.busName(), first.state(), "Another Song",
				first.artist(), first.album(), first.trackId(), first.url(), first.artUrl(),
				first.lengthMicros(), first.positionMicros(), first.rate(), first.canControl());

		assertNotEquals(first.trackKey(), second.trackKey());
	}

	@Test
	void joinsMultipleArtists() {
		String json = """
				{"type":"a{sv}","data":[{"PlaybackStatus":{"type":"s","data":"Playing"},\
				"Metadata":{"type":"a{sv}","data":{"xesam:title":{"type":"s","data":"T"},\
				"xesam:artist":{"type":"as","data":["A","B"]}}}}]}""";

		assertEquals("A, B", MprisPlayer.parse("p", json).artist());
	}

	@Test
	void missingPropertyMapIsNotAPlayer() {
		assertNull(MprisPlayer.parse("p", "{\"type\":\"a{sv}\",\"data\":[]}"));
	}
}
