package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.agent.Rotation;
import dev.bik.mcmcp.agent.Task;
import dev.bik.mcmcp.agent.Vision;
import dev.bik.mcmcp.path.Pathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Frontier exploration: walk to the nearest seen, standable spot that borders unseen space (optionally in one
 * direction). Repeated calls map out caves and terrain using only what the player has looked at.
 */
public final class ExploreTask extends Task {
	private static final int MIN_DISTANCE = 3;
	/** Yaw offsets and pitches of the look-around sweep. */
	private static final float[][] SWEEP = {{0, 30}, {90, 30}, {180, 30}, {270, 30}, {315, 65}, {225, 65}, {135, 65}, {45, 65}, {0, -25}};

	private final @Nullable Direction direction;
	private final int maxDistance;
	private BlockPos start;
	private long seenBefore;
	private @Nullable Pathfinder search;
	private @Nullable GotoTask walk;
	private @Nullable BlockPos target;
	private boolean sweptOnce;
	private int sweepStep = -1;
	private float sweepBaseYaw;
	private int alignedSince = -1;

	public ExploreTask(@Nullable Direction direction, int maxDistance) {
		this.direction = direction;
		this.maxDistance = maxDistance;
	}

	@Override
	public String name() {
		return "explore" + (direction != null ? " " + direction.getName() : "");
	}

	@Override
	public String progress() {
		if (walk != null) {
			return name() + ": heading to frontier " + target.toShortString() + " - " + walk.progress();
		}
		return name() + ": looking for unexplored edges" + (search != null ? " (" + search.expanded() + " nodes)" : "");
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		if (!Vision.noXray()) {
			return Result.fail("explore only makes sense in no-x-ray mode (it is off: every loaded block is already known)");
		}
		if (ticks == 0) {
			seenBefore = Vision.seenCount();
			startSearch(p);
		}
		if (sweepStep >= 0) {
			// Look around like a player arriving somewhere new, then search again.
			if (Rotation.aligned(2.0F)) {
				if (alignedSince < 0) {
					alignedSince = ticks;
				} else if (ticks - alignedSince >= 4) {
					alignedSince = -1;
					if (++sweepStep >= SWEEP.length) {
						sweepStep = -1;
						startSearch(p);
						return null;
					}
					Rotation.setTarget(sweepBaseYaw + SWEEP[sweepStep][0], SWEEP[sweepStep][1]);
				}
			}
			return null;
		}
		if (search != null) {
			Pathfinder.State s = search.step(3000);
			if (s == Pathfinder.State.SEARCHING) {
				return null;
			}
			if (s != Pathfinder.State.FOUND && !sweptOnce) {
				sweptOnce = true;
				search = null;
				sweepStep = 0;
				sweepBaseYaw = p.getYRot();
				Rotation.setTarget(sweepBaseYaw + SWEEP[0][0], SWEEP[0][1]);
				return null;
			}
			if (s != Pathfinder.State.FOUND) {
				return Result.fail("no reachable unexplored edge within " + maxDistance + " blocks"
					+ (direction != null ? " to the " + direction.getName() : "") + " (" + search.expanded() + " positions checked)");
			}
			List<Pathfinder.Node> path = search.path();
			target = path.get(path.size() - 1).pos();
			search = null;
			walk = new GotoTask(target, path, false);
		}
		Result r = walk.step(mc, p);
		if (r == null) {
			return null;
		}
		String seen = "; " + (Vision.seenCount() - seenBefore) + " new blocks seen";
		return r.ok() ? Result.ok("reached frontier at " + p.blockPosition().toShortString() + seen)
			: Result.fail("walking to frontier " + target.toShortString() + " failed: " + r.message() + seen);
	}

	private void startSearch(LocalPlayer p) {
		start = p.blockPosition();
		search = new Pathfinder(p.level(), start, p.getY(), new FrontierGoal(), Pathfinder.Knowledge.pessimistic(Vision::known), 40_000);
	}

	private final class FrontierGoal implements Pathfinder.Goal {
		@Override
		public boolean test(BlockPos pos) {
			int dx = pos.getX() - start.getX(), dz = pos.getZ() - start.getZ();
			double dist = Math.sqrt(dx * dx + dz * dz);
			if (dist < MIN_DISTANCE || dist > maxDistance) {
				return false;
			}
			if (direction != null && (dx * direction.getStepX() + dz * direction.getStepZ()) < dist * 0.5) {
				return false; // more than 60 degrees off the requested direction
			}
			if (!Pathfinder.seenGround(Minecraft.getInstance().level, Vision::known, pos)) {
				return false; // only stop on ground we have actually seen
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos n = pos.relative(d);
				if (!Vision.known(n) || !Vision.known(n.above())) {
					return true;
				}
			}
			return false;
		}

		@Override
		public double heuristic(BlockPos pos) {
			return 0.0;
		}
	}
}
