package dev.bik.mcmcp.mixin;

import dev.bik.mcmcp.Config;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightmapRenderStateExtractor.class)
public class LightmapRenderStateExtractorMixin {
	/** Fullbright: render the lightmap as if night vision were active. Purely visual, nothing reaches the server. */
	@Inject(method = "extract", at = @At("TAIL"))
	private void mcmcp$fullbright(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
		if (Config.get().fullbright && renderState.needsUpdate) {
			renderState.nightVisionEffectIntensity = 1.0F;
		}
	}
}
