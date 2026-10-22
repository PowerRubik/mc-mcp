package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.agent.Rotation;
import dev.bik.mcmcp.agent.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class LookTask extends Task {
	private final @Nullable Vec3 point;
	private final float yaw;
	private final float pitch;

	public LookTask(@Nullable Vec3 point, float yaw, float pitch) {
		this.point = point;
		this.yaw = yaw;
		this.pitch = pitch;
	}

	@Override
	public String name() {
		return point != null ? "look at " + point : "look yaw=" + yaw + " pitch=" + pitch;
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		if (point != null) {
			Rotation.lookAt(p.getEyePosition(), point);
		} else if (ticks == 0) {
			Rotation.setTarget(yaw, pitch);
		}
		if (Rotation.aligned(0.5F)) {
			return Result.ok(String.format("facing yaw=%.1f pitch=%.1f", p.getYRot(), p.getXRot()));
		}
		return ticks > 100 ? Result.fail("rotation did not converge") : null;
	}
}
