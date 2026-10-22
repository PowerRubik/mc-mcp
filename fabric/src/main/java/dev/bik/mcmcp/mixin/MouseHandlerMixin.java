package dev.bik.mcmcp.mixin;

import dev.bik.mcmcp.agent.Rotation;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	/** Runs once per rendered frame, the same cadence real mouse movement is applied at. */
	@Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
	private void mcmcp$applyAgentRotation(CallbackInfo ci) {
		Rotation.onFrame();
	}
}
