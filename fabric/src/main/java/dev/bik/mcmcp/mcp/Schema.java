package dev.bik.mcmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Tiny builder for JSON-schema tool inputs. */
final class Schema {
	private final JsonObject properties = new JsonObject();
	private final JsonArray required = new JsonArray();

	static Schema object() {
		return new Schema();
	}

	private Schema prop(String name, String type, String description) {
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		if (!description.isEmpty()) {
			p.addProperty("description", description);
		}
		properties.add(name, p);
		return this;
	}

	Schema num(String name, String description) {
		return prop(name, "number", description);
	}

	Schema integer(String name, String description) {
		return prop(name, "integer", description);
	}

	Schema bool(String name, String description) {
		return prop(name, "boolean", description);
	}

	Schema str(String name, String description) {
		return prop(name, "string", description);
	}

	Schema required(String... names) {
		for (String n : names) {
			required.add(n);
		}
		return this;
	}

	JsonObject build() {
		JsonObject o = new JsonObject();
		o.addProperty("type", "object");
		o.add("properties", properties);
		if (!required.isEmpty()) {
			o.add("required", required);
		}
		return o;
	}
}
