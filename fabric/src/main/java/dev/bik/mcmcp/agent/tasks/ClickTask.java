package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.agent.Input;
import dev.bik.mcmcp.agent.Task;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

/** Presses a key once and waits a few ticks for the effect (opening inventory, dropping an item, ...). */
public final class ClickTask extends Task {
	private final String label;
	private final KeyMapping key;
	private final int settle;

	public ClickTask(String label, KeyMapping key, int settle) {
		this.label = label;
		this.key = key;
		this.settle = settle;
	}

	@Override
	public String name() {
		return label;
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		if (ticks == 0) {
			if (mc.gui.screen() != null) {
				return Result.fail("a screen is open (" + mc.gui.screen().getTitle().getString() + "); close it first");
			}
			Input.click(key);
		}
		if (ticks >= settle) {
			var screen = mc.gui.screen();
			return Result.ok(label + " done" + (screen != null ? "; screen open: " + screen.getTitle().getString() : ""));
		}
		return null;
	}
}
