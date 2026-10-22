package dev.bik.mcmcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Config instance = new Config();

	/** Address the MCP server binds to. Keep it on loopback unless you know what you are doing. */
	public String host = "127.0.0.1";
	/**
	 * Only let the agent use blocks the player has actually seen (no seeing through walls). Toggle with
	 * {@code /mcmcp noxray on|off} in game or by editing this file; the agent itself cannot change it.
	 */
	public boolean noXray = true;
	/** Render everything fully lit (like night vision). Visual only. Toggle with {@code /mcmcp fullbright on|off}. */
	public boolean fullbright = true;
	public int port = 25599;
	/** Prevents the pause menu from opening (and releasing all keys) when the window loses focus. */
	public boolean keepRunningUnfocused = true;
	/**
	 * When the window is unfocused, skip drawing the world (nothing the server sees depends on rendering) and run the
	 * frame loop at {@link #unfocusedFps} instead of vanilla's 30/10 fps idle throttle, which bunches ticks together.
	 */
	public boolean skipRenderingWhenUnfocused = true;
	public int unfocusedFps = 60;
	/** Upper bound on agent camera turn speed, degrees per second. */
	public double maxTurnSpeed = 540.0;
	/** Lower bound on agent camera turn speed so small corrections still finish quickly, degrees per second. */
	public double minTurnSpeed = 40.0;
	/** Minimum client ticks between two container slot clicks. */
	public int clickIntervalTicks = 3;

	public static Config get() {
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("mcmcp.json");
	}

	private static long lastModified = -1;

	public static void load() {
		Path path = path();
		try {
			if (Files.exists(path)) {
				Config loaded = GSON.fromJson(Files.readString(path), Config.class);
				if (loaded != null) {
					instance = loaded;
				}
			}
			save();
		} catch (IOException | RuntimeException e) {
			McMcpClient.LOGGER.warn("Could not read/write {}, using defaults", path, e);
		}
	}

	public static void save() {
		Path path = path();
		try {
			Files.writeString(path, GSON.toJson(instance));
			lastModified = Files.getLastModifiedTime(path).toMillis();
		} catch (IOException e) {
			McMcpClient.LOGGER.warn("Could not write {}", path, e);
		}
	}

	/** Picks up edits made to the file while the game runs. Network settings only apply after a restart. */
	public static void reloadIfChanged() {
		Path path = path();
		try {
			long modified = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1;
			if (modified == lastModified) {
				return;
			}
			lastModified = modified;
			Config loaded = GSON.fromJson(Files.readString(path), Config.class);
			if (loaded != null) {
				boolean before = instance.noXray;
				instance = loaded;
				if (before != loaded.noXray) {
					McMcpClient.LOGGER.info("no-x-ray mode {} (config file changed)", loaded.noXray ? "enabled" : "disabled");
				}
			}
		} catch (IOException | RuntimeException e) {
			McMcpClient.LOGGER.warn("Ignoring unreadable {}: {}", path, e.toString());
		}
	}
}
