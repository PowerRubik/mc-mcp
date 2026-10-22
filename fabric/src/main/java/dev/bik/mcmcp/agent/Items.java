package dev.bik.mcmcp.agent;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public final class Items {
	private Items() {
	}

	public static String normalizeId(String id) {
		String s = id.trim().toLowerCase();
		return s.contains(":") ? s : "minecraft:" + s;
	}

	public static String id(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	public static boolean matches(ItemStack stack, String normalizedId) {
		return !stack.isEmpty() && id(stack).equals(normalizedId);
	}

	/** Inventory index (0-35) holding the item, hotbar first, or -1. */
	public static int find(Player player, String normalizedId) {
		Inventory inv = player.getInventory();
		int selected = inv.getSelectedSlot();
		if (matches(inv.getItem(selected), normalizedId)) {
			return selected;
		}
		for (int i = 0; i < 36; i++) {
			if (matches(inv.getItem(i), normalizedId)) {
				return i;
			}
		}
		return -1;
	}

	/** Hotbar slot with the fastest tool for the block, or -1 if nothing beats the current item. */
	public static int bestHotbarTool(Player player, BlockState state) {
		Inventory inv = player.getInventory();
		int best = -1;
		float bestScore = score(inv.getItem(inv.getSelectedSlot()), state);
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			float s = score(inv.getItem(i), state);
			if (s > bestScore + 1e-3) {
				bestScore = s;
				best = i;
			}
		}
		return best;
	}

	private static float score(ItemStack stack, BlockState state) {
		float speed = stack.getDestroySpeed(state);
		if (state.requiresCorrectToolForDrops() && stack.isCorrectToolForDrops(state)) {
			speed += 1000;
		}
		return speed;
	}

	/** Lore lines (where server GUIs usually put prices, descriptions and click hints), or "" if none. */
	public static String lore(ItemStack stack) {
		net.minecraft.world.item.component.ItemLore lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
		if (lore == null || lore.lines().isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(" | lore: ");
		for (int i = 0; i < lore.lines().size(); i++) {
			if (i > 0) {
				sb.append(" / ");
			}
			sb.append(lore.lines().get(i).getString());
		}
		return sb.toString();
	}

	public static String describe(ItemStack stack) {
		if (stack.isEmpty()) {
			return "empty";
		}
		String s = stack.getCount() + "x " + id(stack);
		String name = stack.getHoverName().getString();
		if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
			s += " \"" + name + "\"";
		}
		if (stack.isDamageableItem()) {
			s += " (durability " + (stack.getMaxDamage() - stack.getDamageValue()) + "/" + stack.getMaxDamage() + ")";
		}
		return s;
	}
}
