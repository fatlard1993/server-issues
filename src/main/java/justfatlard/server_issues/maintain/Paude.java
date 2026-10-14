package justfatlard.server_issues.maintain;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The few calls of paude's API this needs, made as the token's owner. Never on the server thread. */
final class Paude {
	private Paude() {}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	/** A dialog Claude is showing: a permission prompt or a question, answered by pressing one option's key. */
	record Asking(String title, List<String> details, String question, List<Option> options) {}

	record Option(String key, String label) {}

	/** paude said no, or was not there: the message is fit to show the player. */
	static final class Refused extends RuntimeException {
		Refused(String message) {
			super(message);
		}
	}

	/** A new session in the project, which Claude starts on with {@code prompt}. @return its id */
	static CompletableFuture<String> start(Settings settings, String prompt) {
		JsonObject body = new JsonObject();
		body.addProperty("text", prompt);
		return call(settings, "POST", "/api/projects/" + encode(settings.project()) + "/sessions", body)
			.thenApply(json -> json.getAsJsonObject().get("id").getAsString());
	}

	static CompletableFuture<Void> name(Settings settings, String sessionId, String name) {
		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		return call(settings, "PUT", "/api/sessions/" + sessionId + "/name", body).thenApply(ignored -> null);
	}

	/** Typed into Claude once its prompt box is free; a session that had stopped is taken back up first. */
	static CompletableFuture<Void> prompt(Settings settings, String sessionId, String text) {
		JsonObject body = new JsonObject();
		body.addProperty("text", text);
		return call(settings, "POST", "/api/sessions/" + sessionId + "/prompt", body).thenApply(ignored -> null);
	}

	/**
	 * What the session said and did since {@code cursor}; null for none, which reads from now on. Read as {@code player}
	 * in Minecraft, so paude shows them as here for as long as they keep reading.
	 */
	static CompletableFuture<Feed.Page> feed(Settings settings, String sessionId, String player, String cursor) {
		String query = "?label=Minecraft&name=" + encode(player) + (cursor == null ? "" : "&after=" + encode(cursor));
		return call(settings, "GET", "/api/sessions/" + sessionId + "/feed" + query, null)
			.thenApply(json -> Feed.of(json.getAsJsonObject()));
	}

	/** working, waiting (on someone), ready, or stopped. */
	static CompletableFuture<String> status(Settings settings, String sessionId) {
		return call(settings, "GET", "/api/sessions/" + sessionId, null)
			.thenApply(json -> json.getAsJsonObject().get("status").getAsString());
	}

	/** @return what Claude is asking, or null for nothing */
	static CompletableFuture<Asking> asking(Settings settings, String sessionId) {
		return call(settings, "GET", "/api/sessions/" + sessionId + "/asking", null).thenApply(json -> {
			if (!(json.getAsJsonObject().get("asking") instanceof JsonObject asked)) return null;
			List<String> details = new ArrayList<>();
			if (asked.get("details") != null && asked.get("details").isJsonArray())
				asked.getAsJsonArray("details").forEach(line -> details.add(line.getAsString()));
			List<Option> options = new ArrayList<>();
			asked.getAsJsonArray("options").forEach(option -> options.add(new Option(
				option.getAsJsonObject().get("key").getAsString(), option.getAsJsonObject().get("label").getAsString())));
			return new Asking(text(asked, "title"), details, text(asked, "question"), options);
		});
	}

	/** Refused (409) when the screen no longer asks that, so an answer never lands on the next question. */
	static CompletableFuture<Void> answer(Settings settings, String sessionId, String question, String key) {
		JsonObject body = new JsonObject();
		body.addProperty("question", question);
		body.addProperty("key", key);
		return call(settings, "POST", "/api/sessions/" + sessionId + "/answer", body).thenApply(ignored -> null);
	}

	/** A link that opens the session in a browser and may type into it, for a day. */
	static CompletableFuture<String> invite(Settings settings, String sessionId, String name) {
		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		body.addProperty("role", "drive");
		body.addProperty("hours", 24);
		return call(settings, "POST", "/api/sessions/" + sessionId + "/invites", body)
			.thenApply(json -> settings.browserUrl() + "/#/join/" + json.getAsJsonObject().get("token").getAsString());
	}

	private static CompletableFuture<JsonElement> call(Settings settings, String method, String path, JsonObject body) {
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder(URI.create(settings.url() + path))
				.timeout(Duration.ofSeconds(15))
				.header("authorization", "Bearer " + settings.bearer())
				.header("content-type", "application/json")
				.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
		} catch (IOException e) {
			return CompletableFuture.failedFuture(new Refused("The paude token does not read: " + e.getMessage()));
		}
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
			.handle((response, error) -> {
				if (error != null) throw new Refused("paude is not answering at " + settings.url());
				if (response.statusCode() >= 400)
					throw new Refused("paude said " + response.statusCode() + ": " + response.body().strip());
				return response.body().isBlank() ? null : JsonParser.parseString(response.body());
			});
	}

	private static String text(JsonObject object, String key) {
		return object.get(key) != null && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
	}

	private static String encode(String part) {
		return URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
