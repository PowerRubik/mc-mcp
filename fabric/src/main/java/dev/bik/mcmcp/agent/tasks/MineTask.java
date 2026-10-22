package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.agent.Aim;
import dev.bik.mcmcp.agent.Input;
import dev.bik.mcmcp.agent.Items;
import dev.bik.mcmcp.agent.Rotation;
import dev.bik.mcmcp.agent.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Looks at a block and holds the attack key until it breaks, exactly as a player holding left click. */
public final class MineTask extends Task {
	private enum Phase { TOOL, AIM, MINE }

	private final BlockPos pos;
	private final boolean autoTool;
	private Phase phase = Phase.TOOL;
	private BlockState original;
	private int phaseStart;
	private int aimAttempts;
	private int budget;
	private boolean toolChosen;

	public MineTask(BlockPos pos, boolean autoTool) {
		this.pos = pos;
		this.autoTool = autoTool;
	}

	@Override
	public String name() {
		return "mine " + pos.toShortString();
	}

	@Override
	public String progress() {
		return name() + ": " + phase.name().toLowerCase();
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		BlockState now = p.level().getBlockState(pos);
		if (ticks == 0) {
			original = now;
			if (now.isAir() || now.getShape(p.level(), pos).isEmpty()) {
				return Result.fail("nothing to mine at " + pos.toShortString() + " (" + blockId(now) + ")");
			}
			float progress = now.getDestroyProgress(p, p.level(), pos);
			if (progress <= 0.0F) {
				return Result.fail(blockId(now) + " is unbreakable");
			}
		} else if (now.getBlock() != original.getBlock()) {
			return Result.ok("mined " + blockId(original) + " at " + pos.toShortString() + " (now " + blockId(now) + ")");
		}

		switch (phase) {
			case TOOL -> {
				if (autoTool && !toolChosen) {
					toolChosen = true;
					int best = Items.bestHotbarTool(p, now);
					if (best >= 0) {
						Input.click(mc.options.keyHotbarSlots[best]);
						return null; // let the selection apply first
					}
				}
				startAim(p);
				return phase == Phase.AIM ? null : Result.fail("block not visible or out of reach ("
					+ String.format(java.util.Locale.ROOT, "%.1f", p.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos))) + " blocks away)"
					+ Aim.obstruction(p, pos));
			}
			case AIM -> {
				if (Aim.crosshairOn(pos, null) && Rotation.aligned(4.0F)) {
					phase = Phase.MINE;
					phaseStart = ticks;
					float progress = now.getDestroyProgress(p, p.level(), pos);
					budget = (int) Math.ceil(1.0F / Math.max(progress, 1e-4F)) + 40;
					Input.click(mc.options.keyAttack);
					Input.set(mc.options.keyAttack, true);
				} else if (ticks - phaseStart > 30) {
					if (++aimAttempts > 3) {
						return Result.fail("could not get the crosshair onto the block");
					}
					startAim(p);
					if (phase != Phase.AIM) {
						return Result.fail("block no longer visible");
					}
				}
				return null;
			}
			case MINE -> {
				if (!Aim.crosshairOn(pos, null)) {
					Input.set(mc.options.keyAttack, false);
					startAim(p);
					return phase == Phase.AIM ? null : Result.fail("lost sight of the block while mining");
				}
				if (ticks - phaseStart > budget) {
					return Result.fail("block did not break in time (" + budget + " ticks)");
				}
				return null;
			}
		}
		return null;
	}

	private void startAim(LocalPlayer p) {
		Aim.Point point = Aim.blockPoint(p, pos, null);
		if (point == null) {
			boolean touching = p.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)) < 1.2;
			if (!touching && !Aim.crosshairOn(pos, null)) {
				phase = Phase.TOOL;
				return;
			}
			// No outside face to aim at (e.g. our head is inside it): look at its middle; the crosshair still resolves to it.
			Rotation.lookAt(p.getEyePosition(), net.minecraft.world.phys.Vec3.atCenterOf(pos));
		} else {
			Rotation.lookAt(p.getEyePosition(), point.point());
		}
		phase = Phase.AIM;
		phaseStart = ticks;
	}

	@Override
	protected void onEnd() {
		Input.set(Minecraft.getInstance().options.keyAttack, false);
	}

	static String blockId(BlockState s) {
		return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
	}
}
