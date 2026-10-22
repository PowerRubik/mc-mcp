package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.agent.Aim;
import dev.bik.mcmcp.agent.Combat;
import dev.bik.mcmcp.agent.ElytraPilot;
import dev.bik.mcmcp.agent.Input;
import dev.bik.mcmcp.agent.Rotation;
import dev.bik.mcmcp.agent.Task;
import dev.bik.mcmcp.agent.Vision;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Fights one target with vanilla inputs. With an elytra, rockets and a spear it does dive runs: climb above the
 * target, hold use to charge the spear, dive at the predicted position (kinetic damage scales with closing speed),
 * pull up, rocket away, repeat. Otherwise it melees (spacing, strafing, crits). Aim quality is set by {@link Combat.Skill}.
 */
public final class FightTask extends Task {
	private enum Air { TAKEOFF, CLIMB, RUN, RECOVER, LAND }

	private static final double PERCH_HEIGHT = 16;
	private static final double PERCH_DISTANCE = 24;

	private final @Nullable Integer targetId;
	private final @Nullable String targetName;
	private final Combat.Skill skill;
	private final boolean air;
	private final int maxTicks;
	private final ElytraPilot pilot = new ElytraPilot();
	private final RandomSource random = RandomSource.create();
	private final Deque<Vec3> history = new ArrayDeque<>();

	private @Nullable Entity target;
	private Vec3 lastSeen;
	private int lastSeenTick;
	private Air phase = Air.TAKEOFF;
	private int phaseStart;
	private float lastTargetHealth = -1;
	private float lastOwnHealth = -1;
	private double dealt;
	private double taken;
	private int hits;
	private int runs;
	private int nextSwingTick;
	private int strafeDir = 1;
	private int strafeUntil;
	private int orbitSign = 1;
	private Vec3 aimNoise = Vec3.ZERO;
	private int noiseUntil;
	private double closestThisRun = Double.MAX_VALUE;
	private @Nullable String outcome;

	public FightTask(@Nullable Integer targetId, @Nullable String targetName, Combat.Skill skill, boolean air, int maxTicks) {
		this.targetId = targetId;
		this.targetName = targetName;
		this.skill = skill;
		this.air = air;
		this.maxTicks = maxTicks;
	}

	@Override
	public String name() {
		return "fight " + (targetName != null ? targetName : "entity " + targetId);
	}

	@Override
	public String progress() {
		return name() + " [" + (air ? "elytra " + phase.name().toLowerCase(Locale.ROOT) : "melee") + "] " + summary();
	}

	private String summary() {
		return String.format(Locale.ROOT, "%d hits, %.1f damage dealt, %.1f taken%s", hits, dealt, taken, air ? ", " + runs + " dive runs" : "");
	}

	private void enter(Air next) {
		phase = next;
		phaseStart = ticks;
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		if (ticks == 0) {
			Rotation.setCombatSpeed(skill.turnSpeed(), skill.ease());
		}
		if (target == null || target.isRemoved()) {
			target = Combat.findTarget(p, targetId, targetName);
		}

		// Bookkeeping: damage dealt and taken.
		float own = p.getHealth();
		if (lastOwnHealth >= 0 && own < lastOwnHealth) {
			taken += lastOwnHealth - own;
		}
		lastOwnHealth = own;
		if (target instanceof LivingEntity le) {
			float h = le.getHealth();
			if (lastTargetHealth >= 0 && h < lastTargetHealth) {
				dealt += lastTargetHealth - h;
				hits++;
				if (phase == Air.RUN) {
					enter(Air.RECOVER);
				}
			}
			lastTargetHealth = h;
		}

		if (outcome == null) {
			if (target == null || !target.isAlive()) {
				outcome = target == null && lastSeen == null ? "target not found" : "target defeated";
			} else if (ticks >= maxTicks) {
				outcome = "time limit reached";
			}
		}
		if (outcome != null) {
			// Never end mid-air: glide down and land first.
			if (p.isFallFlying() && !p.onGround()) {
				releaseAll(mc);
				landTick(p);
				return ticks - phaseStart > 600 ? done() : null;
			}
			return done();
		}

		// What can we see? In no-x-ray mode we only chase where we last saw them.
		boolean visible = !Vision.noXray() || Combat.inSight(p, target);
		if (visible) {
			lastSeen = target.position();
			lastSeenTick = ticks;
			history.addLast(target.position());
			while (history.size() > 20) {
				history.removeFirst();
			}
		}
		if (lastSeen == null) {
			return Result.fail("target is not in sight");
		}

		if (air && (Combat.wearingElytra(p))) {
			airTick(mc, p, visible);
		} else {
			meleeTick(mc, p, visible);
		}
		return null;
	}

