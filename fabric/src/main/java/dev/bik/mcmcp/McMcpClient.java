package dev.bik.mcmcp;

import com.mojang.brigadier.context.CommandContext;
import dev.bik.mcmcp.agent.Agent;
import dev.bik.mcmcp.agent.Vision;
import dev.bik.mcmcp.mcp.McpServer;
import dev.bik.mcmcp.mcp.Tools;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McMcpClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("mcmcp");

	public static String version() {
		return FabricLoader.getInstance().getModContainer("mcmcp")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}

	@Override
	public void onInitializeClient() {
		Config.load();

		// START so held keys/clicks are in place before vanilla reads them in handleKeybinds and the player tick.
		ClientTickEvents.START_CLIENT_TICK.register(Agent::tick);
		// END so the scan uses this tick's position and rotation.
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			Vision.tick(mc);
			if (mc.level == null || mc.level.getGameTime() % 20 == 0) {
				Config.reloadIfChanged();
			}
		});

		// Client-side only: handled locally, never sent to the server, and not reachable through MCP.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
			ClientCommands.literal("mcmcp")
				.executes(McMcpClient::showMode)
				.then(ClientCommands.literal("noxray")
					.executes(McMcpClient::showMode)
					.then(ClientCommands.literal("on").executes(ctx -> setNoXray(ctx, true)))
					.then(ClientCommands.literal("off").executes(ctx -> setNoXray(ctx, false)))
					.then(ClientCommands.literal("toggle").executes(ctx -> setNoXray(ctx, !Config.get().noXray))))
				.then(ClientCommands.literal("fullbright")
					.executes(McMcpClient::showMode)
					.then(ClientCommands.literal("on").executes(ctx -> setFullbright(ctx, true)))
					.then(ClientCommands.literal("off").executes(ctx -> setFullbright(ctx, false)))
					.then(ClientCommands.literal("toggle").executes(ctx -> setFullbright(ctx, !Config.get().fullbright))))));

		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) ->
			ChatLog.add("chat", message.getString()));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				ChatLog.add("system", message.getString());
			}
		});

		McpServer server = new McpServer(new Tools());
		try {
			server.start(Config.get().host, Config.get().port);
		} catch (Exception e) {
			LOGGER.error("Failed to start MCP server on {}:{}", Config.get().host, Config.get().port, e);
			return;
		}
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> server.stop());
	}

	private static int setNoXray(CommandContext<FabricClientCommandSource> ctx, boolean on) {
		Config.get().noXray = on;
		Config.save();
		return showMode(ctx);
	}

	private static int setFullbright(CommandContext<FabricClientCommandSource> ctx, boolean on) {
		Config.get().fullbright = on;
		Config.save();
		return showMode(ctx);
	}

	private static int showMode(CommandContext<FabricClientCommandSource> ctx) {
		boolean on = Config.get().noXray;
		ctx.getSource().sendFeedback(Component.literal("[mcmcp] no-x-ray mode is " + (on ? "ON" : "OFF")
			+ (on ? " (" + Vision.seenCount() + " blocks seen)" : " - the agent can read every loaded block")
			+ ", fullbright is " + (Config.get().fullbright ? "ON" : "OFF")));
		return 1;
	}
}
