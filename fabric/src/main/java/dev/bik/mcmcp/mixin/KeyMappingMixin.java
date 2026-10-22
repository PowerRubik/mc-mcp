package dev.bik.mcmcp.mixin;

import dev.bik.mcmcp.agent.Input;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Agent-held keys are reported as physically held. Everything downstream (KeyboardInput, sprint logic,
 * continueAttack, item use) is untouched vanilla code, so the resulting packets are identical to a real key press.
 */
@Mixin(KeyMapping.class)
public class KeyMappingMixin {
	@Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
	private void mcmcp$agentHeld(CallbackInfoReturnable<Boolean> cir) {
		if (Input.isHeld((KeyMapping) (Object) this)) {
			cir.setReturnValue(true);
		}
	}
}
