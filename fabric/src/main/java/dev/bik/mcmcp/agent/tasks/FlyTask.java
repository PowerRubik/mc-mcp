package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.agent.Combat;
import dev.bik.mcmcp.agent.ElytraPilot;
import dev.bik.mcmcp.agent.Input;
import dev.bik.mcmcp.agent.Rotation;
import dev.bik.mcmcp.agent.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/** Elytra travel: take off with a rocket, cruise high over terrain, glide down and flare to land near the target. */
public final class FlyTask extends Task {
	private enum Phase { TAKEOFF, CRUISE, APPROACH, FLARE }

	private final double x;
	private final double z;
	private final @Nullable Double y;
	private final ElytraPilot pilot = new ElytraPilot();
	private Phase phase = Phase.TAKEOFF;
	private int phaseStart;
	private int relaunches;

	public FlyTask(double x, @Nullable Double y, double z) {
		this.x = x + 0.5;
		this.y = y;
		this.z = z + 0.5;
	}

	@Override
	public String name() {
		return String.format(Locale.ROOT, "fly to %.0f, %s, %.0f", x - 0.5, y == null ? "~" : String.format(Locale.ROOT, "%.0f", y), z - 0.5);
	}

	@Override
	public String progress() {
		return name() + ": " + phase.name().toLowerCase(Locale.ROOT);
	}

	private void enter(Phase p) {
		phase = p;
		phaseStart = ticks;
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		if (!Combat.wearingElytra(p)) {
			return Result.fail("not wearing a usable elytra (equip one in the chest slot first)");
		}
		double groundY = p.level().getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
		double destY = y != null ? y : groundY;
		Vec3 pos = p.position();
		double horiz = Math.sqrt(Mth.square(x - pos.x) + Mth.square(z - pos.z));
		int rocketSlot = Combat.hotbar(p, Combat::isRocket);

		switch (phase) {
			case TAKEOFF -> {
				if (p.onGround() && horiz < 3) {
					return Result.ok("already there");
				}
				if (rocketSlot >= 0 && !Combat.select(mc, p, rocketSlot)) {
					return null;
				}
				Rotation.setTarget(ElytraPilot.yawTo(p, new Vec3(x, pos.y, z)), -50.0F);
				if (pilot.takeoff(mc, p)) {
					pilot.boost(mc, p, ticks, false);
					enter(Phase.CRUISE);
				} else if (ticks - phaseStart > 100) {
					return Result.fail("could not take off");
				}
			}
			case CRUISE -> {
				double cruiseY = Math.max(destY + 25, p.level().getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ()) + 20);
				ElytraPilot.steer(p, new Vec3(x, cruiseY, z), -40.0F, 20.0F);
				boolean low = pos.y < cruiseY - 5;
				if (pilot.busySwitching() || rocketSlot >= 0 && pilot.sinceRocket(ticks) > 25 && (ElytraPilot.speed(p) < 1.2 || low)) {
					pilot.boost(mc, p, ticks, false);
				}
				if (ElytraPilot.terrainAhead(p, 1.5)) {
					Rotation.setTarget(p.getYRot(), -45.0F);
					pilot.boost(mc, p, ticks, false);
				}
				if (horiz < Math.max(30, (pos.y - destY) * 1.8)) {
					enter(Phase.APPROACH);
				}
			}
			case APPROACH -> {
				ElytraPilot.steer(p, new Vec3(x, destY + 1, z), -10.0F, 35.0F);
				if (ElytraPilot.terrainAhead(p, 1.0) && horiz > 8) {
					Rotation.setTarget(p.getYRot(), -30.0F);
					pilot.boost(mc, p, ticks, false);
				}
				if (ElytraPilot.heightAboveGround(p) < 4 || horiz < 4) {
					enter(Phase.FLARE);
				}
			}
			case FLARE -> Rotation.setTarget(p.getYRot(), -15.0F);
		}

		if (phase != Phase.TAKEOFF && (p.onGround() || p.isInWater()) && ticks - phaseStart > 5) {
			Input.releaseAll();
			if (horiz < 8 || ++relaunches > 3) {
				return horiz < 8
					? Result.ok(String.format(Locale.ROOT, "landed at %d, %d, %d (%.1f blocks from target)", p.getBlockX(), p.getBlockY(), p.getBlockZ(), horiz))
					: Result.fail(String.format(Locale.ROOT, "kept landing short; now %.0f blocks away", horiz));
			}
			enter(Phase.TAKEOFF);
		}
		return ticks > 20 * 300 ? Result.fail("flight took too long") : null;
	}
}
