package dev.tapedeck.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TapedeckConfigTest {

	@Test
	void defaultsAreConservative() {
		TapedeckConfig config = TapedeckConfig.defaults();

		assertEquals(1_000L, config.pollMillis());
		assertEquals(5_000L, config.resyncMillis());
		assertEquals(TapedeckConfig.Mode.AUTO, config.mode());
		assertEquals(-1, config.priorityIndex("org.mpris.MediaPlayer2.firefox"));
		assertFalse(config.isIgnored("org.mpris.MediaPlayer2.firefox"));
	}

	@Test
	void readsEveryKey() {
		Properties properties = new Properties();
		properties.setProperty("priority", "firefox, vlc");
		properties.setProperty("ignore", "kdeconnect");
		properties.setProperty("poll-ms", "400");
		properties.setProperty("resync-ms", "2000");
		properties.setProperty("mode", "poll");

		TapedeckConfig config = TapedeckConfig.parse(properties);

		assertEquals(400L, config.pollMillis());
		assertEquals(2_000L, config.resyncMillis());
		assertEquals(TapedeckConfig.Mode.POLL, config.mode());
	}

	@Test
	void priorityMatchesBusNameSubstringsInOrder() {
		TapedeckConfig config = TapedeckConfig.parse(priority("firefox, vlc"));

		assertEquals(0, config.priorityIndex("org.mpris.MediaPlayer2.firefox.instance_1_135"));
		assertEquals(1, config.priorityIndex("org.mpris.MediaPlayer2.vlc"));
		assertEquals(-1, config.priorityIndex("org.mpris.MediaPlayer2.spotify"));
	}

	@Test
	void ignoreIsCaseInsensitiveAndRegexCapable() {
		TapedeckConfig config = TapedeckConfig.parse(ignore("KDEConnect"));

		assertTrue(config.isIgnored("org.mpris.MediaPlayer2.kdeconnect.device_1"));
		assertFalse(config.isIgnored("org.mpris.MediaPlayer2.firefox"));

		TapedeckConfig regex = TapedeckConfig.parse(ignore("kdeconnect.*phone"));
		assertTrue(regex.isIgnored("org.mpris.MediaPlayer2.kdeconnect.phone"));
		assertFalse(regex.isIgnored("org.mpris.MediaPlayer2.kdeconnect.tablet"));
	}

	@Test
	void brokenRegexFallsBackToALiteral() {
		TapedeckConfig config = TapedeckConfig.parse(ignore("["));

		// Must not throw while loading: a stray bracket in the config cannot break the mod.
		assertTrue(config.isIgnored("org.mpris.MediaPlayer2.[weird"));
		assertFalse(config.isIgnored("org.mpris.MediaPlayer2.firefox"));
	}

	@Test
	void numbersAreClampedAndInvalidOnesFallBack() {
		Properties properties = new Properties();
		properties.setProperty("poll-ms", "10");
		properties.setProperty("resync-ms", "nonsense");
		properties.setProperty("mode", "something-else");

		TapedeckConfig config = TapedeckConfig.parse(properties);

		assertEquals(250L, config.pollMillis());
		assertEquals(5_000L, config.resyncMillis());
		assertEquals(TapedeckConfig.Mode.AUTO, config.mode());
	}

	@Test
	void blankListsMeanNoRules() {
		TapedeckConfig config = TapedeckConfig.parse(new Properties());

		assertTrue(config.priority().isEmpty());
		assertEquals(-1, config.priorityIndex("anything"));
		assertFalse(config.isIgnored("anything"));
	}

	@Test
	void hudSizeDefaultsAndParses() {
		assertEquals(100, TapedeckConfig.defaults().hudSize());

		Properties properties = new Properties();
		properties.setProperty("hud-size", "80");
		assertEquals(80, TapedeckConfig.parse(properties).hudSize());
	}

	@Test
	void hudSizeIsClamped() {
		assertEquals(50, TapedeckConfig.clampHudSize(10));
		assertEquals(200, TapedeckConfig.clampHudSize(900));
		assertEquals(100, TapedeckConfig.clampHudSize(100));

		Properties tooBig = new Properties();
		tooBig.setProperty("hud-size", "400");
		assertEquals(200, TapedeckConfig.parse(tooBig).hudSize());

		Properties nonsense = new Properties();
		nonsense.setProperty("hud-size", "huge");
		assertEquals(100, TapedeckConfig.parse(nonsense).hudSize());
	}

	@Test
	void hudSizeIsWrittenWithoutLosingComments(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("tapedeck.properties");
		Files.writeString(file, """
				# my priority list
				priority = firefox
				hud-size = 100
				ignore = kdeconnect
				""");

		assertTrue(TapedeckConfig.writeHudSize(file, 80));

		String updated = Files.readString(file);
		assertTrue(updated.contains("hud-size = 80"), updated);
		assertTrue(updated.contains("# my priority list"), updated);
		assertTrue(updated.contains("priority = firefox"), updated);
		assertTrue(updated.contains("ignore = kdeconnect"), updated);
	}

	@Test
	void hudSizeIsAppendedWhenMissing(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("tapedeck.properties");
		Files.writeString(file, "mode = poll\n");

		assertTrue(TapedeckConfig.writeHudSize(file, 125));

		String updated = Files.readString(file);
		assertTrue(updated.contains("mode = poll"), updated);
		assertTrue(updated.contains("hud-size = 125"), updated);
	}

	@Test
	void hudSizeWritesClampedValues(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("tapedeck.properties");
		Files.writeString(file, "hud-size = 100\n");

		assertTrue(TapedeckConfig.writeHudSize(file, 900));

		assertTrue(Files.readString(file).contains("hud-size = 200"));
	}

	@Test
	void hudHiddenDefaultsToShownAndParses() {
		assertFalse(TapedeckConfig.defaults().hudHidden());

		Properties hidden = new Properties();
		hidden.setProperty("hud-hidden", "true");
		assertTrue(TapedeckConfig.parse(hidden).hudHidden());

		Properties nonsense = new Properties();
		nonsense.setProperty("hud-hidden", "sometimes");
		assertFalse(TapedeckConfig.parse(nonsense).hudHidden());
	}

	@Test
	void hudHiddenIsWrittenWithoutLosingComments(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("tapedeck.properties");
		Files.writeString(file, """
				# keep me
				hud-size = 80
				hud-hidden = false
				priority = firefox
				""");

		assertTrue(TapedeckConfig.writeHudHidden(file, true));

		String updated = Files.readString(file);
		assertTrue(updated.contains("hud-hidden = true"), updated);
		assertTrue(updated.contains("# keep me"), updated);
		assertTrue(updated.contains("hud-size = 80"), updated);
		assertTrue(updated.contains("priority = firefox"), updated);
	}

	@Test
	void hudHiddenIsAppendedWhenMissing(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("tapedeck.properties");
		Files.writeString(file, "mode = auto\n");

		assertTrue(TapedeckConfig.writeHudHidden(file, true));

		String updated = Files.readString(file);
		assertTrue(updated.contains("mode = auto"), updated);
		assertTrue(updated.contains("hud-hidden = true"), updated);
	}

	private static Properties priority(String value) {
		Properties properties = new Properties();
		properties.setProperty("priority", value);
		return properties;
	}

	private static Properties ignore(String value) {
		Properties properties = new Properties();
		properties.setProperty("ignore", value);
		return properties;
	}
}
