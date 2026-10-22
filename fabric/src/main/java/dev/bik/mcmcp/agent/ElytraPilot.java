package dev.bik.mcmcp.agent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra flying through vanilla inputs only: double-jump to deploy, camera to steer, rockets via the use key with
 * the rocket selected (switching hotbar slots like a player). One instance per flight; call from a task's tick.
 */
public final class ElytraPilot {
	private int takeoffStep;
	private int lastRocketTick = -1000;
	private int rocketStep;
	private int restoreSlot = -1;

	/** Blocks per tick. */
	public static double speed(LocalPlayer p) {
		return p.getDeltaMovement().length();
	}

	/** Distance to the first solid block straight below (up to 64). */
	public static double heightAboveGround(LocalPlayer p) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int d = 0; d < 64; d++) {
			m.set(p.getBlockX(), p.getBlockY() - d, p.getBlockZ());
			if (!p.level().getBlockState(m).getCollisionShape(p.level(), m).isEmpty() || !p.level().getFluidState(m).isEmpty()) {
				return p.getY() - (m.getY() + 1);
			}
		}
		return 64;
	}

	/** Would we fly into terrain within {@code seconds} at the current velocity? */
	public static boolean terrainAhead(LocalPlayer p, double seconds) {
		Vec3 v = p.getDeltaMovement();
		double len = Math.max(v.length() * 20 * seconds, 6.0);
		Vec3 dir = v.lengthSqr() < 1e-4 ? p.getViewVector(1.0F) : v.normalize();
		Vec3 from = p.position().add(0, 0.4, 0);
		HitResult hit = p.level().clip(new ClipContext(from, from.add(dir.scale(len)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
		return hit.getType() != HitResult.Type.MISS;
	}

	/**
	 * Deploy the elytra: jump, then press jump again while airborne. Returns true once gliding. Call every tick
	 * until it does; it keeps its own step counter.
	 */
	public boolean takeoff(Minecraft mc, LocalPlayer p) {
		if (p.isFallFlying()) {
			Input.set(mc.options.keyJump, false);
			return true;
		}
		switch (takeoffStep) {
			case 0 -> {
				Input.set(mc.options.keyJump, true);
				if (!p.onGround()) {
					takeoffStep = 1;
				}
			}
			case 1 -> {
				Input.set(mc.options.keyJump, false); // release so the next press is a fresh one
				takeoffStep = 2;
			}
			case 2, 3 -> takeoffStep++;
			default -> {
				Input.set(mc.options.keyJump, true);
				if (p.onGround()) {
					takeoffStep = 0;
				} else {
					takeoffStep = 1; // retry on the next tick if deploying did not take
				}
			}
		}
		return false;
	}

	public boolean canBoost(LocalPlayer p) {
		return p.isFallFlying() && (Combat.hotbar(p, Combat::isRocket) >= 0 || Combat.isRocket(p.getOffhandItem()));
	}

	/** Ticks since the last rocket was fired (a rocket boosts for roughly 1-1.5 s). */
	public int sinceRocket(int now) {
		return now - lastRocketTick;
	}

	/**
	 * Fire a rocket: select it (unless it is in the offhand and the main hand would not block it), press use, then
	 * switch back. Returns true on the tick the use key is pressed. Multi-tick; call every tick while wanted.
	 */
	public boolean boost(Minecraft mc, LocalPlayer p, int now, boolean switchBack) {
		if (rocketStep == 2) {
			if (restoreSlot < 0 || Combat.select(mc, p, restoreSlot)) {
				rocketStep = 0;
				restoreSlot = -1;
			}
			return false;
		}
		if (!p.isFallFlying() || p.isUsingItem()) {
			return false;
		}
		if (Combat.isRocket(p.getOffhandItem()) && (p.getMainHandItem().isEmpty() || Combat.isMelee(p.getMainHandItem()))) {
			Input.click(mc.options.keyUse);
			lastRocketTick = now;
			return true;
		}
		int slot = Combat.hotbar(p, Combat::isRocket);
		if (slot < 0) {
			return false;
		}
		if (rocketStep == 0) {
			restoreSlot = switchBack ? p.getInventory().getSelectedSlot() : -1;
			rocketStep = 1;
		}
		if (Combat.select(mc, p, slot)) {
			Input.click(mc.options.keyUse);
			lastRocketTick = now;
			rocketStep = 2;
			return true;
		}
		return false;
	}

	public boolean busySwitching() {
		return rocketStep != 0;
	}

	/** Pitch (degrees, positive = down) that points at {@code target} from the eye. */
	public static float pitchTo(LocalPlayer p, Vec3 target) {
		return Rotation.anglesTo(p.getEyePosition(), target)[1];
	}

	public static float yawTo(LocalPlayer p, Vec3 target) {
		return Rotation.anglesTo(p.getEyePosition(), target)[0];
	}

	/** Steer toward a point with the pitch kept inside [minPitch, maxPitch]. */
	public static void steer(LocalPlayer p, Vec3 target, float minPitch, float maxPitch) {
		Rotation.setTarget(yawTo(p, target), Mth.clamp(pitchTo(p, target), minPitch, maxPitch));
	}
}
