package dev.bik.mcmcp.path;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Incremental A* over walkable positions. Only uses moves a player can do with plain keyboard input: walking
 * (including diagonals), jumping up to 1.2 blocks, dropping up to 3 blocks (more into water) and swimming.
 * The search is spread over several ticks via {@link #step(int)} so the client never stalls.
 */
public final class Pathfinder {
	public enum State { SEARCHING, FOUND, PARTIAL, FAILED }

	public record Node(BlockPos pos, double standY, boolean water) {
	}

	/** What the search is looking for. */
	public interface Goal {
		boolean test(BlockPos pos);

		/** Admissible-ish distance estimate; 0 turns the search into Dijkstra. */
		double heuristic(BlockPos pos);

		static Goal near(BlockPos target, double range, boolean anyY) {
			double r = Math.max(range, 0.0);
			return new Goal() {
				private double distSqr(BlockPos pos) {
					double dx = pos.getX() - target.getX(), dz = pos.getZ() - target.getZ();
					double dy = anyY ? 0.0 : pos.getY() - target.getY();
					return dx * dx + dy * dy + dz * dz;
				}

				@Override
				public boolean test(BlockPos pos) {
					return distSqr(pos) <= r * r;
				}

				@Override
				public double heuristic(BlockPos pos) {
					return Math.max(0.0, Math.sqrt(distSqr(pos)) - r);
				}
			};
		}
	}

	/**
	 * Which blocks the planner may use real data for. Unknown blocks are assumed to be open air over a flat floor
	 * (optimistic, at extra cost) so routes can lead into unexplored space and get replanned once it is seen.
	 */
	@FunctionalInterface
	public interface Knowledge {
		Knowledge ALL = pos -> true;

		boolean known(BlockPos pos);

		/** Optimistic: unknown is open air over a floor. Pessimistic: unknown is neither floor nor open space. */
		default boolean optimistic() {
			return true;
		}

		static Knowledge pessimistic(Knowledge k) {
			return new Knowledge() {
				@Override
				public boolean known(BlockPos pos) {
					return k.known(pos);
				}

				@Override
				public boolean optimistic() {
					return false;
				}
			};
		}
	}

	private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
	public static final int MAX_FALL = 3;
	private static final int MAX_FALL_INTO_WATER = 12;
	private static final double HEURISTIC_WEIGHT = 1.25;

	private final Level level;
	private final Goal goal;
	private final Knowledge knowledge;
	private final int maxNodes;
	private final PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
	private final Long2ObjectOpenHashMap<Entry> all = new Long2ObjectOpenHashMap<>();
	private final Entry startEntry;
	private Entry best;
	private @Nullable Entry found;
	private State state = State.SEARCHING;
	private int expanded;

	private static final class Entry {
		final BlockPos pos;
		final double standY;
		final boolean water;
		double g;
		double h;
		double f;
		@Nullable Entry parent;
		boolean closed;

		Entry(BlockPos pos, double standY, boolean water) {
			this.pos = pos;
			this.standY = standY;
			this.water = water;
		}
	}

	public Pathfinder(Level level, BlockPos start, double startY, Goal goal, Knowledge knowledge, int maxNodes) {
		this.level = level;
		this.goal = goal;
		this.knowledge = knowledge;
		this.maxNodes = maxNodes;
		double sy = standHeight(level, knowledge, start);
		startEntry = new Entry(start, Double.isNaN(sy) ? startY : sy, isWater(level, knowledge, start));
		startEntry.h = goal.heuristic(start);
		startEntry.f = startEntry.h * HEURISTIC_WEIGHT;
		best = startEntry;
		all.put(start.asLong(), startEntry);
		open.add(startEntry);
	}

	/** The block stood in has been seen, and so has what it rests on (unless it is itself solid, like carpet or a slab). */
	public static boolean seenGround(Level level, Knowledge k, BlockPos pos) {
		return k.known(pos) && (k.known(pos.below()) || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty());
	}

	public State state() {
		return state;
	}

	public int expanded() {
		return expanded;
	}

	public State step(int budget) {
		if (state != State.SEARCHING) {
			return state;
		}
		for (int i = 0; i < budget; i++) {
			Entry cur = open.poll();
			if (cur == null || expanded >= maxNodes) {
				state = best != startEntry && best.h < startEntry.h - 1.0 ? State.PARTIAL : State.FAILED;
				return state;
			}
			if (cur.closed) {
				continue;
			}
			cur.closed = true;
			expanded++;
			if (isGoal(cur.pos)) {
				found = cur;
				state = State.FOUND;
				return state;
			}
			if (cur.h < best.h) {
				best = cur;
			}
			expand(cur);
		}
		return state;
	}

	/** Path from (excluding) start to the goal, or to the closest reached node for PARTIAL results. */
	public List<Node> path() {
		Entry end = state == State.FOUND ? found : best;
		List<Node> nodes = new ArrayList<>();
		for (Entry e = end; e != null && e != startEntry; e = e.parent) {
			nodes.add(new Node(e.pos, e.standY, e.water));
		}
		Collections.reverse(nodes);
		return nodes;
	}

	public boolean isGoal(BlockPos pos) {
		return goal.test(pos);
	}

	private void expand(Entry cur) {
		BlockPos p = cur.pos;
		for (int[] d : DIRS) {
			boolean diagonal = d[0] != 0 && d[1] != 0;
			if (diagonal && !(clear(p.offset(d[0], 0, 0), cur) && clear(p.offset(0, 0, d[1]), cur))) {
				continue;
			}
			neighborColumn(cur, d[0], d[1], diagonal);
		}
		if (cur.water) {
			tryVertical(cur, p.above(), 1.5);
			if (isWater(level, knowledge, p.below())) {
				tryVertical(cur, p.below(), 1.5);
			}
		}
	}

	private void tryVertical(Entry cur, BlockPos to, double cost) {
		double sy = standHeight(level, knowledge, to);
		if (!Double.isNaN(sy)) {
			offer(cur, to, sy, cost);
		}
	}

	private void neighborColumn(Entry cur, int dx, int dz, boolean diagonal) {
		BlockPos p = cur.pos;
		double base = diagonal ? Math.sqrt(2) : 1.0;
		// Jump up one block.
		BlockPos up = p.offset(dx, 1, dz);
		double upY = knowledge.known(up.below()) ? standHeight(level, knowledge, up) : Double.NaN;
		if (!Double.isNaN(upY)) {
			double dh = upY - cur.standY;
			if (dh <= 1.2 && (dh <= 0.6 || headroomForJump(cur, dh)) && (!diagonal || dh <= 0.6)) {
				offer(cur, up, upY, base + (dh > 0.6 ? 0.6 : 0.0));
				return;
			}
		}
		// Same level, then progressively lower (walk off an edge).
		int maxFall = MAX_FALL_INTO_WATER;
		for (int dy = 0; dy >= -maxFall; dy--) {
			BlockPos c = p.offset(dx, dy, dz);
			double sy = standHeight(level, knowledge, c);
			if (!Double.isNaN(sy)) {
				double dh = sy - cur.standY;
				if (dh > 1.2 || (dh > 0.6 && !headroomForJump(cur, dh))) {
					return;
				}
				if (diagonal && Math.abs(dh) > 0.6) {
					return;
				}
				boolean landsInWater = isWater(level, knowledge, c);
				if (-dh > MAX_FALL + 0.6 && !landsInWater) {
					return;
				}
				double cost = base + (dh > 0.6 ? 0.6 : 0.0) + (dh < -0.6 ? -dh * 0.4 : 0.0);
				offer(cur, c, sy, cost);
				return;
			}
			// Keep scanning downward only through empty space.
			if (dy == 0 && !passable(level, knowledge, c.above())) {
				return;
			}
			if (!passable(level, knowledge, c) || diagonal) {
				return;
			}
		}
	}

	private boolean headroomForJump(Entry cur, double dh) {
		int top = (int) Math.ceil(cur.standY + 1.8 + dh) - 1;
		for (int y = cur.pos.getY() + 1; y <= top; y++) {
			if (!passable(level, knowledge, new BlockPos(cur.pos.getX(), y, cur.pos.getZ()))) {
				return false;
			}
		}
		return true;
	}

	/** Corner check for diagonals: the column next to us must be open at body height. */
	private boolean clear(BlockPos column, Entry cur) {
		int top = (int) Math.ceil(cur.standY + 1.8) - 1;
		for (int y = (int) Math.floor(cur.standY + 0.01); y <= top; y++) {
			if (!passable(level, knowledge, new BlockPos(column.getX(), y, column.getZ()))) {
				return false;
			}
		}
		return true;
	}

	private void offer(Entry from, BlockPos pos, double standY, double cost) {
		boolean water = isWater(level, knowledge, pos);
		if (water || from.water) {
			cost *= 2.5;
		}
		cost += cliffPenalty(pos);
		if (!knowledge.known(pos) || !knowledge.known(pos.below())) {
			cost = cost * 1.5 + 0.5; // unexplored: prefer routes over ground we have actually seen
		}
		double g = from.g + cost;
		long key = pos.asLong();
		Entry e = all.get(key);
		if (e == null) {
			e = new Entry(pos, standY, water);
			e.h = goal.heuristic(pos);
			all.put(key, e);
		} else if (e.closed || g >= e.g) {
			return;
		}
		e.g = g;
		e.f = g + e.h * HEURISTIC_WEIGHT;
		e.parent = from;
		open.add(e);
	}

	/** Extra cost for standing next to a drop deeper than a safe fall, so paths keep away from ledges. */
	private double cliffPenalty(BlockPos pos) {
		double penalty = 0.0;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = pos.relative(d);
			boolean deep = true;
			for (int k = 0; k <= MAX_FALL + 1 && deep; k++) {
				BlockPos q = n.below(k);
				deep = passable(level, knowledge, q) && !isWater(level, knowledge, q);
			}
			if (deep) {
				penalty += 0.75;
			}
		}
		return penalty;
	}

	private static boolean isWater(Level level, Knowledge k, BlockPos pos) {
		return k.known(pos) && level.getFluidState(pos).is(FluidTags.WATER);
	}

	private static boolean passable(Level level, Knowledge k, BlockPos pos) {
		if (!level.isLoaded(pos)) {
			return false;
		}
		if (!k.known(pos)) {
			return k.optimistic();
		}
		BlockState s = level.getBlockState(pos);
		return s.getCollisionShape(level, pos).isEmpty() && !dangerous(s);
	}

	/**
	 * Y the player's feet rest at when standing in block {@code pos}, or NaN if that is not a safe place to stand.
	 * Full-height floors are the block below; partial blocks (slabs, carpet, farmland...) are stood "inside".
	 */
	public static double standHeight(Level level, Knowledge k, BlockPos pos) {
		if (!level.isLoaded(pos) || !level.isLoaded(pos.below())) {
			return Double.NaN;
		}
		boolean feetKnown = k.known(pos);
		if (!feetKnown && !k.optimistic()) {
			return Double.NaN;
		}
		BlockState feet = feetKnown ? level.getBlockState(pos) : Blocks.AIR.defaultBlockState();
		if (dangerous(feet)) {
			return Double.NaN;
		}
		VoxelShape feetShape = feet.getCollisionShape(level, pos);
		double stand;
		if (feetShape.isEmpty()) {
			if (feet.getFluidState().is(FluidTags.WATER)) {
				stand = pos.getY();
			} else if (!k.known(pos.below())) {
				if (!k.optimistic()) {
					return Double.NaN;
				}
				stand = pos.getY(); // unseen floor: assume it is there
			} else {
				BlockPos belowPos = pos.below();
				BlockState below = level.getBlockState(belowPos);
				if (dangerous(below) || below.is(Blocks.MAGMA_BLOCK)) {
					return Double.NaN;
				}
				VoxelShape belowShape = below.getCollisionShape(level, belowPos);
				if (belowShape.isEmpty()) {
					return Double.NaN;
				}
				double top = belowShape.max(Direction.Axis.Y);
				if (top < 0.999 || top > 1.001) {
					return Double.NaN; // partial blocks are handled as feet blocks; fences/walls are too tall
				}
				stand = pos.getY();
			}
		} else {
			double top = feetShape.max(Direction.Axis.Y);
			if (top > 0.95 || feetShape.min(Direction.Axis.Y) > 0.01 || feet.is(Blocks.MAGMA_BLOCK)) {
				return Double.NaN;
			}
			stand = pos.getY() + top;
		}
		int top = (int) Math.ceil(stand + 1.8) - 1;
		for (int y = pos.getY() + 1; y <= top; y++) {
			if (!passable(level, k, new BlockPos(pos.getX(), y, pos.getZ()))) {
				return Double.NaN;
			}
		}
		return stand;
	}

	private static boolean dangerous(BlockState s) {
		return s.getFluidState().is(FluidTags.LAVA)
			|| s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)
			|| s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.WITHER_ROSE)
			|| s.is(Blocks.POWDER_SNOW) || s.is(Blocks.COBWEB) || s.is(Blocks.POINTED_DRIPSTONE)
			|| s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.END_PORTAL) || s.is(Blocks.END_GATEWAY);
	}
}
