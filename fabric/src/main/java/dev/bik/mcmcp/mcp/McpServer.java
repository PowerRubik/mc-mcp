package dev.bik.mcmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bik.mcmcp.McMcpClient;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal MCP server over the Streamable HTTP transport (JSON responses, no SSE). Only loopback origins are accepted
 * to prevent DNS-rebinding attacks from web pages.
 */
public final class McpServer {
	private static final Gson GSON = new Gson();
	private static final Set<String> PROTOCOL_VERSIONS = Set.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");
	private static final String DEFAULT_PROTOCOL = "2025-06-18";

	private final Tools tools;
	private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "mcmcp-worker");
		t.setDaemon(true);
		return t;
	});
	private EventLoopGroup group;
	private Channel channel;

	public McpServer(Tools tools) {
		this.tools = tools;
	}

	public void start(String host, int port) throws InterruptedException {
		group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
		channel = new ServerBootstrap()
			.group(group)
			.channel(NioServerSocketChannel.class)
			.childHandler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(SocketChannel ch) {
					ch.pipeline()
						.addLast(new HttpServerCodec())
						.addLast(new HttpObjectAggregator(1 << 20))
						.addLast(new Handler());
				}
			})
			.bind(host, port).sync().channel();
		McMcpClient.LOGGER.info("MCP server listening on http://{}:{}/mcp", host, port);
	}

	public void stop() {
		if (channel != null) {
			channel.close();
		}
		if (group != null) {
			group.shutdownGracefully();
		}
		workers.shutdownNow();
	}

	private final class Handler extends SimpleChannelInboundHandler<FullHttpRequest> {
		@Override
		protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
			if (!originAllowed(req.headers().get(HttpHeaderNames.ORIGIN))) {
				send(ctx, HttpResponseStatus.FORBIDDEN, null);
				return;
			}
			String path = req.uri().split("\\?", 2)[0];
			if (!path.equals("/mcp") && !path.equals("/")) {
				send(ctx, HttpResponseStatus.NOT_FOUND, null);
				return;
			}
			if (req.method() == HttpMethod.DELETE) {
				send(ctx, HttpResponseStatus.OK, null);
				return;
			}
			if (req.method() != HttpMethod.POST) {
				// No server-initiated stream is offered.
				send(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, null);
				return;
			}
			String body = req.content().toString(StandardCharsets.UTF_8);
			workers.execute(() -> {
				JsonElement response;
				try {
					response = handleBody(JsonParser.parseString(body));
				} catch (RuntimeException e) {
					response = error(null, -32700, "parse error: " + e.getMessage());
				}
				if (response == null) {
					send(ctx, HttpResponseStatus.ACCEPTED, null);
				} else {
					send(ctx, HttpResponseStatus.OK, GSON.toJson(response));
				}
			});
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			McMcpClient.LOGGER.debug("MCP connection error", cause);
			ctx.close();
		}
	}

	private static boolean originAllowed(String origin) {
		if (origin == null || origin.isEmpty() || origin.equals("null")) {
			return true; // non-browser clients
		}
		try {
			String host = URI.create(origin).getHost();
			return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static void send(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
		byte[] bytes = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
		FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
		if (json != null) {
			res.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
		}
		res.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
		ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
	}

	private JsonElement handleBody(JsonElement body) {
		if (body.isJsonArray()) {
			JsonArray out = new JsonArray();
			for (JsonElement e : body.getAsJsonArray()) {
				JsonObject r = handleMessage(e.getAsJsonObject());
				if (r != null) {
					out.add(r);
				}
			}
			return out.isEmpty() ? null : out;
		}
		return handleMessage(body.getAsJsonObject());
	}

	private JsonObject handleMessage(JsonObject msg) {
		JsonElement id = msg.get("id");
		if (!msg.has("method")) {
			return null; // a response from the client; we never send requests
		}
		String method = msg.get("method").getAsString();
		JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
		if (id == null || id.isJsonNull()) {
			return null; // notification (e.g. notifications/initialized)
		}
		try {
			return switch (method) {
				case "initialize" -> result(id, initialize(params));
				case "ping" -> result(id, new JsonObject());
				case "tools/list" -> {
					JsonObject r = new JsonObject();
					r.add("tools", tools.list());
					yield result(id, r);
				}
				case "tools/call" -> {
					String name = params.get("name").getAsString();
					JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
						? params.getAsJsonObject("arguments") : new JsonObject();
					if (!tools.has(name)) {
						yield error(id, -32602, "unknown tool: " + name);
					}
					yield result(id, tools.call(name, args));
				}
				case "resources/list" -> {
					JsonObject r = new JsonObject();
					r.add("resources", new JsonArray());
					yield result(id, r);
				}
				case "prompts/list" -> {
					JsonObject r = new JsonObject();
					r.add("prompts", new JsonArray());
					yield result(id, r);
				}
				default -> error(id, -32601, "method not found: " + method);
			};
		} catch (RuntimeException e) {
			McMcpClient.LOGGER.warn("MCP request {} failed", method, e);
			return error(id, -32603, "internal error: " + e);
		}
	}

	private static JsonObject initialize(JsonObject params) {
		String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : DEFAULT_PROTOCOL;
		JsonObject r = new JsonObject();
		r.addProperty("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : DEFAULT_PROTOCOL);
		JsonObject caps = new JsonObject();
		JsonObject toolCaps = new JsonObject();
		toolCaps.addProperty("listChanged", false);
		caps.add("tools", toolCaps);
		r.add("capabilities", caps);
		JsonObject info = new JsonObject();
		info.addProperty("name", "mcmcp");
		info.addProperty("version", McMcpClient.version());
		r.add("serverInfo", info);
		r.addProperty("instructions", Tools.INSTRUCTIONS);
		return r;
	}

	private static JsonObject result(JsonElement id, JsonObject result) {
		JsonObject r = new JsonObject();
		r.addProperty("jsonrpc", "2.0");
		r.add("id", id);
		r.add("result", result);
		return r;
	}

	private static JsonObject error(JsonElement id, int code, String message) {
		JsonObject r = new JsonObject();
		r.addProperty("jsonrpc", "2.0");
		r.add("id", id);
		JsonObject e = new JsonObject();
		e.addProperty("code", code);
		e.addProperty("message", message);
		r.add("error", e);
		return r;
	}
}
