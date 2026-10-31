package justfatlard.server_issues;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The file the reports go in: one JSON object a line, appended and never rewritten, in the
 * server's own folder beside its logs. A line is a whole report, or a report closed or reopened,
 * so a file cut short by a crash loses at most the one being written, and it reads in any editor
 * as well as in a script.
 */
public final class IssueLog {
	private IssueLog() {}

	public static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("server-issues.jsonl");

	private static final Gson GSON = new Gson();

	/** The number the next report gets; counted from the file once, at start. */
	private static int next = -1;

	public record Report(int number, String time, String kind, String player, String uuid,
			String dimension, int x, int y, int z, String lookingAt, String message) {}

	public record Closing(int closes, String time, String by, String why) {}

	private record Reopening(int reopens, String time, String by) {}

	/** Every report, oldest first, and the ones closed now with how they were closed. */
	public record Snapshot(List<Report> reports, Map<Integer, Closing> closed) {
		public List<Report> open() {
			return reports.stream().filter(report -> !closed.containsKey(report.number())).toList();
		}
	}

	public enum Change { DONE, NO_SUCH_REPORT, ALREADY, NOT_SAVED }

	/** Number it, stamp it and write it down. @return the number, or -1 if the file would not take it */
	public static synchronized int add(String kind, String player, String uuid, String dimension,
			int x, int y, int z, String lookingAt, String message) {
		if (next < 0) next = all().size() + 1;
		Report report = new Report(next, now(), kind, player, uuid, dimension, x, y, z, lookingAt, message);
		if (!append(report)) return -1;
		return next++;
	}

	public static synchronized Change close(int number, String by, String why) {
		Snapshot snapshot = read();
		if (snapshot.reports().stream().noneMatch(report -> report.number() == number)) return Change.NO_SUCH_REPORT;
		if (snapshot.closed().containsKey(number)) return Change.ALREADY;
		return append(new Closing(number, now(), by, why)) ? Change.DONE : Change.NOT_SAVED;
	}

	public static synchronized Change reopen(int number, String by) {
		Snapshot snapshot = read();
		if (snapshot.reports().stream().noneMatch(report -> report.number() == number)) return Change.NO_SUCH_REPORT;
		if (!snapshot.closed().containsKey(number)) return Change.ALREADY;
		return append(new Reopening(number, now(), by)) ? Change.DONE : Change.NOT_SAVED;
	}

	public static synchronized List<Report> all() {
		return read().reports();
	}

	public static synchronized Snapshot read() {
		List<Report> reports = new ArrayList<>();
		Map<Integer, Closing> closed = new HashMap<>();
		if (!Files.exists(FILE)) return new Snapshot(reports, closed);
		try {
			for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
				if (line.isBlank()) continue;
				try {
					JsonObject json = JsonParser.parseString(line).getAsJsonObject();
					if (json.has("closes")) {
						Closing closing = GSON.fromJson(json, Closing.class);
						closed.put(closing.closes(), closing);
					} else if (json.has("reopens")) {
						closed.remove(json.get("reopens").getAsInt());
					} else {
						reports.add(GSON.fromJson(json, Report.class));
					}
				} catch (RuntimeException skipped) {
					Main.LOGGER.warn("[{}] Skipped a line it could not read in {}", Main.MOD_ID, FILE.getFileName());
				}
			}
		} catch (IOException e) {
			Main.LOGGER.error("[{}] Could not read {}: {}", Main.MOD_ID, FILE, e.getMessage());
		}
		return new Snapshot(reports, closed);
	}

	private static String now() {
		return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
	}

	private static boolean append(Object line) {
		try {
			Files.writeString(FILE, GSON.toJson(line) + "\n", StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			return true;
		} catch (IOException e) {
			Main.LOGGER.error("[{}] Could not save to {}: {}", Main.MOD_ID, FILE.getFileName(), e.getMessage());
			return false;
		}
	}
}
