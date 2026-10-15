package justfatlard.server_issues.maintain;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import justfatlard.server_issues.Main;
import net.fabricmc.loader.api.FabricLoader;

/**
 * {@code config/server-issues.json}: which paude to reach and who may use it. Without a {@code paude}
 * section the whole maintainer side stays off. Read again whenever the file changes, so adding a
 * maintainer needs no restart (they rejoin to see the commands).
 */
public record Settings(URI url, String token, Path tokenFile, String project, String browserUrl,
		Map<String, String> passphrases) {

	public static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("server-issues.json");

	/** The server is offline-mode, so a name is only a claim: the passphrase is what proves it. */
	static final int SHORTEST_PASSPHRASE = 12;

	private static Settings current;
	private static long readModified = Long.MIN_VALUE;

	/**
	 * What a crash leads to: a bug report, and with paude set up a session that looks into it, or one
	 * that may also fix it and restart the server with nobody there to ask.
	 */
	public enum OnCrash { OFF, REPORT, INVESTIGATE, FIX }

	private static OnCrash onCrash = OnCrash.REPORT;

	/** @return the settings, or null when the maintainer side is off */
	public static synchronized Settings get() {
		long modified;
		try {
			modified = Files.exists(FILE) ? Files.getLastModifiedTime(FILE).toMillis() : -1;
		} catch (IOException e) {
			return current;
		}
		if (modified != readModified) {
			readModified = modified;
			onCrash = OnCrash.REPORT;
			current = modified < 0 ? null : read();
		}
		return current;
	}

	/** Read with the rest, but on without paude or a file at all: then a crash is a bug report and nothing more. */
	public static synchronized OnCrash onCrash() {
		get();
		return onCrash;
	}

	public boolean maintains(String player) {
		return passphrases.containsKey(player.toLowerCase(Locale.ROOT));
	}

	public boolean passphraseMatches(String player, String given) {
		String expected = passphrases.get(player.toLowerCase(Locale.ROOT));
		return expected != null && MessageDigest.isEqual(
			expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
	}

	/** The token from the file each time it is wanted, so reinstalling paude needs no restart here. */
	String bearer() throws IOException {
		return token != null ? token : Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
	}

	private static Settings read() {
		JsonObject root;
		try {
			root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			Main.LOGGER.error("[{}] {} does not read, so maintainers are off: {}", Main.MOD_ID, FILE, e.getMessage());
			return null;
		}
		onCrash = onCrashOf(string(root, "onCrash", "report"));
		if (!(root.get("paude") instanceof JsonObject paude)) return null;

		String project = string(paude, "project", "minecraft");

		Map<String, String> passphrases = new HashMap<>();
		if (root.get("maintainers") instanceof JsonObject maintainers) {
			for (Map.Entry<String, JsonElement> entry : maintainers.entrySet()) {
				String passphrase = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : "";
				if (passphrase.length() < SHORTEST_PASSPHRASE) {
					Main.LOGGER.warn("[{}] {} is not a maintainer until their passphrase is {} characters or more",
						Main.MOD_ID, entry.getKey(), SHORTEST_PASSPHRASE);
					continue;
				}
				passphrases.put(entry.getKey().toLowerCase(Locale.ROOT), passphrase);
			}
		}

		String browserUrl = string(paude, "browserUrl", null);
		Settings settings = new Settings(
			URI.create(string(paude, "url", "http://127.0.0.1:8044").replaceAll("/+$", "")),
			string(paude, "token", null),
			path(string(paude, "tokenFile", "~/.paude/local-token")),
			project,
			browserUrl == null ? null : browserUrl.replaceAll("/+$", ""),
			Map.copyOf(passphrases));
		Main.LOGGER.info("[{}] Maintainers reach paude at {} in project {}: {}", Main.MOD_ID, settings.url,
			project, passphrases.isEmpty() ? "nobody yet" : String.join(", ", passphrases.keySet()));
		return settings;
	}

	private static OnCrash onCrashOf(String value) {
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "off" -> OnCrash.OFF;
			case "report" -> OnCrash.REPORT;
			case "investigate" -> OnCrash.INVESTIGATE;
			case "fix" -> OnCrash.FIX;
			default -> {
				Main.LOGGER.warn("[{}] onCrash is off, report, investigate or fix, not \"{}\", so it is report", Main.MOD_ID, value);
				yield OnCrash.REPORT;
			}
		};
	}

	private static String string(JsonObject object, String key, String fallback) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() && !value.getAsString().isBlank() ? value.getAsString() : fallback;
	}

	private static Path path(String value) {
		return value.startsWith("~/") ? Path.of(System.getProperty("user.home"), value.substring(2)) : Path.of(value);
	}
}
