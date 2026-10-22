package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.agent.Aim;
import dev.bik.mcmcp.agent.Input;
import dev.bik.mcmcp.agent.Rotation;
import dev.bik.mcmcp.agent.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/** Attacks or right-clicks an entity by tracking it with the crosshair and clicking once it is under it. */
public final class EntityTask extends Task {
	private final int entityId;
	private final boolean attack;
	private final int times;
	private int done;
	private int lastClick = -100;
	private int lastSeen;

	public EntityTask(int entityId, boolean attack, int times) {
		this.entityId = entityId;
		this.attack = attack;
		this.times = Math.max(1, times);
	}

	@Override
	public String name() {
		return (attack ? "attack" : "interact with") + " entity " + entityId;
	}

	@Override
	public String progress() {
		return name() + ": " + done + "/" + times + " clicks";
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		Entity e = p.level().getEntity(entityId);
		if (e == null || !e.isAlive()) {
			return done > 0 ? Result.ok("entity gone after " + done + " click(s)") : Result.fail("entity " + entityId + " not found");
		}
		double range = p.entityInteractionRange();
		if (p.getEyePosition().distanceToSqr(e.getBoundingBox().getCenter()) > (range + 1.0) * (range + 1.0)) {
			return Result.fail("entity is out of reach (" + String.format("%.1f", p.distanceTo(e)) + " blocks)");
		}
		Rotation.lookAt(p.getEyePosition(), Aim.entityPoint(p, e));
		if (!Aim.crosshairOn(e)) {
			if (ticks - lastSeen > 40) {
				return Result.fail("could not get the crosshair onto the entity (occluded?)");
			}
			return null;
		}
		lastSeen = ticks;
		if (ticks - lastClick < (attack ? 2 : 5)) {
			return null;
		}
		// Wait for a full swing like a player who cares about damage.
		if (attack && p.getAttackStrengthScale(0.5F) < 0.95F) {
			return null;
		}
		Input.click(attack ? mc.options.keyAttack : mc.options.keyUse);
		lastClick = ticks;
		if (++done >= times) {
			return Result.ok((attack ? "attacked " : "interacted with ") + e.getType().toShortString() + " x" + done);
		}
		return null;
	}
}
