package dev.bik.mcmcp.agent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** Picks look targets that vanilla's own crosshair raycast will actually resolve to the intended block face. */
public final class Aim {
	private static final double[] OFFSETS = {0.0, -0.3, 0.3};

	private Aim() {
	}

	public record Point(Vec3 point, Direction face) {
	}

	/**
	 * Finds a point on {@code pos} (optionally restricted to one face) that is reachable and not occluded,
	 * preferring the point requiring the smallest camera movement.
	 */
	public static @Nullable Point blockPoint(LocalPlayer player, BlockPos pos, @Nullable Direction onlyFace) {
		Level level = player.level();
		BlockState state = level.getBlockState(pos);
		VoxelShape shape = state.getShape(level, pos);
		AABB box = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		double reach = player.blockInteractionRange();

		Point best = null;
		double bestScore = Double.MAX_VALUE;
		for (Direction face : Direction.values()) {
			if (onlyFace != null && face != onlyFace) {
				continue;
			}
			for (double a : OFFSETS) {
				for (double b : OFFSETS) {
					Vec3 p = facePoint(box, face, a, b);
					Vec3 toPoint = p.subtract(eye);
					double dist = toPoint.length();
					if (dist > reach || dist < 1e-4) {
						continue;
					}
					Vec3 normal = face.getUnitVec3();
					if (toPoint.dot(normal) >= 0) {
						continue; // face points away from us
					}
					Vec3 end = eye.add(toPoint.scale((dist + 0.25) / dist));
					BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
					if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos) || hit.getDirection() != face) {
						continue;
					}
					if (hit.getLocation().distanceTo(eye) > reach) {
						continue;
					}
					double angle = Math.acos(Math.max(-1.0, Math.min(1.0, toPoint.normalize().dot(look))));
					double score = angle + (a == 0.0 && b == 0.0 ? 0.0 : 0.05);
					if (score < bestScore) {
						bestScore = score;
						best = new Point(p, face);
					}
				}
			}
		}
		return best;
	}

	private static Vec3 facePoint(AABB box, Direction face, double a, double b) {
		double cx = (box.minX + box.maxX) / 2, cy = (box.minY + box.maxY) / 2, cz = (box.minZ + box.maxZ) / 2;
		double sx = box.getXsize(), sy = box.getYsize(), sz = box.getZsize();
		return switch (face) {
			case DOWN -> new Vec3(cx + a * sx, box.minY, cz + b * sz);
			case UP -> new Vec3(cx + a * sx, box.maxY, cz + b * sz);
			case NORTH -> new Vec3(cx + a * sx, cy + b * sy, box.minZ);
			case SOUTH -> new Vec3(cx + a * sx, cy + b * sy, box.maxZ);
			case WEST -> new Vec3(box.minX, cy + b * sy, cz + a * sz);
			case EAST -> new Vec3(box.maxX, cy + b * sy, cz + a * sz);
		};
	}

	/**
	 * Describes what the crosshair would hit first when aiming from the eye at the center of {@code pos}, if it is not
	 * {@code pos} itself, e.g. " (view blocked by minecraft:cave_vines at 1, 2, 3)". Empty if nothing is in the way.
	 */
	public static String obstruction(LocalPlayer player, BlockPos pos) {
		Vec3 eye = player.getEyePosition();
		BlockHitResult hit = player.level().clip(new ClipContext(eye, Vec3.atCenterOf(pos), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(pos)) {
			return "";
		}
		BlockPos b = hit.getBlockPos();
		return " (view blocked by " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(b).getBlock())
			+ " at " + b.toShortString() + ")";
	}

	/** True when vanilla's current crosshair target is the given block (and face, if given). */
	public static boolean crosshairOn(BlockPos pos, @Nullable Direction face) {
		HitResult hr = Minecraft.getInstance().hitResult;
		return hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK
			&& bhr.getBlockPos().equals(pos) && (face == null || bhr.getDirection() == face);
	}

	public static boolean crosshairOn(Entity entity) {
		HitResult hr = Minecraft.getInstance().hitResult;
		return hr instanceof EntityHitResult ehr && ehr.getEntity() == entity;
	}

	/** Point on the entity's hitbox closest to the eye line, biased toward the center. */
	public static Vec3 entityPoint(LocalPlayer player, Entity entity) {
		AABB box = entity.getBoundingBox();
		Vec3 center = box.getCenter();
		Vec3 eye = player.getEyePosition();
		double y = Math.max(box.minY + box.getYsize() * 0.25, Math.min(box.maxY - box.getYsize() * 0.15, eye.y));
		return new Vec3(center.x, y, center.z);
	}
}
