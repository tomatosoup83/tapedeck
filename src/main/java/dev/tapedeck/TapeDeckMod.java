package dev.tapedeck;

import dev.tapedeck.hud.NowPlayingHud;
import dev.tapedeck.music.MusicBridge;
import dev.tapedeck.music.NowPlayingInfo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. Owns the {@link MusicBridge} and registers the HUD element that
 * renders its snapshots.
 */
public final class TapeDeckMod implements ClientModInitializer {

	public static final String MOD_ID = "tapedeck";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	private static MusicBridge bridge;

	@Override
	public void onInitializeClient() {
		if (!MusicBridge.isSupportedPlatform()) {
			LOG.warn("[tapedeck] macOS only; the Apple Music HUD will not be shown.");
			return;
		}

		MusicBridge started = MusicBridge.create();
		started.start();
		bridge = started;

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(MOD_ID, "now_playing"),
				(graphics, deltaTracker) -> NowPlayingHud.render(graphics, started.snapshot()));

		Runtime.getRuntime().addShutdownHook(
				new Thread(started::close, "tapedeck-bridge-shutdown"));

		LOG.info("[tapedeck] ready - reading Apple Music via osascript");
	}

	/** The live snapshot, or a placeholder when the bridge is disabled or not started yet. */
	public static NowPlayingInfo nowPlaying() {
		return bridge == null ? NowPlayingInfo.unavailable("not started") : bridge.snapshot();
	}

	/** The live bridge, or {@code null} on unsupported platforms. */
	public static MusicBridge bridge() {
		return bridge;
	}
}