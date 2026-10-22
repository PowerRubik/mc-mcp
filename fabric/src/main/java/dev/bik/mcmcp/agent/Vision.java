package dev.bik.mcmcp.agent;

import dev.bik.mcmcp.Config;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Memory of every block the player could actually have seen. Each tick a slice of a jittered ray grid is cast through
 * the camera's field of view; rays mark the voxels they pass and stop at the first opaque block (which is marked too).
 * Light level is ignored, as with fullbright. With no-x-ray mode on, world queries and planning only use seen blocks.
 */
public final class Vision {
	private static final int COLS = 48;
	private static final int ROWS = 27;
	private static final int SLICES = 3;
	private static final int MAX_DISTANCE = 96;
	private static final int MAX_SECTIONS = 60_000; // 512 bytes each, ~30 MB worst case

	private static final Long2ObjectLinkedOpenHashMap<long[]> SEEN = new Long2ObjectLinkedOpenHashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();
	private static @Nullable ClientLevel trackedLevel;
	private static long seenCount;
	private static int slice;

	private Vision() {
	}

	public static boolean noXray() {
		return Config.get().noXray;
	}

	/** True if the agent may use this block's real state: always when x-ray is allowed, else only if seen. */
	public static boolean known(BlockPos pos) {
		return !noXray() || seen(pos.getX(), pos.getY(), pos.getZ());
	}

	public static long seenCount() {
		return seenCount;
	}

	private static boolean seen(int x, int y, int z) {
		long[] bits = SEEN.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
		if (bits == null) {
			return false;
		}
		int i = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
		return (bits[i >> 6] & (1L << (i & 63))) != 0;
	}

	private static void mark(int x, int y, int z) {
		long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
		long[] bits = SEEN.get(key);
		if (bits == null) {
			bits = new long[64];
			SEEN.put(key, bits);
			if (SEEN.size() > MAX_SECTIONS) {
				SEEN.removeFirst(); // forget the oldest-discovered area
			}
		}
		int i = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
		long bit = 1L << (i & 63);
		if ((bits[i >> 6] & bit) == 0) {
			bits[i >> 6] |= bit;
			seenCount++;
		}
	}

	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (level != trackedLevel) {
			SEEN.clear();
			seenCount = 0;
			trackedLevel = level;
		}
		if (p == null || level == null || !noXray()) {
			return;
		}

		// What you stand in and on is always known.
		BlockPos feet = p.blockPosition();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = -1; dy <= 2; dy++) {
					mark(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
				}
			}
		}

		Vec3 eye = p.getEyePosition();
		Vec3 forward = p.getViewVector(1.0F);
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		if (right.lengthSqr() < 1e-6) {
			float yaw = p.getYRot() * Mth.DEG_TO_RAD;
			right = new Vec3(-Mth.cos(yaw), 0, -Mth.sin(yaw));
		}
		right = right.normalize();
		Vec3 up = right.cross(forward).normalize();
		double tanY = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
		int w = mc.getWindow().getScreenWidth(), h = mc.getWindow().getScreenHeight();
		double tanX = tanY * (w > 0 && h > 0 ? (double) w / h : 16.0 / 9.0);
		int distance = Math.min(MAX_DISTANCE, mc.options.getEffectiveRenderDistance() * 16);
		if (p.isEyeInFluid(FluidTags.WATER)) {
			distance = Math.min(distance, 32);
		}

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int r = slice; r < ROWS; r += SLICES) {
			for (int c = 0; c < COLS; c++) {
				double u = ((c + RANDOM.nextDouble()) / COLS * 2.0 - 1.0) * tanX;
				double v = ((r + RANDOM.nextDouble()) / ROWS * 2.0 - 1.0) * tanY;
				Vec3 dir = forward.add(right.scale(u)).add(up.scale(v)).normalize();
				cast(level, eye, dir, distance, cursor);
			}
		}
		slice = (slice + 1) % SLICES;
	}

	/** Amanatides-Woo voxel walk from {@code origin} along {@code dir}. */
	private static void cast(ClientLevel level, Vec3 origin, Vec3 dir, int maxDistance, BlockPos.MutableBlockPos cursor) {
		int x = Mth.floor(origin.x), y = Mth.floor(origin.y), z = Mth.floor(origin.z);
		int stepX = dir.x > 0 ? 1 : -1, stepY = dir.y > 0 ? 1 : -1, stepZ = dir.z > 0 ? 1 : -1;
		double tDeltaX = dir.x == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.x);
		double tDeltaY = dir.y == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.y);
		double tDeltaZ = dir.z == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.z);
		double tMaxX = dir.x == 0 ? Double.MAX_VALUE : (stepX > 0 ? x + 1 - origin.x : origin.x - x) * tDeltaX;
		double tMaxY = dir.y == 0 ? Double.MAX_VALUE : (stepY > 0 ? y + 1 - origin.y : origin.y - y) * tDeltaY;
		double tMaxZ = dir.z == 0 ? Double.MAX_VALUE : (stepZ > 0 ? z + 1 - origin.z : origin.z - z) * tDeltaZ;
		double t = 0;
		while (t <= maxDistance) {
			cursor.set(x, y, z);
			if (level.isOutsideBuildHeight(y) || !level.isLoaded(cursor)) {
				return;
			}
			mark(x, y, z);
			if (blocksSight(level.getBlockState(cursor), origin, dir, maxDistance, cursor)) {
				return;
			}
			if (tMaxX < tMaxY && tMaxX < tMaxZ) {
				x += stepX;
				t = tMaxX;
				tMaxX += tDeltaX;
			} else if (tMaxY < tMaxZ) {
				y += stepY;
				t = tMaxY;
				tMaxY += tDeltaY;
			} else {
				z += stepZ;
				t = tMaxZ;
				tMaxZ += tDeltaZ;
			}
		}
	}

	/**
	 * Full opaque cubes and lava stop sight. Partial opaque blocks (slabs, stairs, carpet, snow layers...) only stop the
	 * ray if it actually passes through their occlusion shape. Glass, leaves, plants and water are seen past.
	 */
	private static boolean blocksSight(BlockState s, Vec3 origin, Vec3 dir, int maxDistance, BlockPos pos) {
		if (s.isSolidRender() || s.getFluidState().is(FluidTags.LAVA)) {
			return true;
		}
		if (!s.canOcclude()) {
			return false;
		}
		VoxelShape shape = s.getOcclusionShape();
		return !shape.isEmpty() && shape.clip(origin, origin.add(dir.scale(maxDistance)), pos) != null;
	}
}
