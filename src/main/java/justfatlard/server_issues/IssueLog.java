package justfatlard.server_issues;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The file the reports go in: one JSON object a line, appended and never rewritten, in the
 * server's own folder beside its logs. A line is a whole report, so a file cut short by a crash
 * loses at most the one being written, and it reads in any editor as well as in a script.
 */
public final class IssueLog {
	private IssueLog() {}

	public static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("server-issues.jsonl");

	private static final Gson GSON = new Gson();

	/** The number the next report gets; counted from the file once, at start. */
	private static int next = -1;

	public record Report(int number, String time, String kind, String player, String uuid,
			String dimension, int x, int y, int z, String lookingAt, String message) {}

	/** Number it, stamp it and write it down. @return the number, or -1 if the file would not take it */
	public static synchronized int add(String kind, String player, String uuid, String dimension,
			int x, int y, int z, String lookingAt, String message) {
		if (next < 0) next = all().size() + 1;
		Report report = new Report(next, OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
			kind, player, uuid, dimension, x, y, z, lookingAt, message);
		try {
			Files.writeString(FILE, GSON.toJson(report) + "\n", StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			Main.LOGGER.error("[{}] Could not save report: {}", Main.MOD_ID, e.getMessage());
			return -1;
		}
		return next++;
	}

	/** Every report in the file, oldest first. A line that does not read is skipped, not fatal. */
	public static synchronized List<Report> all() {
		List<Report> reports = new ArrayList<>();
		if (!Files.exists(FILE)) return reports;
		try {
			for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
				if (line.isBlank()) continue;
				try {
					JsonObject json = JsonParser.parseString(line).getAsJsonObject();
					reports.add(GSON.fromJson(json, Report.class));
				} catch (RuntimeException skipped) {
					Main.LOGGER.warn("[{}] Skipped a line it could not read in {}", Main.MOD_ID, FILE.getFileName());
				}
			}
		} catch (IOException e) {
			Main.LOGGER.error("[{}] Could not read {}: {}", Main.MOD_ID, FILE, e.getMessage());
		}
		return reports;
	}
}
