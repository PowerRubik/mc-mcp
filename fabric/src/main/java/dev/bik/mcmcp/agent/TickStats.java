package dev.bik.mcmcp.agent;

import java.util.Locale;

/** Measures the wall-clock spacing of client ticks, which is what the server sees as packet timing. */
public final class TickStats {
	private static final int SIZE = 100;
	private static final long[] GAPS = new long[SIZE];
	private static int count;
	private static long last;

	private TickStats() {
	}

	public static synchronized void onTick() {
		long now = System.nanoTime();
		if (last != 0) {
			long gap = now - last;
			GAPS[count++ % SIZE] = gap;
			if (gap > 250_000_000L) {
				dev.bik.mcmcp.McMcpClient.LOGGER.warn("Client tick stalled for {} ms (task: {})", gap / 1_000_000, Agent.status());
			}
		}
		last = now;
	}

	public static synchronized String describe() {
		int n = Math.min(count, SIZE);
		if (n == 0) {
			return "no ticks yet";
		}
		long sum = 0, max = 0;
		int bunched = 0;
		for (int i = 0; i < n; i++) {
			sum += GAPS[i];
			max = Math.max(max, GAPS[i]);
			if (GAPS[i] < 10_000_000L) {
				bunched++; // two ticks run in the same frame
			}
		}
		return String.format(Locale.ROOT, "tick spacing over last %d ticks: avg %.1f ms, max %.1f ms, %d back-to-back",
			n, sum / 1e6 / n, max / 1e6, bunched);
	}
}