	private Result done() {
		String s = outcome + ": " + summary();
		return outcome.equals("target not found") ? Result.fail(s) : Result.ok(s);
	}

	// ------------------------------------------------------------------ aiming

	/** Where the bot thinks the target is: delayed by its reaction time, extrapolated, plus aim error. */
	private Vec3 perceivedTarget(double leadTicks) {
		Vec3 now = history.isEmpty() ? lastSeen : history.peekLast();
		Vec3 seen = now;
		int i = 0;
		for (var it = history.descendingIterator(); it.hasNext(); i++) {
			Vec3 v = it.next();
			if (i == skill.reactionTicks()) {
				seen = v;
				break;
			}
		}
		Vec3 vel = target.getKnownMovement();
		Vec3 predicted = seen.add(vel.scale(leadTicks + skill.reactionTicks()));
		if (ticks >= noiseUntil) {
			double r = Math.tan(Math.toRadians(skill.aimErrorDeg()));
			aimNoise = new Vec3(random.nextGaussian(), random.nextGaussian() * 0.5, random.nextGaussian()).scale(r);
			noiseUntil = ticks + 8 + random.nextInt(10);
		}
		return predicted.add(0, target.getBbHeight() * 0.6, 0).add(aimNoise.scale(Math.max(1.0, predicted.distanceTo(lastSeen))));
	}

	// ------------------------------------------------------------------ elytra + spear

	private void airTick(Minecraft mc, LocalPlayer p, boolean visible) {
		Options o = mc.options;
		Vec3 pos = p.position();
		Vec3 tgt = lastSeen;
		double horiz = Math.sqrt(Mth.square(tgt.x - pos.x) + Mth.square(tgt.z - pos.z));
		double speed = ElytraPilot.speed(p);
		int rocketSlot = Combat.hotbar(p, Combat::isRocket);
		int spearSlot = Combat.hotbar(p, Combat::isSpear);

		if (phase != Air.TAKEOFF && !p.isFallFlying()) {
			if (p.onGround() || p.isInWater()) {
				enter(Air.TAKEOFF);
			}
		}

		switch (phase) {
			case TAKEOFF -> {
				Input.set(o.keyUse, false);
				if (rocketSlot >= 0 && !Combat.select(mc, p, rocketSlot)) {
					return; // hold the rocket before leaving the ground
				}
				Rotation.setTarget(ElytraPilot.yawTo(p, tgt) + 180.0F * orbitSign * 0.5F, -55.0F);
				if (pilot.takeoff(mc, p)) {
					pilot.boost(mc, p, ticks, false);
					enter(Air.CLIMB);
				}
			}
			case CLIMB -> {
				Input.set(o.keyUse, false);
				// Circle out to a perch above and away from the target, rocketing whenever slow or low.
				Vec3 away = new Vec3(pos.x - tgt.x, 0, pos.z - tgt.z);
				away = away.lengthSqr() < 1 ? new Vec3(1, 0, 0) : away.normalize();
				Vec3 side = new Vec3(-away.z, 0, away.x).scale(orbitSign);
				Vec3 perch = tgt.add(away.scale(PERCH_DISTANCE)).add(side.scale(10)).add(0, PERCH_HEIGHT, 0);
				ElytraPilot.steer(p, perch, -50.0F, 25.0F);
				boolean low = pos.y < tgt.y + PERCH_HEIGHT - 3 || ElytraPilot.heightAboveGround(p) < 8;
				if ((speed < 1.1 || low) && pilot.sinceRocket(ticks) > 25 && rocketSlot >= 0) {
					pilot.boost(mc, p, ticks, false);
				} else if (pilot.busySwitching()) {
					pilot.boost(mc, p, ticks, false);
				}
				if (ElytraPilot.terrainAhead(p, 1.5)) {
					Rotation.setTarget(p.getYRot(), -45.0F);
					pilot.boost(mc, p, ticks, false);
				}
				boolean highEnough = pos.y >= tgt.y + PERCH_HEIGHT - 4;
				if (visible && highEnough && horiz > 14 && horiz < 45 && spearSlot >= 0 && ticks - phaseStart > 20) {
					closestThisRun = Double.MAX_VALUE;
					runs++;
					enter(Air.RUN);
				}
				if (ticks - phaseStart > 400) {
					orbitSign = -orbitSign; // stuck circling: try the other way round
					phaseStart = ticks;
				}
			}
			case RUN -> {
				// Spear out, use held (charging), nose on the predicted impact point.
				if (spearSlot < 0) {
					enter(Air.CLIMB);
					return;
				}
				if (!Combat.select(mc, p, spearSlot)) {
					Input.set(o.keyUse, false);
					return;
				}
				if (!p.isUsingItem() && ticks - phaseStart < 3) {
					Input.click(o.keyUse);
				}
				Input.set(o.keyUse, true);
				double dist = p.getEyePosition().distanceTo(target.getBoundingBox().getCenter());
				double timeToImpact = dist / Math.max(speed, 0.5);
				// Aim a little high so the pass goes over their head instead of into the ground beside them.
				Vec3 aim = perceivedTarget(timeToImpact).add(0, Math.min(1.2, dist * 0.04), 0);
				Rotation.lookAt(p.getEyePosition(), aim);
				closestThisRun = Math.min(closestThisRun, dist);
				boolean passed = closestThisRun < 8 && dist > closestThisRun + 3;
				boolean groundRisk = ElytraPilot.heightAboveGround(p) < 2.5 && dist > 5;
				if (passed || groundRisk || ticks - phaseStart > 160) {
					enter(Air.RECOVER);
				}
			}
			case RECOVER -> {
				Input.set(o.keyUse, false);
				Rotation.setTarget(p.getYRot(), -35.0F);
				if (pilot.sinceRocket(ticks) > 15 || pilot.busySwitching()) {
					pilot.boost(mc, p, ticks, false);
				}
				if (ticks - phaseStart > 25 && (ElytraPilot.heightAboveGround(p) > 6 || ticks - phaseStart > 60)) {
					orbitSign = random.nextBoolean() ? 1 : -1;
					enter(Air.CLIMB);
				}
			}
			case LAND -> landTick(p);
		}
	}

