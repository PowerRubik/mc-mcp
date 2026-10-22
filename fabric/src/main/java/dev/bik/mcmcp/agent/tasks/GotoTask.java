package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.agent.Input;
import dev.bik.mcmcp.agent.Rotation;
import dev.bik.mcmcp.agent.Task;
import dev.bik.mcmcp.agent.Vision;
import dev.bik.mcmcp.path.Pathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** Walks to a position by holding movement keys and steering with the virtual mouse. */
public final class GotoTask extends Task {
	private static final int NODES_PER_TICK = 3000;
	private static final int MAX_NODES = 60000;
	private static final int MAX_REPLANS = 8;
	private static final int MAX_REVEAL_REPLANS = 60;

	private final BlockPos goal;
	private final double range;
	private final boolean anyY;
	private final boolean sprint;
	private @Nullable Pathfinder pathfinder;
	private List<Pathfinder.Node> path = List.of();
	private int index;
	private int replans;
	private int revealReplans;
	private int lastProgressTick;
	private double lastDistance = Double.MAX_VALUE;
	private boolean partial;
	private @Nullable Vec3 gaze;
	private @Nullable List<Pathfinder.Node> route;

	/** @param anyY ignore {@code goal.getY()} and accept any height in the target column. */
	/** Follow an already planned route; replans only if it turns out blocked. */
	public GotoTask(BlockPos goal, List<Pathfinder.Node> route, boolean sprint) {
		this(goal, 0, false, sprint);
		this.route = route;
	}

	public GotoTask(BlockPos goal, double range, boolean anyY, boolean sprint) {
		this.goal = goal;
		this.range = range;
		this.anyY = anyY;
		this.sprint = sprint;
	}

	@Override
	public String name() {
		return "goto " + (anyY ? goal.getX() + ", ~, " + goal.getZ() : goal.toShortString());
	}

	@Override
	public String progress() {
		if (pathfinder != null) {
			return name() + ": searching (" + pathfinder.expanded() + " nodes)";
		}
		return name() + ": node " + index + "/" + path.size() + (partial ? " (partial path, will replan)" : "") + ", replans " + replans
			+ (revealReplans > 0 ? ", replans from newly seen terrain " + revealReplans : "");
	}

	/** Same criterion as {@link Pathfinder#isGoal}, applied to the block the player stands in. */
	private boolean arrived(LocalPlayer p) {
		BlockPos feet = p.blockPosition();
		double dx = feet.getX() - goal.getX(), dz = feet.getZ() - goal.getZ();
		double dy = anyY ? 0.0 : feet.getY() - goal.getY();
		double r = Math.max(range, 0.0);
		return dx * dx + dy * dy + dz * dz <= r * r;
	}

