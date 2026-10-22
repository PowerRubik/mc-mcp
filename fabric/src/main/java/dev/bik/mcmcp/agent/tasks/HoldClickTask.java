package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.agent.Input;
import dev.bik.mcmcp.agent.Task;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * Presses a mouse button and keeps it held, like a finger resting on it: one click on press, then vanilla's
 * held-button behaviour (continuous mining, repeated use every 4 ticks, charging a bow, blocking...).
 */
public final class HoldClickTask extends Task {
	private final boolean left;
	private final int duration;
	private final boolean releaseWhenUseEnds;
	private boolean wasUsing;

	public HoldClickTask(boolean left, int duration, boolean releaseWhenUseEnds) {
		this.left = left;
		this.duration = duration;
		this.releaseWhenUseEnds = releaseWhenUseEnds;
	}

	@Override
	public String name() {
		return "hold " + (left ? "left" : "right") + " click";
	}

	@Override
	public String progress() {
		return name() + " (" + ticks + "/" + duration + " ticks)";
	}

	private KeyMapping key() {
		Minecraft mc = Minecraft.getInstance();
		return left ? mc.options.keyAttack : mc.options.keyUse;
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		if (ticks == 0) {
			if (mc.gui.screen() != null) {
				return Result.fail("a screen is open; close it first");
			}
			Input.click(key());
		}
		wasUsing |= p.isUsingItem();
		boolean useEnded = releaseWhenUseEnds && wasUsing && !p.isUsingItem();
		if (ticks >= duration || useEnded) {
			Input.set(key(), false);
			return Result.ok("released after " + ticks + " ticks" + (useEnded ? " (item use finished)" : "")
				+ "; crosshair now on " + describe(mc.hitResult, p));
		}
		Input.set(key(), true);
		return null;
	}

	@Override
	protected void onEnd() {
		Input.set(key(), false);
	}

	private static String describe(@Nullable HitResult hr, LocalPlayer p) {
		if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
			return MineTask.blockId(p.level().getBlockState(bhr.getBlockPos())) + " at " + bhr.getBlockPos().toShortString();
		}
		if (hr instanceof EntityHitResult ehr) {
			return "entity " + ehr.getEntity().getId();
		}
		return "nothing";
	}
}
