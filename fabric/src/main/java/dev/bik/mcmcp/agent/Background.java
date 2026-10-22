package dev.bik.mcmcp.agent;

import dev.bik.mcmcp.Config;
import net.minecraft.client.Minecraft;

/** Headless-ish mode for an unfocused window: no world rendering, steady frame loop so ticks stay evenly spaced. */
public final class Background {
	private Background() {
	}

	/**
	 * Skip world rendering. Never while a screen is open: on join/respawn the loading screen waits until the player's
	 * chunk section has been compiled for rendering, and until then the player is frozen and sends no movement packets.
	 */
	public static boolean active() {
		Minecraft mc = Minecraft.getInstance();
		// Overlays (e.g. the resource-loading fade) remove themselves from their render pass, and vanilla ignores all
		// clicks and key presses while one is up, so keep rendering until it is gone.
		return unfocused() && mc.level != null && mc.gui.screen() == null && mc.gui.overlay() == null;
	}

	/**
	 * Skip acquiring and presenting frames, and run the loop at a steady rate. Safe even with screens open: section
	 * compilation needs the world render pass, not the present. Presenting to a window the compositor is not showing
	 * can block for about a second (seen on Wayland), which stalls ticks.
	 */
	public static boolean unfocused() {
		Minecraft mc = Minecraft.getInstance();
		return Config.get().skipRenderingWhenUnfocused && !mc.isWindowActive();
	}
}
