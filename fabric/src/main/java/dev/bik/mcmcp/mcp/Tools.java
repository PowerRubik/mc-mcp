package dev.bik.mcmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bik.mcmcp.ChatLog;
import dev.bik.mcmcp.agent.Agent;
import dev.bik.mcmcp.agent.Input;
import dev.bik.mcmcp.agent.Items;
import dev.bik.mcmcp.agent.SequenceTask;
import dev.bik.mcmcp.agent.Task;
import dev.bik.mcmcp.agent.Vision;
import dev.bik.mcmcp.agent.tasks.ClickTask;
import dev.bik.mcmcp.agent.tasks.EntityTask;
import dev.bik.mcmcp.agent.tasks.EquipTask;
import dev.bik.mcmcp.agent.tasks.GotoTask;
import dev.bik.mcmcp.agent.tasks.HoldKeysTask;
import dev.bik.mcmcp.agent.tasks.InteractBlockTask;
import dev.bik.mcmcp.agent.tasks.LookTask;
import dev.bik.mcmcp.agent.tasks.MineTask;
import dev.bik.mcmcp.agent.tasks.PlaceTask;
import dev.bik.mcmcp.agent.tasks.SlotClickTask;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class Tools {
	public static final String INSTRUCTIONS = """
		Controls a Minecraft Java 26.2 client. Every action goes through vanilla input: held movement keys, \
		mouse-style camera turns quantized to the player's sensitivity, and real left/right clicks on whatever the \
		crosshair resolves to. Nothing teleports, reaches through walls, or sends hand-crafted packets.
		Coordinates: x/z horizontal, y up. For goto, give the block the player's FEET should end in (one above the floor).
		Actions run one at a time; starting a new one cancels the previous. Long actions return when finished or when \
		timeout_seconds elapses (then use wait_for_task / get_status / stop).
		mine_block, place_block and interact_block need the target within reach (~4.5 blocks) and visible: goto first.
		Container slot indices come from get_screen.
		When no-x-ray mode is on (see get_status; only the user can change it), world queries and pathing only use 		blocks the player has actually seen, and list_entities only shows entities in line of sight. Use look and 		explore to see more; goto into unseen areas replans as terrain comes into view.""";

	@FunctionalInterface
	private interface Handler {
		String call(JsonObject args) throws Exception;
	}

	private record Tool(String description, JsonObject schema, Handler handler) {
	}

	/** Thrown for expected failures; reported with isError=true. */
	static final class ToolError extends Exception {
		ToolError(String message) {
			super(message);
		}
	}

	private final Map<String, Tool> tools = new LinkedHashMap<>();

	public Tools() {
		register();
	}

	public boolean has(String name) {
		return tools.containsKey(name);
	}

	public JsonArray list() {
		JsonArray arr = new JsonArray();
		tools.forEach((name, t) -> {
			JsonObject o = new JsonObject();
			o.addProperty("name", name);
			o.addProperty("description", t.description());
			o.add("inputSchema", t.schema());
			arr.add(o);
		});
		return arr;
	}

	public JsonObject call(String name, JsonObject args) {
		String text;
		boolean isError = false;
		try {
			text = tools.get(name).handler().call(args);
		} catch (ToolError e) {
			text = e.getMessage();
			isError = true;
		} catch (Exception e) {
			text = "error: " + e;
			isError = true;
		}
		JsonObject result = new JsonObject();
		JsonArray content = new JsonArray();
		JsonObject c = new JsonObject();
		c.addProperty("type", "text");
		c.addProperty("text", text);
		content.add(c);
		result.add("content", content);
		result.addProperty("isError", isError);
		return result;
	}

	// ---------------------------------------------------------------- helpers

	private void tool(String name, String description, Schema schema, Handler handler) {
		tools.put(name, new Tool(description, schema.build(), handler));
	}

	private static <T> T onClient(Supplier<T> s) throws Exception {
		try {
			return Minecraft.getInstance().submit(s).get(10, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof RuntimeException re && re.getCause() instanceof ToolError te) {
				throw te;
			}
			throw e;
		}
	}

	private static LocalPlayer player() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) {
			throw new IllegalStateException(new ToolError("not in a world"));
		}
		return p;
	}

	private static void fail(String msg) {
		throw new IllegalStateException(new ToolError(msg));
	}

	private static String run(Task task, double timeoutSeconds) throws Exception {
		if (Minecraft.getInstance().player == null) {
			throw new ToolError("not in a world");
		}
		return await(Agent.submit(task), timeoutSeconds);
	}

	private static String await(CompletableFuture<Task.Result> future, double timeoutSeconds) throws Exception {
		try {
			Task.Result r = future.get((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS);
			if (!r.ok()) {
				throw new ToolError(r.message());
			}
			return r.message();
		} catch (TimeoutException e) {
			return "still running: " + Agent.status() + ". Call wait_for_task to keep waiting, or stop.";
		}
	}

	private static int i(JsonObject a, String k, int def) {
		return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsInt() : def;
	}

	private static double d(JsonObject a, String k, double def) {
		return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsDouble() : def;
	}

	private static boolean b(JsonObject a, String k, boolean def) {
		return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsBoolean() : def;
	}

	private static String s(JsonObject a, String k, String def) {
		return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsString() : def;
	}

	private static int req(JsonObject a, String k) throws ToolError {
		if (!a.has(k) || a.get(k).isJsonNull()) {
			throw new ToolError("missing argument: " + k);
		}
		return (int) Math.floor(a.get(k).getAsDouble());
	}

	private static BlockPos pos(JsonObject a) throws ToolError {
		return new BlockPos(req(a, "x"), req(a, "y"), req(a, "z"));
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}

	private static String blockId(BlockState s) {
		return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
	}

	private static String cardinal(float yaw) {
		return Direction.fromYRot(yaw).getName();
	}

	// ---------------------------------------------------------------- tools

	private void register() {
		tool("get_status", "Position, rotation, health, hunger, held item, crosshair target, open screen and agent task state.",
			Schema.object(), a -> onClient(Tools::status));

		tool("get_inventory", "List all inventory slots (0-8 hotbar, 9-35 main), armor and offhand.",
			Schema.object(), a -> onClient(() -> {
				LocalPlayer p = player();
				Inventory inv = p.getInventory();
				StringBuilder sb = new StringBuilder("selected hotbar slot: " + inv.getSelectedSlot() + "\n");
				for (int idx = 0; idx < 36; idx++) {
					ItemStack st = inv.getItem(idx);
					if (!st.isEmpty()) {
						sb.append(idx < 9 ? "hotbar " : "inv ").append(idx).append(": ").append(Items.describe(st)).append('\n');
					}
				}
				for (EquipmentSlot es : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
					ItemStack st = p.getItemBySlot(es);
					if (!st.isEmpty()) {
						sb.append(es.getName()).append(": ").append(Items.describe(st)).append('\n');
					}
				}
				return sb.toString().trim();
			}));

		tool("get_screen", "Describe the open GUI. For container screens, lists every non-empty slot index usable with click_slot.",
			Schema.object(), a -> onClient(() -> {
				Minecraft mc = Minecraft.getInstance();
				var screen = mc.gui.screen();
				if (screen == null) {
					return "no screen open";
				}
				StringBuilder sb = new StringBuilder("screen: " + screen.getTitle().getString() + " (" + screen.getClass().getSimpleName() + ")\n");
				if (screen instanceof AbstractContainerScreen<?> cs) {
					AbstractContainerMenu menu = cs.getMenu();
					sb.append("slots: ").append(menu.slots.size()).append(", cursor holds ").append(Items.describe(menu.getCarried())).append('\n');
					for (Slot slot : menu.slots) {
						boolean playerInv = slot.container instanceof Inventory;
						if (slot.hasItem()) {
							sb.append(slot.index).append(playerInv ? " [player " + slot.getContainerSlot() + "]" : " [container]")
								.append(": ").append(Items.describe(slot.getItem())).append(Items.lore(slot.getItem())).append('\n');
						}
					}
					int firstPlayer = -1;
					for (Slot slot : menu.slots) {
						if (slot.container instanceof Inventory) {
							firstPlayer = slot.index;
							break;
						}
					}
					sb.append("(container slots are below index ").append(firstPlayer < 0 ? menu.slots.size() : firstPlayer).append(")");
				}
				return sb.toString().trim();
			}));

		tool("look", "Smoothly turn the camera, like moving the mouse. Give either x,y,z (a point to look at) or yaw,pitch in degrees (yaw: 0=south, 90=west, 180=north, -90=east; pitch: -90 up, 90 down).",
			Schema.object().num("x", "point x").num("y", "point y").num("z", "point z").num("yaw", "yaw degrees").num("pitch", "pitch degrees"),
			a -> {
				if (a.has("x") && a.has("y") && a.has("z")) {
					return run(new LookTask(new Vec3(d(a, "x", 0), d(a, "y", 0), d(a, "z", 0)), 0, 0), 10);
				}
				if (a.has("yaw") || a.has("pitch")) {
					float[] cur = onClient(() -> new float[]{player().getYRot(), player().getXRot()});
					return run(new LookTask(null, (float) d(a, "yaw", cur[0]), (float) d(a, "pitch", cur[1])), 10);
				}
				throw new ToolError("give x,y,z or yaw/pitch");
			});

		tool("walk", "Hold movement keys for a number of ticks (20 ticks = 1 second). Camera does not change; use look first. For travel prefer goto.",
			Schema.object()
				.bool("forward", "hold W").bool("back", "hold S").bool("left", "hold A").bool("right", "hold D")
				.bool("jump", "hold space").bool("sneak", "hold shift").bool("sprint", "hold sprint")
				.integer("ticks", "how long, 1-400 (default 20)"),
			a -> {
				Options o = Minecraft.getInstance().options;
				List<KeyMapping> keys = new ArrayList<>();
				if (b(a, "forward", false)) keys.add(o.keyUp);
				if (b(a, "back", false)) keys.add(o.keyDown);
				if (b(a, "left", false)) keys.add(o.keyLeft);
				if (b(a, "right", false)) keys.add(o.keyRight);
				if (b(a, "jump", false)) keys.add(o.keyJump);
				if (b(a, "sneak", false)) keys.add(o.keyShift);
				if (b(a, "sprint", false)) keys.add(o.keySprint);
				int ticks = Math.clamp(i(a, "ticks", 20), 1, 400);
				return run(new HoldKeysTask("walk", keys, ticks, false), ticks / 20.0 + 10);
			});

		tool("goto", "Pathfind and walk to a position using only normal movement (walk, jump up 1 block, drop up to 3, swim). Does not break or place blocks. x,y,z is where the feet should end up; omit y to accept any height at that x/z. Pathing only sees loaded chunks: for far targets, call repeatedly.",
			Schema.object().integer("x", "target x").integer("y", "target feet y (optional)").integer("z", "target z").required("x", "z")
				.num("range", "accept any position within this many blocks of the target (default 0 = exact block; use 2-3 to approach a block you want to mine/use)")
				.bool("sprint", "sprint on straight segments (default true)")
				.num("timeout_seconds", "max seconds to wait before returning while it keeps walking (default 120)"),
			a -> {
				boolean anyY = !a.has("y") || a.get("y").isJsonNull();
				BlockPos target = new BlockPos(req(a, "x"), anyY ? 0 : req(a, "y"), req(a, "z"));
				return run(new GotoTask(target, d(a, "range", 0), anyY, b(a, "sprint", true)), d(a, "timeout_seconds", 120));
			});

		tool("explore", "No-x-ray mode: walk to the nearest seen, standable spot bordering unseen space (optionally in one direction), revealing more of the world. Call repeatedly to map caves or terrain.",
			Schema.object().str("direction", "north, south, east or west (default: any)")
				.integer("max_distance", "how far from here to look for an unexplored edge, 8-96 (default 32)")
				.num("timeout_seconds", "default 60"),
			a -> {
				String dir = s(a, "direction", null);
				Direction direction = null;
				if (dir != null) {
					direction = Direction.byName(dir.toLowerCase(Locale.ROOT));
					if (direction == null || direction.getAxis().isVertical()) {
						throw new ToolError("direction must be north, south, east or west");
					}
				}
				return run(new dev.bik.mcmcp.agent.tasks.ExploreTask(direction, Math.clamp(i(a, "max_distance", 32), 8, 96)), d(a, "timeout_seconds", 60));
			});

		tool("fly_to", "Elytra travel (elytra must be worn; firework rockets on the hotbar are used for take-off and boosts): take off, cruise high over terrain, glide down and land near x,z. Give y to land at a specific height.",
			Schema.object().integer("x", "target x").integer("z", "target z").required("x", "z").integer("y", "optional landing height")
				.num("timeout_seconds", "default 120"),
			a -> run(new dev.bik.mcmcp.agent.tasks.FlyTask(req(a, "x"), a.has("y") ? (double) req(a, "y") : null, req(a, "z")), d(a, "timeout_seconds", 120)));

		tool("fight", "Fight a player (by name) or entity (by id) until it dies or time runs out. With an elytra, firework rockets and a spear it does elytra dive runs: climbs above the target, holds use to charge the spear and dives at it (damage scales with closing speed), pulls up, repeats. Without them it melees (spacing, strafing, crits). Difficulty sets reaction time, aim error, turn speed and attack timing. Puts on the elytra and moves rockets/spear to the hotbar first if needed.",
			Schema.object().str("target", "player name").integer("entity_id", "or an entity id")
				.str("difficulty", "easy | normal | hard | max (default hard)")
				.bool("elytra", "use elytra dive attacks (default: yes if an elytra, rockets and a spear are available)")
				.num("timeout_seconds", "how long to fight before stopping (default 120)"),
			a -> {
				String name = s(a, "target", null);
				Integer id = a.has("entity_id") ? req(a, "entity_id") : null;
				if (name == null && id == null) {
					throw new ToolError("give target (player name) or entity_id");
				}
				double timeout = d(a, "timeout_seconds", 120);
				List<Task> steps = onClient(() -> {
					LocalPlayer p = player();
					List<Task> plan = new ArrayList<>();
					boolean hasElytra = dev.bik.mcmcp.agent.Combat.wearingElytra(p) || Items.find(p, "minecraft:elytra") >= 0;
					boolean hasRockets = Items.find(p, "minecraft:firework_rocket") >= 0;
					int spearIdx = -1;
					for (int i = 0; i < 36; i++) {
						if (dev.bik.mcmcp.agent.Combat.isSpear(p.getInventory().getItem(i))) {
							spearIdx = i;
							break;
						}
					}
					boolean air = b(a, "elytra", hasElytra && hasRockets && spearIdx >= 0);
					if (air) {
						if (!hasElytra || !hasRockets || spearIdx < 0) {
							fail("elytra fighting needs an elytra, firework rockets and a spear");
						}
						if (!dev.bik.mcmcp.agent.Combat.wearingElytra(p)) {
							plan.add(new EquipTask("minecraft:elytra", -1));
							plan.add(new ClickTask("wear elytra", Minecraft.getInstance().options.keyUse, 3));
						}
						if (dev.bik.mcmcp.agent.Combat.hotbar(p, dev.bik.mcmcp.agent.Combat::isRocket) < 0) {
							plan.add(new EquipTask("minecraft:firework_rocket", -1));
						}
						if (spearIdx >= 9) {
							plan.add(new EquipTask(Items.id(p.getInventory().getItem(spearIdx)), -1));
						}
					}
					plan.add(new dev.bik.mcmcp.agent.tasks.FightTask(id, name, dev.bik.mcmcp.agent.Combat.Skill.of(s(a, "difficulty", "hard")), air, (int) (timeout * 20)));
					return plan;
				});
				Task task = steps.size() == 1 ? steps.get(0) : new SequenceTask(steps.toArray(new Task[0]));
				return run(task, timeout + 40);
			});

		tool("respawn", "Click Respawn on the death screen.", Schema.object(),
			a -> run(new dev.bik.mcmcp.agent.tasks.RespawnTask(), 15));

		tool("stop", "Cancel the running action and release all keys.", Schema.object(),
			a -> await(Agent.stop(), 5));

		tool("wait_for_task", "Wait for the currently running action to finish.",
			Schema.object().num("timeout_seconds", "max seconds to wait (default 60)"),
			a -> {
				CompletableFuture<Task.Result> f = onClient(Agent::currentFuture);
				if (f == null) {
					return "no task running; last result: " + Agent.lastResult();
				}
				return await(f, d(a, "timeout_seconds", 60));
			});

		tool("mine_block", "Look at a block and hold left click until it breaks. Picks the best hotbar tool first unless auto_tool=false.",
			Schema.object().integer("x", "block x").integer("y", "block y").integer("z", "block z").required("x", "y", "z")
				.bool("auto_tool", "switch to the fastest hotbar tool (default true)")
				.num("timeout_seconds", "default 60"),
			a -> run(new MineTask(pos(a), b(a, "auto_tool", true)), d(a, "timeout_seconds", 60)));

		tool("place_block", "Place a block at x,y,z by right-clicking a visible face of an adjacent solid block. Optionally equips the item first (pulling it from the main inventory via the inventory screen if needed).",
			Schema.object().integer("x", "target x").integer("y", "target y").integer("z", "target z").required("x", "y", "z")
				.str("item", "item id to place, e.g. minecraft:cobblestone (default: whatever is held)")
				.bool("sneak", "hold sneak while clicking (default: automatic when clicking against chests, doors, etc.)"),
			a -> {
				BlockPos p = pos(a);
				Boolean sneak = a.has("sneak") ? b(a, "sneak", false) : null;
				String item = s(a, "item", null);
				Task place = new PlaceTask(p, sneak);
				return run(item == null ? place : new SequenceTask(new EquipTask(item, -1), place), 30);
			});

		tool("interact_block", "Right-click a block: open chests/furnaces/crafting tables, press buttons, flip levers, open doors, use beds.",
			Schema.object().integer("x", "block x").integer("y", "block y").integer("z", "block z").required("x", "y", "z"),
			a -> run(new InteractBlockTask(pos(a)), 15));

		tool("attack_entity", "Aim at an entity (id from list_entities) and left-click it, waiting for full attack cooldown between hits.",
			Schema.object().integer("entity_id", "entity id").required("entity_id").integer("times", "number of hits (default 1)")
				.num("timeout_seconds", "default 30"),
			a -> run(new EntityTask(req(a, "entity_id"), true, i(a, "times", 1)), d(a, "timeout_seconds", 30)));

		tool("interact_entity", "Aim at an entity and right-click it (trade with villagers, mount, feed/breed with held item...).",
			Schema.object().integer("entity_id", "entity id").required("entity_id"),
			a -> run(new EntityTask(req(a, "entity_id"), false, 1), 15));

		tool("use_item", "Right-click with the held item without targeting anything specific (eat, drink, throw, bow...). ticks=0 clicks once; otherwise holds right click for that many ticks (eating takes ~32), releasing early when the use finishes.",
			Schema.object().integer("ticks", "hold duration (default 0 = single click)"),
			a -> {
				Options o = Minecraft.getInstance().options;
				int t = Math.clamp(i(a, "ticks", 0), 0, 400);
				Task task = t == 0 ? new ClickTask("use item", o.keyUse, 3) : new HoldKeysTask("use item", List.of(o.keyUse), t, true);
				return run(task, t / 20.0 + 10);
			});

		tool("hold_click", "Press and hold a mouse button for a number of ticks (20 = 1 s), aimed wherever the crosshair currently is (use look first). Left: one attack click, then continuous mining of the block under the crosshair. Right: one use, then vanilla's held use (repeats every 4 ticks, or keeps eating/drawing a bow/blocking). Ends early on stop.",
			Schema.object().str("button", "left or right").required("button")
				.integer("ticks", "how long to hold, 1-1200 (default 20)")
				.bool("release_when_use_ends", "right button only: release as soon as an item use (eating, drinking...) completes (default false)"),
			a -> {
				String button = s(a, "button", "").toLowerCase(Locale.ROOT);
				if (!button.equals("left") && !button.equals("right")) {
					throw new ToolError("button must be left or right");
				}
				int t = Math.clamp(i(a, "ticks", 20), 1, 1200);
				return run(new dev.bik.mcmcp.agent.tasks.HoldClickTask(button.equals("left"), t, b(a, "release_when_use_ends", false)), t / 20.0 + 10);
			});

		tool("sneak", "Toggle sneaking on or off. Stays on across all other actions until turned off (e.g. to stay at ledge edges while building). Note: you cannot sprint while sneaking.",
			Schema.object().bool("on", "true to sneak, false to stand").required("on"),
			a -> onClient(() -> {
				Input.setSneakToggle(b(a, "on", false));
				return Input.sneakToggled() ? "sneaking (toggle on)" : "not sneaking (toggle off)";
			}));

		tool("equip", "Hold an item in the main hand. Give item (id) to find it anywhere in the inventory, or hotbar_slot 1-9.",
			Schema.object().str("item", "item id, e.g. minecraft:diamond_pickaxe").integer("hotbar_slot", "1-9"),
			a -> {
				String item = s(a, "item", null);
				int slot = i(a, "hotbar_slot", 0);
				if (item == null && (slot < 1 || slot > 9)) {
					throw new ToolError("give item or hotbar_slot 1-9");
				}
				return run(new EquipTask(item, slot >= 1 ? slot - 1 : -1), 15);
			});

		tool("drop_item", "Press the drop key once (drops one item from the held stack).", Schema.object(),
			a -> run(new ClickTask("drop", Minecraft.getInstance().options.keyDrop, 2), 5));

		tool("swap_hands", "Press the swap-offhand key.", Schema.object(),
			a -> run(new ClickTask("swap hands", Minecraft.getInstance().options.keySwapOffhand, 2), 5));

		tool("open_inventory", "Press the inventory key.", Schema.object(),
			a -> run(new ClickTask("open inventory", Minecraft.getInstance().options.keyInventory, 3), 5));

		tool("close_screen", "Close the open GUI (like pressing Escape).", Schema.object(),
			a -> onClient(() -> {
				Minecraft mc = Minecraft.getInstance();
				if (mc.gui.screen() == null) {
					return "no screen open";
				}
				mc.gui.screen().onClose();
				return "closed";
			}));

		tool("click_slot", "Click a slot in the open container screen. Modes: pickup (normal click; button 0=left 1=right), quick_move (shift-click), swap (button = hotbar 0-8, or 40 for offhand), throw (button 0=one, 1=stack), pickup_all (double-click). slot -999 = outside the window.",
			Schema.object().integer("slot", "slot index from get_screen").required("slot")
				.integer("button", "default 0").str("mode", "pickup | quick_move | swap | throw | pickup_all (default pickup)"),
			a -> {
				ContainerInput mode;
				try {
					mode = ContainerInput.valueOf(s(a, "mode", "pickup").toUpperCase(Locale.ROOT));
				} catch (IllegalArgumentException e) {
					throw new ToolError("unknown mode");
				}
				if (mode == ContainerInput.CLONE || mode == ContainerInput.QUICK_CRAFT) {
					throw new ToolError("mode not supported");
				}
				return run(new SlotClickTask(req(a, "slot"), i(a, "button", 0), mode), 5);
			});

		tool("send_chat", "Send a chat message, or a command if it starts with '/'.",
			Schema.object().str("message", "text").required("message"),
			a -> {
				String msg = s(a, "message", "").trim();
				if (msg.isEmpty()) {
					throw new ToolError("empty message");
				}
				if (msg.length() > 256) {
					throw new ToolError("message longer than 256 characters");
				}
				if (msg.toLowerCase(Locale.ROOT).matches("/+mcmcp(\\s.*)?")) {
					throw new ToolError("/mcmcp settings are reserved for the user");
				}
				return onClient(() -> {
					LocalPlayer p = player();
					if (msg.startsWith("/")) {
						p.connection.sendCommand(msg.substring(1));
					} else {
						p.connection.sendChat(msg);
					}
					return "sent";
				});
			});

		tool("read_chat", "Read received chat and system messages. Each line has a sequence number; pass after=<last seq> to get only new lines.",
			Schema.object().integer("after", "only lines with seq greater than this (default 0)").integer("limit", "max lines (default 30)"),
			a -> {
				List<ChatLog.Line> lines = ChatLog.read(i(a, "after", 0), Math.clamp(i(a, "limit", 30), 1, 500));
				if (lines.isEmpty()) {
					return "no messages";
				}
				StringBuilder sb = new StringBuilder();
				for (ChatLog.Line l : lines) {
					sb.append('#').append(l.seq()).append(" [").append(l.kind()).append("] ").append(l.text()).append('\n');
				}
				return sb.toString().trim();
			});

		tool("find_blocks", "Find the nearest blocks of a type in loaded chunks around the player.",
			Schema.object().str("block", "block id, e.g. minecraft:oak_log").required("block")
				.integer("radius", "search radius, max 48 (default 24)").integer("limit", "max results (default 10)"),
			a -> {
				Identifier id = Identifier.tryParse(Items.normalizeId(s(a, "block", "")));
				if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
					throw new ToolError("unknown block id");
				}
				Block block = BuiltInRegistries.BLOCK.getOptional(id).orElseThrow();
				int radius = Math.clamp(i(a, "radius", 24), 1, 48);
				int limit = Math.clamp(i(a, "limit", 10), 1, 100);
				return onClient(() -> {
					LocalPlayer p = player();
					Level level = p.level();
					BlockPos c = p.blockPosition();
					List<BlockPos> found = new ArrayList<>();
					BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
					for (int x = -radius; x <= radius; x++) {
						for (int y = Math.max(level.getMinY() - c.getY(), -radius); y <= Math.min(level.getMaxY() - c.getY(), radius); y++) {
							for (int z = -radius; z <= radius; z++) {
								m.set(c.getX() + x, c.getY() + y, c.getZ() + z);
								if (level.isLoaded(m) && Vision.known(m) && level.getBlockState(m).is(block)) {
									found.add(m.immutable());
								}
							}
						}
					}
					found.sort((p1, p2) -> Double.compare(p1.distSqr(c), p2.distSqr(c)));
					if (found.isEmpty()) {
						return "none found within " + radius + (Vision.noXray() ? " among blocks you have seen" : "");
					}
					StringBuilder sb = new StringBuilder();
					for (BlockPos f : found.subList(0, Math.min(limit, found.size()))) {
						sb.append(f.getX()).append(' ').append(f.getY()).append(' ').append(f.getZ())
							.append(" (").append(fmt(Math.sqrt(f.distSqr(c)))).append(" away)\n");
					}
					return sb.append(found.size()).append(" total").toString();
				});
			});

		tool("get_blocks", "List non-air blocks in a box (max 4096 blocks), e.g. to inspect terrain before building or digging.",
			Schema.object().integer("x1", "").integer("y1", "").integer("z1", "").integer("x2", "").integer("y2", "").integer("z2", "")
				.required("x1", "y1", "z1", "x2", "y2", "z2"),
			a -> {
				int x1 = Math.min(req(a, "x1"), req(a, "x2")), x2 = Math.max(req(a, "x1"), req(a, "x2"));
				int y1 = Math.min(req(a, "y1"), req(a, "y2")), y2 = Math.max(req(a, "y1"), req(a, "y2"));
				int z1 = Math.min(req(a, "z1"), req(a, "z2")), z2 = Math.max(req(a, "z1"), req(a, "z2"));
				long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
				if (volume > 4096) {
					throw new ToolError("box too large (" + volume + " > 4096)");
				}
				return onClient(() -> {
					Level level = player().level();
					StringBuilder sb = new StringBuilder();
					int air = 0;
					int unknown = 0;
					for (int y = y2; y >= y1; y--) {
						for (int z = z1; z <= z2; z++) {
							for (int x = x1; x <= x2; x++) {
								BlockPos bp = new BlockPos(x, y, z);
								if (!level.isLoaded(bp)) {
									sb.append(x).append(' ').append(y).append(' ').append(z).append(" unloaded\n");
									continue;
								}
								if (!Vision.known(bp)) {
									unknown++;
									continue;
								}
								BlockState st = level.getBlockState(bp);
								if (st.isAir()) {
									air++;
									continue;
								}
								sb.append(x).append(' ').append(y).append(' ').append(z).append(' ').append(blockId(st)).append('\n');
							}
						}
					}
					sb.append(air).append(" air blocks omitted");
					if (unknown > 0) {
						sb.append(", ").append(unknown).append(" never-seen blocks omitted (no-x-ray mode)");
					}
					return sb.toString();
				});
			});

		tool("list_entities", "List entities near the player with ids for attack_entity / interact_entity.",
			Schema.object().integer("radius", "default 24, max 64").integer("limit", "default 25"),
			a -> {
				int radius = Math.clamp(i(a, "radius", 24), 1, 64);
				int limit = Math.clamp(i(a, "limit", 25), 1, 200);
				return onClient(() -> {
					LocalPlayer p = player();
					List<Entity> list = p.level().getEntities(p, p.getBoundingBox().inflate(radius), e -> !Vision.noXray() || inSight(p, e));
					list.sort((e1, e2) -> Double.compare(e1.distanceToSqr(p), e2.distanceToSqr(p)));
					if (list.isEmpty()) {
						return "no entities within " + radius;
					}
					StringBuilder sb = new StringBuilder();
					for (Entity e : list.subList(0, Math.min(limit, list.size()))) {
						sb.append("id ").append(e.getId()).append(": ").append(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
						if (e.hasCustomName() || e instanceof net.minecraft.world.entity.player.Player) {
							sb.append(" \"").append(e.getName().getString()).append('"');
						}
						if (e instanceof ItemEntity ie) {
							sb.append(" (").append(Items.describe(ie.getItem())).append(')');
						}
						sb.append(" at ").append(fmt(e.getX())).append(' ').append(fmt(e.getY())).append(' ').append(fmt(e.getZ()))
							.append(", ").append(fmt(e.distanceTo(p))).append(" away");
						if (e instanceof LivingEntity le) {
							sb.append(", hp ").append(fmt(le.getHealth())).append('/').append(fmt(le.getMaxHealth()));
						}
						sb.append('\n');
					}
					return sb.toString().trim();
				});
			});
	}

	/** Line of sight from the eye to the entity's eyes, center or feet, as a player would see it. */
	private static boolean inSight(LocalPlayer p, Entity e) {
		Vec3 eye = p.getEyePosition();
		for (Vec3 target : new Vec3[]{e.getEyePosition(), e.getBoundingBox().getCenter(), e.position().add(0, 0.1, 0)}) {
			HitResult hit = p.level().clip(new net.minecraft.world.level.ClipContext(eye, target,
				net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
			if (hit.getType() == HitResult.Type.MISS) {
				return true;
			}
		}
		return false;
	}

	private static String status() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null) {
			return "not in a world (screen: " + (mc.gui.screen() == null ? "none" : mc.gui.screen().getTitle().getString()) + ")";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("position: ").append(fmt(p.getX())).append(' ').append(fmt(p.getY())).append(' ').append(fmt(p.getZ()))
			.append(" (block ").append(p.blockPosition().toShortString()).append(")\n");
		sb.append("dimension: ").append(p.level().dimension().identifier()).append('\n');
		sb.append("facing: yaw ").append(fmt(p.getYRot())).append(" pitch ").append(fmt(p.getXRot()))
			.append(" (").append(cardinal(p.getYRot())).append(")\n");
		if (p.isDeadOrDying()) {
			sb.append("DEAD - call respawn\n");
		}
		sb.append("health: ").append(fmt(p.getHealth())).append('/').append(fmt(p.getMaxHealth()))
			.append(", food: ").append(p.getFoodData().getFoodLevel()).append("/20, saturation ").append(fmt(p.getFoodData().getSaturationLevel()))
			.append(", xp level ").append(p.experienceLevel).append('\n');
		sb.append("gamemode: ").append(mc.gameMode == null ? "?" : mc.gameMode.getPlayerMode().getName())
			.append(", on ground: ").append(p.onGround()).append(", sneaking: ").append(p.isShiftKeyDown())
			.append(Input.sneakToggled() ? " (toggle on)" : "").append(", in water: ").append(p.isInWater()).append('\n');
		sb.append("held (slot ").append(p.getInventory().getSelectedSlot() + 1).append("): ").append(Items.describe(p.getMainHandItem()))
			.append(", offhand: ").append(Items.describe(p.getOffhandItem())).append('\n');
		HitResult hr = mc.hitResult;
		if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
			sb.append("crosshair: block ").append(blockId(p.level().getBlockState(bhr.getBlockPos()))).append(" at ")
				.append(bhr.getBlockPos().toShortString()).append(" face ").append(bhr.getDirection().getName()).append('\n');
		} else if (hr instanceof EntityHitResult ehr) {
			sb.append("crosshair: entity ").append(ehr.getEntity().getId()).append(' ')
				.append(BuiltInRegistries.ENTITY_TYPE.getKey(ehr.getEntity().getType())).append('\n');
		} else {
			sb.append("crosshair: nothing\n");
		}
		sb.append("screen: ").append(mc.gui.screen() == null ? "none" : mc.gui.screen().getTitle().getString())
			.append(mc.gui.overlay() != null ? " (overlay active: clicks are ignored until it closes)" : "").append('\n');
		sb.append("vision: ").append(Vision.noXray()
			? "no-x-ray (only seen blocks are known; " + Vision.seenCount() + " seen so far)"
			: "x-ray allowed (all loaded blocks are known)").append('\n');
		sb.append("window focused: ").append(mc.isWindowActive()).append(", fps: ").append(mc.getFps())
			.append(" (throttle: ").append(mc.getFramerateLimitTracker().getThrottleReason().name().toLowerCase(Locale.ROOT)).append(")\n");
		sb.append(dev.bik.mcmcp.agent.TickStats.describe()).append('\n');
		sb.append("task: ").append(Agent.status());
		String held = Input.describe();
		if (!held.isEmpty()) {
			sb.append(" [keys: ").append(held).append(']');
		}
		sb.append("\nlast result: ").append(Agent.lastResult());
		return sb.toString();
	}
}
