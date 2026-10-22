package dev.bik.mcmcp.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.mojang.blaze3d.systems.GpuSurface;
import dev.bik.mcmcp.agent.Background;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import dev.bik.mcmcp.Config;
import dev.bik.mcmcp.agent.Input;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	/** Without this, alt-tabbing away opens the pause menu, which releases every key and stops the agent. */
	@Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
	private void mcmcp$keepRunningUnfocused(CallbackInfo ci) {
		if (Config.get().keepRunningUnfocused) {
			ci.cancel();
		}
	}

	/**
	 * Vanilla only continues mining while the cursor is grabbed. When the window is unfocused the agent's held
	 * attack key would otherwise be ignored; treat it as grabbed so mining behaves exactly as with a focused window.
	 */
	@ModifyExpressionValue(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
	private boolean mcmcp$agentAttackGrab(boolean grabbed) {
		return grabbed || Input.isHeld(Minecraft.getInstance().options.keyAttack);
	}

	@WrapWithCondition(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"))
	private boolean mcmcp$skipExtract(GameRenderer renderer, DeltaTracker deltaTracker, boolean advanceGameTime) {
		return !Background.active();
	}

	@WrapWithCondition(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
	private boolean mcmcp$skipRender(GameRenderer renderer, DeltaTracker deltaTracker, boolean advanceGameTime) {
		return !Background.active();
	}

	/**
	 * Without an acquired image nothing is blitted or presented. Presenting a vsync'd surface the compositor is not
	 * showing can block for up to a second (seen on Wayland), stalling ticks; skipping it keeps the loop on time.
	 */
	@WrapWithCondition(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;acquireNextTexture()V"))
	private boolean mcmcp$skipAcquire(GpuSurface surface) {
		return !Background.unfocused();
	}
}
