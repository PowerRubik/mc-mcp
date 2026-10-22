package dev.bik.mcmcp.agent;

import dev.bik.mcmcp.mixin.KeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.HashSet;
import java.util.Set;

/**
 * Virtual keyboard. Held keys are only reported while no screen is open, mirroring vanilla which releases every key
 * when a screen opens. Must only be touched from the client thread.
 */
public final class Input {
	private static final Set<KeyMapping> HELD = new HashSet<>();
	/** Toggle-sneak: stays on across tasks until switched off (like vanilla's toggle sneak option). */
	private static boolean sneakToggled;

	private Input() {
	}

	public static boolean isHeld(KeyMapping key) {
		if (HELD.isEmpty() && !sneakToggled) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() != null) {
			return false;
		}
		return HELD.contains(key) || sneakToggled && key == mc.options.keyShift;
	}

	public static void setSneakToggle(boolean on) {
		sneakToggled = on;
	}

	public static boolean sneakToggled() {
		return sneakToggled;
	}

	public static void set(KeyMapping key, boolean down) {
		if (down) {
			HELD.add(key);
		} else {
			HELD.remove(key);
		}
	}

	public static void releaseAll() {
		HELD.clear();
	}

	/** A single press, consumed by vanilla's handleKeybinds on the next tick (only runs while no screen is open). */
	public static void click(KeyMapping key) {
		KeyMappingAccessor acc = (KeyMappingAccessor) key;
		acc.mcmcp$setClickCount(acc.mcmcp$getClickCount() + 1);
	}

	public static String describe() {
		StringBuilder sb = new StringBuilder();
		for (KeyMapping k : HELD) {
			if (!sb.isEmpty()) {
				sb.append(", ");
			}
			sb.append(k.getName());
		}
		return sb.toString();
	}
}
