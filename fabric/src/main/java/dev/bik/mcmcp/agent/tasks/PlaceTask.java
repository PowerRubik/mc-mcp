package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.agent.Aim;
import dev.bik.mcmcp.agent.Input;
import dev.bik.mcmcp.agent.Rotation;
import dev.bik.mcmcp.agent.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Places the held item into {@code target} by right-clicking the face of an adjacent block, the only way vanilla
 * can place. The crosshair must really be on that face, so the server receives a normal, consistent use packet.
 */
public final class PlaceTask extends Task {
	private enum Phase { PLAN, AIM, SNEAK, CLICK, VERIFY }

	private final BlockPos target;
	private final @Nullable Boolean sneakOverride;
	private Phase phase = Phase.PLAN;
	private BlockPos support;
	private Direction face;
	private boolean sneak;
	private int phaseStart;
	private int attempts;
	private BlockState before;

	public PlaceTask(BlockPos target, @Nullable Boolean sneak) {
		this.target = target;
		this.sneakOverride = sneak;
	}

	@Override
	public String name() {
		return "place at " + target.toShortString();
	}

	@Override
	public String progress() {
		return name() + ": " + phase.name().toLowerCase();
	}

	private void enter(Phase p) {
		phase = p;
		phaseStart = ticks;
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		Level level = p.level();
		switch (phase) {
			case PLAN -> {
				before = level.getBlockState(target);
				if (!before.canBeReplaced()) {
					return Result.fail("target " + target.toShortString() + " is occupied by " + MineTask.blockId(before));
				}
				if (p.getMainHandItem().isEmpty()) {
					return Result.fail("main hand is empty");
				}
				if (p.getBoundingBox().intersects(new AABB(target))) {
					return Result.fail("you are standing in the target block; move first");
				}
				Aim.Point best = null;
				BlockPos bestSupport = null;
				Direction bestFace = null;
				double bestDist = Double.MAX_VALUE;
				// Replaceable blocks with a hitbox (grass, ferns, snow layers...): vanilla places into the clicked
				// block itself, and they would block the crosshair from reaching the faces behind them anyway.
				if (!before.isAir() && !before.getShape(level, target).isEmpty()) {
					Aim.Point self = Aim.blockPoint(p, target, null);
					if (self != null) {
						best = self;
						bestSupport = target;
						bestFace = self.face();
						bestDist = 0;
					}
				}
				for (Direction d : best != null ? new Direction[0] : Direction.values()) {
					BlockPos s = target.relative(d);
					BlockState ss = level.getBlockState(s);
					if (ss.canBeReplaced() || ss.getShape(level, s).isEmpty()) {
						continue;
					}
					Aim.Point point = Aim.blockPoint(p, s, d.getOpposite());
					if (point == null) {
						continue;
					}
					double dist = point.point().distanceToSqr(p.getEyePosition());
					if (dist < bestDist) {
						bestDist = dist;
						best = point;
						bestSupport = s;
						bestFace = d.getOpposite();
					}
				}
				if (best == null) {
					return Result.fail("no visible adjacent block face within reach to place against" + Aim.obstruction(p, target.below()));
				}
				support = bestSupport;
				face = bestFace;
				Block supportBlock = level.getBlockState(support).getBlock();
				sneak = sneakOverride != null ? sneakOverride : isInteractive(supportBlock);
				Rotation.lookAt(p.getEyePosition(), best.point());
				enter(Phase.AIM);
				return null;
			}
			case AIM -> {
				if (Aim.crosshairOn(support, face) && Rotation.aligned(4.0F)) {
					enter(sneak ? Phase.SNEAK : Phase.CLICK);
				} else if (ticks - phaseStart > 30) {
					if (++attempts > 2) {
						return Result.fail("could not get the crosshair onto " + support.toShortString() + " " + face);
					}
					enter(Phase.PLAN);
				}
				return null;
			}
			case SNEAK -> {
				Input.set(mc.options.keyShift, true);
				if (ticks - phaseStart >= 3 && p.isShiftKeyDown()) {
					enter(Phase.CLICK);
				}
				return null;
			}
			case CLICK -> {
				if (!Aim.crosshairOn(support, face)) {
					enter(Phase.AIM);
					return null;
				}
				Input.click(mc.options.keyUse);
				enter(Phase.VERIFY);
				return null;
			}
			case VERIFY -> {
				BlockState now = level.getBlockState(target);
				if (now != before) {
					return Result.ok("placed " + MineTask.blockId(now) + " at " + target.toShortString());
				}
				if (ticks - phaseStart > 10) {
					return Result.fail("clicked but nothing was placed (item not placeable, entity in the way, or server rejected)");
				}
				return null;
			}
		}
		return null;
	}

	@Override
	protected void onEnd() {
		Input.set(Minecraft.getInstance().options.keyShift, false);
	}

	private static boolean isInteractive(Block b) {
		return b instanceof BaseEntityBlock || b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
			|| b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof BedBlock || b instanceof CraftingTableBlock
			|| b instanceof AnvilBlock || b instanceof NoteBlock || b instanceof DiodeBlock;
	}
}