	private void plan(LocalPlayer p) {
		pathfinder = new Pathfinder(p.level(), p.blockPosition(), p.getY(), Pathfinder.Goal.near(goal, range, anyY), Vision::known, MAX_NODES);
		path = List.of();
		index = 0;
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		Options o = mc.options;
		if (ticks == 0) {
			if (arrived(p)) {
				return Result.ok("already there");
			}
			if (route != null && !route.isEmpty()) {
				path = route;
				index = 0;
				lastProgressTick = ticks;
			} else {
				plan(p);
			}
		}

		if (pathfinder != null) {
			releaseMovement(o);
			Pathfinder.State s = pathfinder.step(NODES_PER_TICK);
			if (s == Pathfinder.State.SEARCHING) {
				return null;
			}
			if (s == Pathfinder.State.FAILED) {
				return Result.fail("no path found to " + name().substring(5) + " after " + pathfinder.expanded()
					+ " nodes (unloaded chunks, obstacles needing block breaking/placing, or unsafe terrain)");
			}
			partial = s == Pathfinder.State.PARTIAL;
			path = pathfinder.path();
			pathfinder = null;
			lastProgressTick = ticks;
			lastDistance = Double.MAX_VALUE;
			if (path.isEmpty()) {
				return arrived(p) ? Result.ok("arrived") : Result.fail("no progress possible toward " + goal.toShortString());
			}
		}

		if (index >= path.size()) {
			releaseMovement(o);
			if (arrived(p)) {
				return Result.ok("arrived at " + p.blockPosition().toShortString());
			}
			if (!p.onGround() && !p.isInWater()) {
				return null; // let the landing settle before deciding
			}
			return replan(p, partial ? "partial path finished" : "ended off target");
		}

		// Without x-ray the plan crosses ground we had not seen yet; replan once it turns out different.
		if (Vision.noXray() && ticks % 5 == 0) {
			for (int i = index; i < Math.min(path.size(), index + 10); i++) {
				Pathfinder.Node n = path.get(i);
				double h = Pathfinder.standHeight(p.level(), Vision::known, n.pos());
				if (Double.isNaN(h) || Math.abs(h - n.standY()) > 0.01) {
					if (++revealReplans > MAX_REVEAL_REPLANS) {
						return Result.fail("gave up: the route kept turning out blocked as new terrain came into view");
					}
					plan(p);
					return null;
				}
			}
		}

		Pathfinder.Node node = path.get(index);
		Vec3 pos = p.position();
		// Skip ahead if we are already at a later node (overshoot / shortcut).
		for (int look = Math.min(path.size() - 1, index + 2); look >= index; look--) {
			if (reached(p, path.get(look), look == path.size() - 1)) {
				index = look + 1;
				lastProgressTick = ticks;
				lastDistance = Double.MAX_VALUE;
				return null;
			}
		}

		// Also count a node as passed once we are beyond it along the next segment (overshoot, wide turns).
		if (index + 1 < path.size()) {
			Pathfinder.Node next = path.get(index + 1);
			double sx = next.pos().getX() - node.pos().getX(), sz = next.pos().getZ() - node.pos().getZ();
			double rx = pos.x - (node.pos().getX() + 0.5), rz = pos.z - (node.pos().getZ() + 0.5);
			double len = Math.sqrt(sx * sx + sz * sz);
			if (len > 1e-6 && (rx * sx + rz * sz) / len > 0.05 && Math.abs(rx * sz - rz * sx) / len < 0.6
				&& Math.abs(pos.y - node.standY()) < 0.75) {
				index++;
				lastProgressTick = ticks;
				lastDistance = Double.MAX_VALUE;
				return null;
			}
		}

		// Cut corners like a player: head straight for the farthest upcoming node on the same level whose
		// straight line is fully walkable, instead of zig-zagging through every block center.
		if (p.onGround() && Math.abs(node.standY() - pos.y) < 0.1) {
			int aim = index;
			for (int j = index + 1; j < Math.min(path.size(), index + 6); j++) {
				Pathfinder.Node n = path.get(j);
				if (n.water() || Math.abs(n.standY() - node.standY()) > 0.01 || !straightWalkable(p, n)) {
					break;
				}
				aim = j;
			}
			if (aim != index) {
				index = aim;
				node = path.get(index);
				lastDistance = Double.MAX_VALUE;
			}
		}

		double dx = node.pos().getX() + 0.5 - pos.x;
		double dz = node.pos().getZ() + 0.5 - pos.z;
		double hd = Math.sqrt(dx * dx + dz * dz);
		double dy = node.standY() - pos.y;

		if (dy < -4.5 && p.onGround()) {
			return replan(p, "fell off path");
		}
		if (hd < lastDistance - 0.05) {
			lastDistance = hd;
			lastProgressTick = ticks;
		} else if (ticks - lastProgressTick > 60) {
			return replan(p, "stuck");
		}

		// Pure pursuit: steer at a point a little way along the path, never at the node itself. It cannot flip
		// behind us like a nearby node can, and turning starts before corners the way a player's would.
		Vec3 carrot = pursuitPoint(pos, p.isSprinting() ? 2.0 : 1.3);
		if (!bodyClear(p, carrot)) {
			// Cutting the corner would push us into a block side; walk to the node center first.
			carrot = new Vec3(node.pos().getX() + 0.5, 0, node.pos().getZ() + 0.5);
		}
		double cx = carrot.x - pos.x, cz = carrot.z - pos.z;
		float yaw = cx * cx + cz * cz > 0.04 ? (float) (Mth.atan2(cz, cx) * Mth.RAD_TO_DEG) - 90.0F : p.getYRot();
		Rotation.setTarget(yaw, gazePitch(p));
		float yawErr = Math.abs(Rotation.yawError(p, yaw));

		boolean last = index == path.size() - 1;
		boolean forward = yawErr < 70.0F && !(last && hd < 0.15);
		boolean jump = false;
		if (p.isInWater()) {
			jump = dy > -0.4;
		} else if (p.onGround()) {
			jump = (dy > 0.6 && hd < 1.6) || (p.horizontalCollision && forward && dy > 0.3);
		}
		boolean canSprint = sprint && !last && yawErr < 20.0F && !p.isInWater() && p.getFoodData().getFoodLevel() > 6
			&& index < path.size() - 2 && !dropAhead(p);
		// Crouching is how a player stops at a ledge; vanilla then refuses to step off the edge.
		boolean guard = forward && !jump && p.onGround() && !p.isInWater() && unsafeAhead(p);
		if (guard) {
			canSprint = false;
		}

		Input.set(o.keyUp, forward);
		Input.set(o.keyJump, jump);
		Input.set(o.keySprint, canSprint);
		Input.set(o.keyShift, guard);
		return null;
	}

