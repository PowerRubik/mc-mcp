package dev.bik.mcmcp.agent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** Runs tasks one after another, stopping at the first failure. Reports the last task's result. */
public final class SequenceTask extends Task {
	private final List<Task> tasks;
	private int index;

	public SequenceTask(Task... tasks) {
		this.tasks = List.of(tasks);
	}

	@Override
	public String name() {
		return tasks.get(tasks.size() - 1).name();
	}

	@Override
	public String progress() {
		return tasks.get(Math.min(index, tasks.size() - 1)).progress();
	}

	@Override
	protected @Nullable Result tick(Minecraft mc, LocalPlayer player) {
		Task current = tasks.get(index);
		Result r = current.step(mc, player);
		if (r == null) {
			return null;
		}
		current.onEnd();
		Input.releaseAll();
		if (!r.ok() || ++index >= tasks.size()) {
			return r;
		}
		return null;
	}

	@Override
	protected void onEnd() {
		if (index < tasks.size()) {
			tasks.get(index).onEnd();
		}
	}
}
