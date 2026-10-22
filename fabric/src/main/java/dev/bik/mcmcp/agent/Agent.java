package dev.bik.mcmcp.agent;

import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/** Owns the single running task. All state is touched on the client thread only. */
public final class Agent {
	private static @Nullable Task current;
	private static @Nullable CompletableFuture<Task.Result> currentFuture;
	private static volatile String status = "idle";
	private static volatile String lastResult = "none";

	private Agent() {
	}

	/** Starts a task, cancelling whatever is running. Safe to call from any thread. */
	public static CompletableFuture<Task.Result> submit(Task task) {
		CompletableFuture<Task.Result> future = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> {
			finish(Task.Result.fail("cancelled: superseded by " + task.name()));
			current = task;
			currentFuture = future;
			status = task.progress();
		});
		return future;
	}

	public static CompletableFuture<Task.Result> stop() {
		CompletableFuture<Task.Result> future = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> {
			boolean had = current != null;
			finish(Task.Result.fail("cancelled: stop requested"));
			Input.releaseAll();
			Rotation.clear();
			future.complete(Task.Result.ok(had ? "stopped" : "nothing was running"));
		});
		return future;
	}

	public static @Nullable CompletableFuture<Task.Result> currentFuture() {
		return currentFuture;
	}

	public static String status() {
		return status;
	}

	public static String lastResult() {
		return lastResult;
	}

	public static void tick(Minecraft mc) {
		TickStats.onTick();
		Task task = current;
		if (task == null) {
			return;
		}
		// Agent input counts as player activity, same as a key press; otherwise vanilla throttles an "AFK" client
		// to 30/10 fps, which makes ticks (and movement packets) bunch up and look like lag to the server.
		mc.getFramerateLimitTracker().onInputReceived();
		if (mc.player == null || mc.level == null) {
			finish(Task.Result.fail("not in a world"));
			return;
		}
		if (mc.player.isDeadOrDying() && !task.runsWhileDead()) {
			finish(Task.Result.fail("you died at " + mc.player.blockPosition().toShortString() + "; use respawn"));
			return;
		}
		Task.Result r;
		try {
			r = task.step(mc, mc.player);
		} catch (RuntimeException e) {
			r = Task.Result.fail("task crashed: " + e);
		}
		if (r != null) {
			finish(r);
		} else {
			status = task.progress();
		}
	}

	private static void finish(Task.Result result) {
		Task task = current;
		CompletableFuture<Task.Result> future = currentFuture;
		current = null;
		currentFuture = null;
		if (task == null) {
			return;
		}
		try {
			task.onEnd();
		} finally {
			Input.releaseAll();
			Rotation.clear();
			status = "idle";
			lastResult = task.name() + ": " + (result.ok() ? "OK " : "FAILED ") + result.message();
			if (future != null) {
				future.complete(result);
			}
		}
	}
}
