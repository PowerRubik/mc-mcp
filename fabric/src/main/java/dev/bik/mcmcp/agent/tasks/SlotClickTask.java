package dev.bik.mcmcp.agent.tasks;

import dev.bik.mcmcp.Config;
import dev.bik.mcmcp.agent.Items;
import dev.bik.mcmcp.agent.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.jspecify.annotations.Nullable;

/**
 * Clicks a slot of the open container screen, sending the same packet AbstractContainerScreen#slotClicked does.
 * A short delay precedes each click so the cadence matches a person moving the cursor.
 */
public final class SlotClickTask extends Task {
	private final int slot;
	private final int button;
	private final ContainerInput mode;

	public SlotClickTask(int slot, int button, ContainerInput mode) {
		this.slot = slot;
		this.button = button;
		this.mode = mode;
	}

	@Override
	public String name() {
		return "click slot " + slot + " (" + mode.name().toLowerCase() + ", button " + button + ")";
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer p) {
		if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
			return Result.fail("no container screen is open");
		}
		AbstractContainerMenu menu = screen.getMenu();
		if (slot != -999 && (slot < 0 || slot >= menu.slots.size())) {
			return Result.fail("slot " + slot + " out of range (0-" + (menu.slots.size() - 1) + ")");
		}
		int delay = Config.get().clickIntervalTicks;
		if (ticks == delay) {
			mc.gameMode.handleContainerInput(menu.containerId, slot, button, mode, p);
		}
		if (ticks >= delay + 1) {
			return Result.ok("clicked; cursor holds " + Items.describe(menu.getCarried())
				+ (slot >= 0 ? ", slot now " + Items.describe(menu.slots.get(slot).getItem()) : ""));
		}
		return null;
	}
}
