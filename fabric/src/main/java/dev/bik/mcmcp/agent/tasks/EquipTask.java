package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.agent.Input;
import dev.bik.mcmcp.agent.Items;
import dev.bik.mcmcp.agent.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * Puts an item in the main hand like a player would: press its hotbar number, or open the inventory, hover the
 * item and press a hotbar number key (a SWAP click), close the inventory, then select that hotbar slot.
 */
public final class EquipTask extends Task {
	private enum Phase { FIND, OPENING, SWAP, CLOSING, SELECT, SETTLE }

	private final String itemId;
	private final int forcedHotbar;
	private Phase phase = Phase.FIND;
	private int invIndex = -1;
	private int hotbarTarget = -1;
	private int phaseStart;
	private int openedAt = -1;
	private boolean selectPressed;

	/** @param itemId item to equip, or null to just select {@code hotbar}. */
	public EquipTask(@Nullable String itemId, int hotbar) {
		this.itemId = itemId == null ? null : Items.normalizeId(itemId);
		this.forcedHotbar = hotbar;
	}

	@Override
	public String name() {
		return itemId != null ? "equip " + itemId : "select hotbar " + (forcedHotbar + 1);
	}

	@Override
	public String progress() {
		return name() + ": " + phase.name().toLowerCase();
	}

	private void enter(Phase p) {
		phase = p;
		phaseStart = ticks;
	}

	private int inPhase() {
		return ticks - phaseStart;
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		Inventory inv = p.getInventory();
		switch (phase) {
			case FIND -> {
				if (itemId == null) {
					hotbarTarget = forcedHotbar;
					enter(Phase.SELECT);
					return null;
				}
				invIndex = Items.find(p, itemId);
				if (invIndex < 0) {
					return Result.fail("no " + itemId + " in inventory");
				}
				if (Inventory.isHotbarSlot(invIndex)) {
					hotbarTarget = invIndex;
					enter(Phase.SELECT);
					return null;
				}
				if (p.hasInfiniteMaterials()) {
					return Result.fail(itemId + " is not on the hotbar; in creative mode move it there first");
				}
				hotbarTarget = forcedHotbar >= 0 ? forcedHotbar : pickHotbarSlot(inv);
				if (mc.gui.screen() == null) {
					Input.releaseAll();
					Input.click(mc.options.keyInventory);
				}
				enter(Phase.OPENING);
				return null;
			}
			case OPENING -> {
				if (mc.gui.screen() instanceof InventoryScreen) {
					if (openedAt < 0) {
						openedAt = ticks;
					}
					if (ticks - openedAt >= 4) { // a human needs a moment to find the item
						enter(Phase.SWAP);
					}
				} else if (inPhase() > 20) {
					return Result.fail("inventory screen did not open (another screen is open?)");
				}
				return null;
			}
			case SWAP -> {
				if (!(mc.gui.screen() instanceof InventoryScreen screen)) {
					return Result.fail("inventory closed unexpectedly");
				}
				Slot slot = null;
				for (Slot s : screen.getMenu().slots) {
					if (s.container == inv && s.getContainerSlot() == invIndex) {
						slot = s;
						break;
					}
				}
				if (slot == null) {
					return Result.fail("could not find inventory slot " + invIndex);
				}
				mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot.index, hotbarTarget, ContainerInput.SWAP, p);
				enter(Phase.CLOSING);
				return null;
			}
			case CLOSING -> {
				if (inPhase() == 3 && mc.gui.screen() != null) {
					mc.gui.screen().onClose();
				}
				if (inPhase() > 3 && mc.gui.screen() == null) {
					enter(Phase.SELECT);
				}
				return null;
			}
			case SELECT -> {
				if (inv.getSelectedSlot() != hotbarTarget) {
					if (!selectPressed) {
						Input.click(mc.options.keyHotbarSlots[hotbarTarget]);
						selectPressed = true;
						phaseStart = ticks;
					} else if (inPhase() > 10) {
						return Result.fail("hotbar selection did not apply");
					}
					return null;
				}
				enter(Phase.SETTLE);
				return null;
			}
			case SETTLE -> {
				// Give vanilla a tick to send the carried-item packet before anything uses the item.
				if (inPhase() >= 1) {
					return Result.ok("holding " + Items.describe(inv.getSelectedItem()) + " in hotbar slot " + (hotbarTarget + 1));
				}
				return null;
			}
		}
		return null;
	}

	private static int pickHotbarSlot(Inventory inv) {
		if (inv.getItem(inv.getSelectedSlot()).isEmpty()) {
			return inv.getSelectedSlot();
		}
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			if (inv.getItem(i).isEmpty()) {
				return i;
			}
		}
		return inv.getSelectedSlot();
	}
}
