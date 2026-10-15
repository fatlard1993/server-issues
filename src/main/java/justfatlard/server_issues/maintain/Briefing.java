package justfatlard.server_issues.maintain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.Gson;
import justfatlard.server_issues.IssueLog;
import justfatlard.server_issues.Main;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The first prompt of a session: what Claude is for, what it may touch, how this server is built and
 * deployed, and the reports or the crash it is about. The bundled ones, unless the server keeps its own
 * in {@code config/server-issues-briefing.md} and {@code config/server-issues-crash-briefing.md}.
 */
final class Briefing {
	private Briefing() {}

	static final Path OWN = FabricLoader.getInstance().getConfigDir().resolve("server-issues-briefing.md");
	static final Path OWN_CRASH = FabricLoader.getInstance().getConfigDir().resolve("server-issues-crash-briefing.md");

	private static final String MAY_FIX = "You may fix it and restart the server with the fix without waiting for anyone,"
		+ " when the cause is plain, it lies in one mod of the suite, and the fix is small. When any of that is less than sure,"
		+ " stop at saying what you found and what you would do.";
	private static final String ONLY_LOOK = "Find out what happened and stop there: change nothing, stage nothing and restart"
		+ " nothing until a maintainer joins this session and says go.";

	private static final Gson GSON = new Gson();

	static String forReports(String maintainer, List<IssueLog.Report> reports) {
		String about = reports.isEmpty()
			? "No report in particular: " + maintainer + " will say what this session is about."
			: (reports.size() == 1 ? "The report this session is about:" : "The reports this session is about:")
				+ "\n\n" + String.join("\n", reports.stream().map(GSON::toJson).toList());
		return template(OWN, "/maintain-briefing.md")
			.replace("{maintainer}", maintainer)
			.replace("{server}", server())
			.replace("{reports}", about);
	}

	static String forCrash(Settings.OnCrash leeway, String crash) {
		return template(OWN_CRASH, "/maintain-crash-briefing.md")
			.replace("{server}", server())
			.replace("{crash}", crash)
			.replace("{leeway}", leeway == Settings.OnCrash.FIX ? MAY_FIX : ONLY_LOOK);
	}

	private static String server() {
		return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().toString();
	}

	private static String template(Path own, String bundledName) {
		try {
			if (Files.exists(own)) return Files.readString(own, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Main.LOGGER.warn("[{}] {} does not read, so the bundled briefing is used: {}", Main.MOD_ID, own, e.getMessage());
		}
		try (InputStream bundled = Briefing.class.getResourceAsStream(bundledName)) {
			return new String(bundled.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException | NullPointerException e) {
			throw new IllegalStateException("The bundled briefing is missing from the jar", e);
		}
	}
}
