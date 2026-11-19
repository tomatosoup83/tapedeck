package dev.tapedeck.hud;

import dev.tapedeck.music.NowPlayingInfo;
import dev.tapedeck.music.TapedeckConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.joml.Matrix3x2fStack;

/**
 * Draws the Apple Music now-playing panel in the top-left corner of the HUD.
 *
 * <p>Runs on the render thread only and reads an immutable {@link NowPlayingInfo}
 * snapshot, so it never blocks on the bridge.
 */
public final class NowPlayingHud {

	private static final int MARGIN = 4;
	private static final int BORDER = 1;
	private static final int PADDING = 4;
	private static final int LINE_HEIGHT = 9;
	private static final int LINE_GAP = 2;
	private static final int MAX_CONTENT_WIDTH = 220;

	/** Extra right-hand room for the one-pixel drop shadow that {@code text(..., true)} draws. */
	private static final int SHADOW_ALLOWANCE = 1;

	private static final int COLOR_PANEL = 0xB0100010;
	private static final int COLOR_TITLE = 0xFFFFFFFF;
	private static final int COLOR_TIME = 0xFFA0A0A0;
	private static final int COLOR_BORDER_PLAYING = 0xFF5AC85A;
	private static final int COLOR_BORDER_PAUSED = 0xFFD8A657;

	/** Seconds to fade the panel in or out. */
	private static final float FADE_SECONDS = 0.2f;

	private static float opacity;
	private static long lastFrameMillis;
	private static String lastKey = "";

	/** Percentage of the default layout; changed by {@code /tapedeck size}. */
	private static volatile int sizePercent = TapedeckConfig.DEFAULT_HUD_SIZE;

	/** Set by {@code /tapedeck toggle} or its key binding. */
	private static volatile boolean hidden;

	private NowPlayingHud() {
	}

	/** Current panel size as a percentage of the default layout. */
	public static int sizePercent() {
		return sizePercent;
	}

	/** Applies a new size, clamped to the supported range; returns the value actually used. */
	public static int setSizePercent(int percent) {
		int applied = TapedeckConfig.clampHudSize(percent);
		sizePercent = applied;
		return applied;
	}

	public static boolean isHidden() {
		return hidden;
	}

	public static void setHidden(boolean value) {
		hidden = value;
	}

	/** Flips visibility and returns the new state. */
	public static boolean toggleHidden() {
		hidden = !hidden;
		return hidden;
	}

	public static void render(GuiGraphicsExtractor graphics, NowPlayingInfo info) {
		// Opacity keeps updating while hidden, so unhiding fades the panel back in instead
		// of popping it onto the screen at full brightness.
		updateOpacity(info);
		if (hidden || opacity < 0.01f) {
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;

		Component heading = buildHeading(font, info);
		// Not every source reports a duration: Apple Music's web player, for one, supplies
		// no `mpris:length` at all. Show elapsed time alone rather than dropping the line.
		boolean hasDuration = info.duration() > 0.0;
		boolean showTime = hasDuration || info.clampedPosition() > 0.0;
		String timeText = hasDuration
				? NowPlayingInfo.formatTime(info.clampedPosition())
						+ "/" + NowPlayingInfo.formatTime(info.duration())
				: NowPlayingInfo.formatTime(info.clampedPosition());
		Component time = showTime ? Component.literal(timeText) : null;

		// Widths are measured in unscaled units; the pose below scales the whole panel.
		// Shadowed text is drawn one pixel wider than measured, hence the slack.
		int contentWidth = Math.max(font.width(heading), time == null ? 0 : font.width(time))
				+ SHADOW_ALLOWANCE;

		int panelWidth = BORDER * 2 + PADDING * 2 + contentWidth;
		int panelHeight = BORDER * 2 + PADDING * 2 + LINE_HEIGHT
				+ (time == null ? 0 : LINE_GAP + LINE_HEIGHT);

		int alpha = Math.round(Math.min(1.0f, opacity) * 255.0f);
		int borderColor = scale(info.isPlaying() ? COLOR_BORDER_PLAYING : COLOR_BORDER_PAUSED, alpha);

		// Size is a zoom around the top-left margin: the panel keeps its distance from the
		// screen corner and grows right and down. Push/pop so the transform cannot leak
		// into the vanilla HUD elements drawn after this one.
		float zoom = sizePercent / 100.0f;
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		try {
			pose.translate(MARGIN, MARGIN);
			pose.scale(zoom, zoom);

			graphics.fill(0, 0, panelWidth, panelHeight, scale(COLOR_PANEL, alpha));
			drawBorder(graphics, 0, 0, panelWidth, panelHeight, borderColor);

			int textX = BORDER + PADDING;
			int textY = BORDER + PADDING;

			graphics.text(font, heading, textX, textY, scale(COLOR_TITLE, alpha), true);

			if (time != null) {
				graphics.text(font, time, textX, textY + LINE_HEIGHT + LINE_GAP,
						scale(COLOR_TIME, alpha), true);
			}
		} finally {
			pose.popMatrix();
		}
	}

	/** Four fills are cheaper than a nine-slice here; the border is only one pixel. */
	private static void drawBorder(GuiGraphicsExtractor graphics, int x, int y,
	                              int width, int height, int color) {
		graphics.fill(x, y, x + width, y + BORDER, color);
		graphics.fill(x, y + height - BORDER, x + width, y + height, color);
		graphics.fill(x, y, x + BORDER, y + height, color);
		graphics.fill(x + width - BORDER, y, x + width, y + height, color);
	}

	/**
	 * Exponential approach to the target opacity so the panel fades instead of popping
	 * as tracks change. A new track restarts the fade; a pause does not, because the key
	 * is derived from the metadata and not from the playback state.
	 */
	private static void updateOpacity(NowPlayingInfo info) {
		boolean visible = info.hasTrack() && !hidden;
		if (!info.key().equals(lastKey)) {
			lastKey = info.key();
			if (visible) {
				opacity = 0.0f;
			}
		}

		long now = Util.getMillis();
		if (lastFrameMillis == 0L) {
			opacity = visible ? 1.0f : 0.0f;
		} else {
			float dt = (now - lastFrameMillis) / 1000.0f;
			opacity += ((visible ? 1.0f : 0.0f) - opacity) * clamp(dt / FADE_SECONDS, 0.0f, 1.0f);
		}
		lastFrameMillis = now;

		if (opacity < 0.0f) {
			opacity = 0.0f;
		} else if (opacity > 1.0f) {
			opacity = 1.0f;
		}
	}

	/** Title and artist on one line, degrading gracefully when either is missing. */
	private static Component buildHeading(Font font, NowPlayingInfo info) {
		String title = info.title();
		String artist = info.artist();
		String text;
		if (artist.isEmpty()) {
			text = title;
		} else if (title.isEmpty()) {
			text = artist;
		} else {
			// ASCII hyphen: the vanilla font has no reliable glyph for an em dash.
			text = title + " - " + artist;
		}
		return ellipsise(font, text, MAX_CONTENT_WIDTH);
	}

	/** Replaces the alpha byte, leaving the RGB channels alone. */
	private static int scale(int argb, int alpha) {
		return (argb & 0x00FFFFFF) | (alpha << 24);
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	/** Appends an ellipsis until the text fits; the HUD has no text wrapping. */
	private static Component ellipsise(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return Component.literal(text);
		}
		String working = text;
		while (!working.isEmpty() && font.width(working + "...") > maxWidth) {
			working = working.substring(0, working.length() - 1);
		}
		return Component.literal(working.isEmpty() ? "..." : working + "...");
	}
}