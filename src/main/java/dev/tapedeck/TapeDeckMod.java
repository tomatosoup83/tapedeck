package dev.tapedeck;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.tapedeck.hud.NowPlayingHud;
import dev.tapedeck.music.NowPlayingInfo;
import dev.tapedeck.music.NowPlayingSource;
import dev.tapedeck.music.SourceFactory;
import dev.tapedeck.music.TapedeckConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Client entrypoint. Owns the platform {@link NowPlayingSource}, registers the HUD element
 * that renders its snapshots, the {@code /tapedeck} command, and the toggle key binding.
 */
public final class TapeDeckMod implements ClientModInitializer {

	public static final String MOD_ID = "tapedeck";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	/** Translation key of the toggle key; see assets/tapedeck/lang/en_us.json. */
	private static final String TOGGLE_KEY = "key.tapedeck.toggle";

	/** Common sizes offered by tab completion; any value in range is still accepted. */
	private static final SuggestionProvider<FabricClientCommandSource> SIZE_SUGGESTIONS =
			(context, builder) -> {
				for (String option : new String[]{"50", "75", "100", "125", "150", "200"}) {
					builder.suggest(option);
				}
				return builder.buildFuture();
			};

	private static NowPlayingSource source;
	private static KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		Path configPath = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".properties");
		TapedeckConfig config = TapedeckConfig.load(configPath);
		NowPlayingHud.setSizePercent(config.hudSize());
		NowPlayingHud.setHidden(config.hudHidden());

		NowPlayingSource started = SourceFactory.create(config);
		started.start();
		source = started;

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(MOD_ID, "now_playing"),
				(graphics, deltaTracker) -> NowPlayingHud.render(graphics, started.snapshot()));

		Runtime.getRuntime().addShutdownHook(
				new Thread(started::close, "tapedeck-bridge-shutdown"));

		registerKeyBinding(configPath);
		registerCommands(configPath);

		LOG.info("[tapedeck] ready - {} at {}%", started.getClass().getSimpleName(),
				NowPlayingHud.sizePercent());
	}

	/**
	 * Registers the toggle key. It is a regular {@link KeyMapping}, so it shows up under
	 * Options - Controls - Tapedeck and players can rebind or clear it like any other key.
	 */
	private static void registerKeyBinding(Path configPath) {
		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping(TOGGLE_KEY, InputConstants.KEY_F8, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.consumeClick()) {
				boolean hidden = NowPlayingHud.toggleHidden();
				TapedeckConfig.writeHudHidden(configPath, hidden);
				toast(client, hidden);
			}
		});
	}

	/** Action-bar confirmation, so a toggle in a quiet moment is not a silent no-op. */
	private static void toast(Minecraft client, boolean hidden) {
		client.gui.hud.setOverlayMessage(
				Component.literal("Tapedeck HUD " + (hidden ? "hidden" : "shown")), false);
	}

	/**
	 * Registers {@code /tapedeck toggle} and {@code /tapedeck size <percent>}.
	 *
	 * <p>Client commands have to use {@link ClientCommands}' builders: vanilla's
	 * {@code Commands.literal} is bound to {@code CommandSourceStack}, which cannot be
	 * registered on a client dispatcher. The commands are client-side only, so they need no
	 * server permission and work on any server.
	 */
	private static void registerCommands(Path configPath) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			LiteralArgumentBuilder<FabricClientCommandSource> toggle =
					ClientCommands.literal("toggle").executes(context -> {
						boolean hidden = NowPlayingHud.toggleHidden();
						TapedeckConfig.writeHudHidden(configPath, hidden);
						context.getSource().sendFeedback(Component.literal(
								"Tapedeck HUD " + (hidden ? "hidden" : "shown")));
						return 1;
					});

			LiteralArgumentBuilder<FabricClientCommandSource> size =
					ClientCommands.literal("size")
							.executes(context -> {
								context.getSource().sendFeedback(Component.literal(
										"Tapedeck size: " + NowPlayingHud.sizePercent() + "%"));
								return 1;
							})
							.then(ClientCommands.argument("percent",
											IntegerArgumentType.integer(
													TapedeckConfig.MIN_HUD_SIZE,
													TapedeckConfig.MAX_HUD_SIZE))
									.suggests(SIZE_SUGGESTIONS)
									.executes(context -> {
										int percent = IntegerArgumentType.getInteger(context, "percent");
										int applied = NowPlayingHud.setSizePercent(percent);
										TapedeckConfig.writeHudSize(configPath, applied);
										context.getSource().sendFeedback(Component.literal(
												"Tapedeck size: " + applied + "%"));
										return applied;
									}));

			LiteralArgumentBuilder<FabricClientCommandSource> root =
					ClientCommands.literal("tapedeck")
							.executes(context -> {
								context.getSource().sendFeedback(Component.literal(
										"Tapedeck: "
												+ (NowPlayingHud.isHidden() ? "hidden" : "shown")
												+ ", size " + NowPlayingHud.sizePercent()
												+ "% - /tapedeck toggle, /tapedeck size <"
												+ TapedeckConfig.MIN_HUD_SIZE + "-"
												+ TapedeckConfig.MAX_HUD_SIZE + ">"));
								return 1;
							})
							.then(toggle)
							.then(size);

			dispatcher.register(root);
		});
	}

	/** The live snapshot, or a placeholder when the bridge is disabled or not started yet. */
	public static NowPlayingInfo nowPlaying() {
		return source == null ? NowPlayingInfo.unavailable("not started") : source.snapshot();
	}

	/** The live source, or {@code null} before initialisation. */
	public static NowPlayingSource source() {
		return source;
	}
}
