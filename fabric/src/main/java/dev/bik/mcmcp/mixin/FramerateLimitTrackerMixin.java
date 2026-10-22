package dev.bik.mcmcp.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.bik.mcmcp.Config;
import dev.bik.mcmcp.agent.Background;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
public class FramerateLimitTrackerMixin {
	/**
	 * Vanilla drops an idle or unfocused client to 30/10 fps; ticks run inside the frame loop, so below 20 fps several
	 * ticks (and movement packets) are sent back-to-back. With rendering skipped, a steady cap is nearly free.
	 */
	@Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
	private void mcmcp$backgroundLimit(CallbackInfoReturnable<Integer> cir) {
		if (Background.unfocused()) {
			cir.setReturnValue(Math.max(Config.get().unfocusedFps, 30));
		}
	}
}