	/** Whether the player's body (0.6 wide) can move in a straight line to {@code to} without hitting anything taller than a step. */
	private static boolean bodyClear(LocalPlayer p, Vec3 to) {
		Vec3 from = p.position();
		double dx = to.x - from.x, dz = to.z - from.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1e-3) {
			return true;
		}
		double px = -dz / len * 0.31, pz = dx / len * 0.31;
		int feetY = Mth.floor(from.y + 0.01);
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (double t = 0.15; t <= len; t += 0.15) {
			for (int side = -1; side <= 1; side++) {
				double x = from.x + dx * t / len + px * side, z = from.z + dz * t / len + pz * side;
				for (int y = feetY; y <= feetY + 1; y++) {
					q.set(Mth.floor(x), y, Mth.floor(z));
					VoxelShape shape = p.level().getBlockState(q).getCollisionShape(p.level(), q);
					if (!shape.isEmpty() && y + shape.max(Direction.Axis.Y) > from.y + 0.6) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** Point at horizontal distance {@code lookahead} along the polyline player -> remaining nodes. */
	private Vec3 pursuitPoint(Vec3 from, double lookahead) {
		Vec3 prev = new Vec3(from.x, 0, from.z);
		double travelled = 0.0;
		Vec3 point = prev;
		for (int i = index; i < path.size(); i++) {
			Pathfinder.Node n = path.get(i);
			point = new Vec3(n.pos().getX() + 0.5, 0, n.pos().getZ() + 0.5);
			double seg = point.distanceTo(prev);
			if (travelled + seg >= lookahead && seg > 1e-6) {
				return prev.lerp(point, (lookahead - travelled) / seg);
			}
			travelled += seg;
			prev = point;
		}
		return point;
	}

	/**
	 * Pitch of a walking player's gaze: a point on the route a few blocks ahead, around knee height. Gives a slight
	 * downward look on flat ground that rises on climbs, dips before drops and settles on the destination. The point
	 * is low-pass filtered so node switches do not make the head twitch.
	 */
	private float gazePitch(LocalPlayer p) {
		Vec3 v = p.getDeltaMovement();
		double speed = Math.sqrt(v.x * v.x + v.z * v.z); // blocks per tick
		double ahead = Mth.clamp(3.5 + speed * 12.0, 3.5, 6.0);
		Vec3 prev = p.position();
		Vec3 target = null;
		double travelled = 0.0;
		boolean full = false;
		for (int i = index; i < path.size(); i++) {
			Pathfinder.Node n = path.get(i);
			Vec3 point = new Vec3(n.pos().getX() + 0.5, n.standY(), n.pos().getZ() + 0.5);
			double seg = Math.sqrt(Mth.square(point.x - prev.x) + Mth.square(point.z - prev.z));
			if (travelled + seg >= ahead && seg > 1e-6) {
				target = prev.lerp(point, (ahead - travelled) / seg);
				full = true;
				break;
			}
			travelled += seg;
			prev = point;
			target = point;
		}
		if (target == null) {
			return p.getXRot();
		}
		if (!full) {
			// The path ends sooner than we look: keep looking ahead past the destination, not down at our feet.
			double hx = target.x - p.getX(), hz = target.z - p.getZ();
			double h = Math.sqrt(hx * hx + hz * hz);
			if (h < 0.3) {
				return p.getXRot();
			}
			target = target.add(hx / h * (ahead - h), 0, hz / h * (ahead - h));
		}
		target = target.add(0, 0.7, 0);
		gaze = gaze == null ? target : gaze.lerp(target, 0.2);
		float pitch = Rotation.anglesTo(p.getEyePosition(), gaze)[1];
		return Mth.clamp(pitch, -35.0F, 40.0F);
	}

	/** Whether the player's whole body width can walk in a straight line to the node without leaving its level. */
	private static boolean straightWalkable(LocalPlayer p, Pathfinder.Node target) {
		Vec3 from = p.position();
		double tx = target.pos().getX() + 0.5, tz = target.pos().getZ() + 0.5;
		double dx = tx - from.x, dz = tz - from.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1e-3) {
			return true;
		}
		double px = -dz / len * 0.32, pz = dx / len * 0.32;
		for (double t = 0; t <= len; t += 0.2) {
			double x = from.x + dx * t / len, z = from.z + dz * t / len;
			for (int side = -1; side <= 1; side++) {
				BlockPos column = BlockPos.containing(x + px * side, target.standY() + 0.01, z + pz * side);
				double h = Pathfinder.standHeight(p.level(), Vision::known, column);
				if (Double.isNaN(h) || Math.abs(h - target.standY()) > 0.01) {
					return false;
				}
			}
		}
		return true;
	}

	private boolean dropAhead(LocalPlayer p) {
		double y = p.getY();
		for (int i = index; i < Math.min(path.size(), index + 3); i++) {
			if (path.get(i).standY() < y - 0.6) {
				return true;
			}
			y = path.get(i).standY();
		}
		return false;
	}

	/** True if momentum is about to carry us over a drop that is not part of the planned path. */
	private boolean unsafeAhead(LocalPlayer p) {
		Vec3 v = p.getDeltaMovement();
		float yawRad = p.getYRot() * Mth.DEG_TO_RAD;
		Vec3 facing = new Vec3(-Mth.sin(yawRad), 0, Mth.cos(yawRad));
		for (double t : new double[]{0.4, 0.8}) {
			Vec3 ahead = p.position().add(v.x * 4 * t + facing.x * t, 0, v.z * 4 * t + facing.z * t);
			BlockPos column = BlockPos.containing(ahead.x, p.getY() + 0.01, ahead.z);
			if (onPath(column)) {
				continue;
			}
			boolean floor = false;
			for (int k = 0; k <= Pathfinder.MAX_FALL + 1 && !floor; k++) {
				BlockPos q = column.below(k);
				floor = !p.level().getBlockState(q).getCollisionShape(p.level(), q).isEmpty() || !p.level().getFluidState(q).isEmpty();
			}
			if (!floor) {
				return true;
			}
		}
		return false;
	}

	private boolean onPath(BlockPos column) {
		for (int i = Math.max(0, index - 1); i < Math.min(path.size(), index + 3); i++) {
			BlockPos n = path.get(i).pos();
			if (n.getX() == column.getX() && n.getZ() == column.getZ()) {
				return true;
			}
		}
		return false;
	}

	private boolean reached(LocalPlayer p, Pathfinder.Node node, boolean last) {
		Vec3 pos = p.position();
		// Cut corners like a player: head straight for the farthest upcoming node on the same level whose
		// straight line is fully walkable, instead of zig-zagging through every block center.
		if (p.onGround() && Math.abs(node.standY() - pos.y) < 0.1) {
			int aim = index;
			for (int j = index + 1; j < Math.min(path.size(), index + 6); j++) {
				Pathfinder.Node n = path.get(j);
				if (n.water() || Math.abs(n.standY() - node.standY()) > 0.01 || !straightWalkable(p, n)) {
					break;
				}
				aim = j;
			}
			if (aim != index) {
				index = aim;
				node = path.get(index);
				lastDistance = Double.MAX_VALUE;
			}
		}

		double dx = node.pos().getX() + 0.5 - pos.x;
		double dz = node.pos().getZ() + 0.5 - pos.z;
		double tol = last ? 0.3 : 0.45;
		if (dx * dx + dz * dz > tol * tol) {
			return false;
		}
		double dy = pos.y - node.standY();
		return dy > -0.6 && dy < 0.8 && (p.onGround() || p.isInWater() || !last);
	}

	private @Nullable Result replan(LocalPlayer p, String why) {
		if (arrived(p)) {
			return Result.ok("arrived at " + p.blockPosition().toShortString());
		}
		if (++replans > MAX_REPLANS) {
			return Result.fail("gave up after " + MAX_REPLANS + " replans (last reason: " + why + ") at " + p.blockPosition().toShortString());
		}
		plan(p);
		return null;
	}

	private static void releaseMovement(Options o) {
		Input.set(o.keyShift, false);
		Input.set(o.keyUp, false);
		Input.set(o.keyJump, false);
		Input.set(o.keySprint, false);
	}
}