	private void landTick(LocalPlayer p) {
		double h = ElytraPilot.heightAboveGround(p);
		// Shallow glide down, then flare just above the ground to bleed speed.
		Rotation.setTarget(p.getYRot(), h > 6 ? 12.0F : -12.0F);
	}

	// ------------------------------------------------------------------ ground melee

	private void meleeTick(Minecraft mc, LocalPlayer p, boolean visible) {
		Options o = mc.options;
		int weapon = Combat.hotbar(p, Combat::isMelee);
		boolean spearJab = false;
		if (weapon < 0) {
			weapon = Combat.hotbar(p, Combat::isSpear);
			spearJab = weapon >= 0;
		}
		if (weapon >= 0 && !Combat.select(mc, p, weapon)) {
			return;
		}
		Vec3 pos = p.position();
		double dist = Math.sqrt(Mth.square(lastSeen.x - pos.x) + Mth.square(lastSeen.z - pos.z));
		Rotation.lookAt(p.getEyePosition(), perceivedTarget(1));

		double reachMin = spearJab ? 2.2 : 0;
		double reachMax = spearJab ? 4.3 : p.entityInteractionRange() - 0.2;
		double ideal = spearJab ? 3.4 : 2.6;
		boolean forward = dist > ideal + 0.4;
		boolean back = dist < Math.max(reachMin, ideal - 1.0);
		Input.set(o.keyUp, forward);
		Input.set(o.keyDown, back);
		Input.set(o.keySprint, forward && dist > ideal + 1.5);

		if (skill.strafe() && dist < 6 && visible) {
			if (ticks >= strafeUntil) {
				strafeDir = random.nextBoolean() ? 1 : -1;
				strafeUntil = ticks + 10 + random.nextInt(20);
			}
			Input.set(o.keyLeft, strafeDir > 0);
			Input.set(o.keyRight, strafeDir < 0);
		}
		boolean ready = p.getAttackStrengthScale(0.5F) >= (spearJab ? 1.0F : 0.95F);
		Input.set(o.keyJump, (skill.crits() && !spearJab && dist < 3.5 && p.onGround() && p.getAttackStrengthScale(0.5F) > 0.7F)
			|| p.horizontalCollision && forward);

		boolean onTarget = spearJab
			? angleTo(p, target.getBoundingBox().getCenter()) < 6.0 && dist > reachMin && dist < reachMax
			: Aim.crosshairOn(target);
		boolean critWindow = !skill.crits() || spearJab || !p.onGround() && p.getDeltaMovement().y < 0 || dist > 3.2 || p.horizontalCollision;
		if (visible && onTarget && ready && critWindow && ticks >= nextSwingTick) {
			Input.click(o.keyAttack);
			nextSwingTick = ticks + 2 + (skill.extraAttackDelay() > 0 ? random.nextInt(skill.extraAttackDelay() + 1) : 0);
		}
	}

	private static double angleTo(LocalPlayer p, Vec3 point) {
		Vec3 to = point.subtract(p.getEyePosition()).normalize();
		return Math.toDegrees(Math.acos(Mth.clamp(to.dot(p.getViewVector(1.0F)), -1.0, 1.0)));
	}

	private static void releaseAll(Minecraft mc) {
		Input.releaseAll();
	}
}
