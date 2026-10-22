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

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		if (ticks >= duration || (stopWhenNotUsing && ticks > 5 && !p.isUsingItem())) {
			for (KeyMapping k : keys) {
				Input.set(k, false);
			}
			return Result.ok(String.format("done after %d ticks; now at %.2f %.2f %.2f", ticks, p.getX(), p.getY(), p.getZ()));
		}
		for (KeyMapping k : keys) {
			Input.set(k, true);
		}
		return null;
	}
}
