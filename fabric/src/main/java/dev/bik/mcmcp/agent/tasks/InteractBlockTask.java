package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.agent.Aim;
import dev.bik.mcmcp.agent.Input;
import dev.bik.mcmcp.agent.Rotation;
import dev.bik.mcmcp.agent.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** Right-clicks a block (open chests, press buttons, use doors...). */
public final class InteractBlockTask extends Task {
	private final BlockPos pos;
	private boolean clicked;
	private int clickTick;
	private int aimStart;
	private int attempts;

	public InteractBlockTask(BlockPos pos) {
		this.pos = pos;
	}

	@Override
	public String name() {
		return "interact " + pos.toShortString();
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		if (ticks == 0 && !aim(p)) {
			return Result.fail("block not visible or out of reach");
		}
		if (!clicked) {
			if (Aim.crosshairOn(pos, null) && Rotation.aligned(4.0F)) {
				Input.click(mc.options.keyUse);
				clicked = true;
				clickTick = ticks;
			} else if (ticks - aimStart > 30) {
				if (++attempts > 2 || !aim(p)) {
					return Result.fail("could not get the crosshair onto the block");
				}
			}
			return null;
		}
		if (ticks - clickTick >= 4) {
			var screen = mc.gui.screen();
			return Result.ok("right-clicked " + MineTask.blockId(p.level().getBlockState(pos))
				+ (screen != null ? "; screen open: " + screen.getTitle().getString() : ""));
		}
		return null;
	}

	private boolean aim(LocalPlayer p) {
		Aim.Point point = Aim.blockPoint(p, pos, null);
		if (point == null) {
			boolean touching = p.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)) < 1.2;
			if (!touching && !Aim.crosshairOn(pos, null)) {
				return false;
			}
			Rotation.lookAt(p.getEyePosition(), net.minecraft.world.phys.Vec3.atCenterOf(pos));
		} else {
			Rotation.lookAt(p.getEyePosition(), point.point());
		}
		aimStart = ticks;
		return true;
	}
}
