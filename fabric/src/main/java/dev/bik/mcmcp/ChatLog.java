package dev.bik.mcmcp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Ring buffer of received chat and system messages, numbered so callers can poll for new lines. */
public final class ChatLog {
	private static final int CAPACITY = 500;
	private static final Deque<Line> LINES = new ArrayDeque<>();
	private static long nextSeq = 1;

	public record Line(long seq, String kind, String text) {
	}

	private ChatLog() {
	}

	public static synchronized void add(String kind, String text) {
		LINES.addLast(new Line(nextSeq++, kind, text));
		while (LINES.size() > CAPACITY) {
			LINES.removeFirst();
		}
	}

	/** Lines with seq greater than {@code after}, at most {@code limit} of the newest. */
	public static synchronized List<Line> read(long after, int limit) {
		List<Line> out = new ArrayList<>();
		for (Line l : LINES) {
			if (l.seq() > after) {
				out.add(l);
			}
		}
		return out.size() > limit ? out.subList(out.size() - limit, out.size()) : out;
	}
}
