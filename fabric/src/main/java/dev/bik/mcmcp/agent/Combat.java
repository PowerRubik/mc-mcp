package dev.bik.mcmcp.agent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.function.Predicate;

/** Shared combat helpers: difficulty profiles, target lookup, sight checks and hotbar switching. */
public final class Combat {
	private Combat() {
	}

	/**
	 * How good the bot is. Reaction delay (it aims where the target was this many ticks ago, partially predicted),
	 * aim error, turn speed, and random extra delay before swinging when an attack is ready.
	 */
	public record Skill(String name, int reactionTicks, double aimErrorDeg, double turnSpeed, double ease, int extraAttackDelay,
		boolean strafe, boolean crits) {
		public static Skill of(String name) {
			return switch (name.toLowerCase(Locale.ROOT)) {
				case "easy" -> new Skill("easy", 6, 6.0, 360, 10, 8, false, false);
				case "normal" -> new Skill("normal", 4, 3.0, 540, 14, 4, true, false);
				case "max" -> new Skill("max", 0, 0.3, 1440, 30, 0, true, true);
				default -> new Skill("hard", 2, 1.2, 900, 20, 1, true, true);
			};
		}
	}

	public static @Nullable Entity findTarget(LocalPlayer p, @Nullable Integer id, @Nullable String name) {
		if (id != null) {
			return p.level().getEntity(id);
		}
		if (name != null) {
			for (Player other : Minecraft.getInstance().level.players()) {
				if (other != p && other.getName().getString().equalsIgnoreCase(name)) {
					return other;
				}
			}
		}
		return null;
	}

	/** Line of sight from the eye to the entity's eyes, center or feet. */
	public static boolean inSight(LocalPlayer p, Entity e) {
		Vec3 eye = p.getEyePosition();
		for (Vec3 target : new Vec3[]{e.getEyePosition(), e.getBoundingBox().getCenter(), e.position().add(0, 0.1, 0)}) {
			HitResult hit = p.level().clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
			if (hit.getType() == HitResult.Type.MISS) {
				return true;
			}
		}
		return false;
	}

	public static boolean isSpear(ItemStack s) {
		return s.has(DataComponents.KINETIC_WEAPON);
	}

	public static boolean isRocket(ItemStack s) {
		return s.is(net.minecraft.world.item.Items.FIREWORK_ROCKET);
	}

	public static boolean isMelee(ItemStack s) {
		return s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES);
	}

	/** Hotbar slot (0-8) whose stack matches, preferring the selected one, or -1. */
	public static int hotbar(Player p, Predicate<ItemStack> match) {
		Inventory inv = p.getInventory();
		if (match.test(inv.getItem(inv.getSelectedSlot()))) {
			return inv.getSelectedSlot();
		}
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			if (match.test(inv.getItem(i))) {
				return i;
			}
		}
		return -1;
	}

	/** Press the slot's number key if needed. Returns true once the slot is held (so it is safe to use it). */
	public static boolean select(Minecraft mc, LocalPlayer p, int slot) {
		if (p.getInventory().getSelectedSlot() == slot) {
			return true;
		}
		Input.click(mc.options.keyHotbarSlots[slot]);
		return false;
	}

	public static boolean wearingElytra(LocalPlayer p) {
		ItemStack chest = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
		return net.minecraft.world.entity.LivingEntity.canGlideUsing(chest, net.minecraft.world.entity.EquipmentSlot.CHEST);
	}
}
