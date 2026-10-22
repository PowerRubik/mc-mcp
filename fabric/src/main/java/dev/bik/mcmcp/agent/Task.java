package dev.bik.mcmcp.agent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

/** A unit of agent work, ticked on the client thread at the start of every client tick. */
public abstract class Task {
	protected int ticks;

	public record Result(boolean ok, String message) {
		public static Result ok(String message) {
			return new Result(true, message);
		}

		public static Result fail(String message) {
			return new Result(false, message);
		}
	}

	public abstract String name();

	/** @return null while still running, otherwise the outcome. */
	protected abstract @Nullable Result tick(Minecraft mc, LocalPlayer player);

	/** Human-readable progress line for status queries. */
	public String progress() {
		return name() + " (" + ticks + " ticks)";
	}

	/** Tasks are failed as soon as the player dies, unless they opt out (e.g. respawning). */
	public boolean runsWhileDead() {
		return false;
	}

	/** Called once when the task ends for any reason, including cancellation. */
	protected void onEnd() {
	}

	public final @Nullable Result step(Minecraft mc, LocalPlayer player) {
		Result r = tick(mc, player);
		ticks++;
		return r;
	}
}
