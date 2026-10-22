package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.agent.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

/** Presses the death screen's respawn button once it becomes clickable. */
public final class RespawnTask extends Task {
	private boolean pressed;

	@Override
	public String name() {
		return "respawn";
	}

	@Override
	public boolean runsWhileDead() {
		return true;
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		if (!pressed) {
			if (!p.isDeadOrDying()) {
				return Result.fail("not dead");
			}
			// The button is disabled for the first second of the death screen.
			if (ticks >= 20 && mc.gui.screen() instanceof DeathScreen) {
				p.respawn();
				pressed = true;
			}
			return ticks > 200 ? Result.fail("death screen never appeared") : null;
		}
		if (!p.isDeadOrDying() && mc.gui.screen() == null) {
			return Result.ok(String.format("respawned at %.1f %.1f %.1f", p.getX(), p.getY(), p.getZ()));
		}
		return ticks > 200 ? Result.fail("respawn did not complete") : null;
	}
}
