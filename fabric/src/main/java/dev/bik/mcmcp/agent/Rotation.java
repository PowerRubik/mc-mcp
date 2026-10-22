package dev.bik.mcmcp.agent;

import com.mojang.blaze3d.Blaze3D;
import dev.bik.mcmcp.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Virtual mouse. Each frame the camera eases toward the target, but the step is converted into whole mouse "pixels"
 * and applied with exactly the math of {@code MouseHandler.turnPlayer}, so every rotation delta is a multiple of the
 * player's own sensitivity step, just like a physical mouse produces.
 */
public final class Rotation {
	private static float targetYaw;
	private static float targetPitch;
	private static boolean active;
	private static double lastTime = -1;
	private static double maxSpeedOverride = -1;
	private static double easeOverride = -1;

	private Rotation() {
	}

	public static void lookAt(Vec3 eye, Vec3 point) {
		float[] angles = anglesTo(eye, point);
		setTarget(angles[0], angles[1]);
	}

	public static void setTarget(float yaw, float pitch) {
		targetYaw = yaw;
		targetPitch = Mth.clamp(pitch, -90.0F, 90.0F);
		active = true;
	}

	public static void clear() {
		active = false;
		maxSpeedOverride = -1;
		easeOverride = -1;
	}

	/** Faster, snappier tracking for combat; reset by {@link #clear()}. */
	public static void setCombatSpeed(double maxDegPerSecond, double easeRate) {
		maxSpeedOverride = maxDegPerSecond;
		easeOverride = easeRate;
	}

	public static boolean isActive() {
		return active;
	}

	/** True once the camera is within one mouse step (plus tolerance) of the target. */
	public static boolean aligned(float toleranceDeg) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) {
			return false;
		}
		float tol = (float) Math.max(toleranceDeg, degreesPerPixel(p) * 1.01);
		return Math.abs(Mth.wrapDegrees(targetYaw - p.getYRot())) <= tol && Math.abs(targetPitch - p.getXRot()) <= tol;
	}

	public static float yawError(LocalPlayer p, float yaw) {
		return Mth.wrapDegrees(yaw - p.getYRot());
	}

	public static float[] anglesTo(Vec3 eye, Vec3 point) {
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		double hd = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
		float pitch = (float) -(Mth.atan2(dy, hd) * Mth.RAD_TO_DEG);
		return new float[]{Mth.wrapDegrees(yaw), pitch};
	}

	private static double sensitivityFactor(LocalPlayer p) {
		Minecraft mc = Minecraft.getInstance();
		double ss = mc.options.sensitivity().get() * 0.6F + 0.2F;
		double mod = ss * ss * ss;
		boolean scoping = mc.options.getCameraType().isFirstPerson() && p.isScoping();
		return scoping ? mod : mod * 8.0;
	}

	private static double degreesPerPixel(LocalPlayer p) {
		return sensitivityFactor(p) * 0.15;
	}

	/** Called at the start of MouseHandler#handleAccumulatedMovement, i.e. once per frame. */
	public static void onFrame() {
		double now = Blaze3D.getTime();
		double dt = lastTime < 0 ? 0.0 : Math.min(now - lastTime, 0.1);
		lastTime = now;

		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (!active || p == null || dt <= 0.0 || mc.gui.screen() != null) {
			return;
		}

		Config cfg = Config.get();
		double sens = sensitivityFactor(p);
		double degPerPx = sens * 0.15;
		double ease = 1.0 - Math.exp(-dt * (easeOverride > 0 ? easeOverride : 14.0));

		long px = pixelStep(Mth.wrapDegrees(targetYaw - p.getYRot()), ease, dt, degPerPx, cfg);
		long py = pixelStep(targetPitch - p.getXRot(), ease, dt, degPerPx, cfg);
		if (px != 0 || py != 0) {
			p.turn(px * sens, py * sens);
		}
	}

	private static long pixelStep(double error, double ease, double dt, double degPerPx, Config cfg) {
		double abs = Math.abs(error);
		if (abs < degPerPx * 0.5) {
			return 0;
		}
		double step = abs * ease;
		step = Math.max(step, Math.min(abs, cfg.minTurnSpeed * dt));
		step = Math.min(step, (maxSpeedOverride > 0 ? maxSpeedOverride : cfg.maxTurnSpeed) * dt);
		long pixels = Math.round(step / degPerPx);
		if (pixels == 0) {
			pixels = 1;
		}
		return (long) Math.signum(error) * pixels;
	}
}
