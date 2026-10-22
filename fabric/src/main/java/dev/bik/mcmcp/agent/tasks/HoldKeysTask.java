package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.agent.Input;
import dev.bik.mcmcp.agent.Task;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** Holds a set of keys for a number of ticks: raw movement, using/eating, holding attack. */
public final class HoldKeysTask extends Task {
	private final String label;
	private final List<KeyMapping> keys;
	private final int duration;
	private final boolean stopWhenNotUsing;

	public HoldKeysTask(String label, List<KeyMapping> keys, int duration, boolean stopWhenNotUsing) {
		this.label = label;
		this.keys = keys;
		this.duration = duration;
		this.stopWhenNotUsing = stopWhenNotUsing;
	}

	@Override
	public String name() {
		return label;
	}

	@Override
	public String progress() {
		return label + " (" + ticks + "/" + duration + " ticks)";
	}

	private int stoppedAt = -1;

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		if (stoppedAt < 0 && (ticks >= duration || (stopWhenNotUsing && ticks > 5 && !p.isUsingItem()))) {
			stoppedAt = ticks;
			for (KeyMapping k : keys) {
				if (k != mc.options.keyShift) {
					Input.set(k, false);
				}
			}
		}
		if (stoppedAt >= 0) {
			// Keep sneak held until momentum has died down, so the bot cannot coast off an edge it stopped at.
			if (keys.contains(mc.options.keyShift) && ticks - stoppedAt < 4) {
				return null;
			}
			Input.set(mc.options.keyShift, false);
			return Result.ok(String.format("done after %d ticks; now at %.2f %.2f %.2f", stoppedAt, p.getX(), p.getY(), p.getZ()));
		}
		for (KeyMapping k : keys) {
			Input.set(k, true);
		}
		return null;
	}
}
