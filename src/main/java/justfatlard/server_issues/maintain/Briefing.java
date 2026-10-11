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
 * The first prompt of a maintainer's session: what Claude is for, what it may touch, how this server
 * is built and deployed, and the reports it is about. The bundled one, unless the server keeps its
 * own in {@code config/server-issues-briefing.md}.
 */
final class Briefing {
	private Briefing() {}

	static final Path OWN = FabricLoader.getInstance().getConfigDir().resolve("server-issues-briefing.md");

	private static final Gson GSON = new Gson();

	static String forReports(String maintainer, List<IssueLog.Report> reports) {
		String about = reports.isEmpty()
			? "No report in particular: " + maintainer + " will say what this session is about."
			: (reports.size() == 1 ? "The report this session is about:" : "The reports this session is about:")
				+ "\n\n" + String.join("\n", reports.stream().map(GSON::toJson).toList());
		return template()
			.replace("{maintainer}", maintainer)
			.replace("{server}", FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().toString())
			.replace("{reports}", about);
	}

	private static String template() {
		try {
			if (Files.exists(OWN)) return Files.readString(OWN, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Main.LOGGER.warn("[{}] {} does not read, so the bundled briefing is used: {}", Main.MOD_ID, OWN, e.getMessage());
		}
		try (InputStream bundled = Briefing.class.getResourceAsStream("/maintain-briefing.md")) {
			return new String(bundled.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException | NullPointerException e) {
			throw new IllegalStateException("The bundled briefing is missing from the jar", e);
		}
	}
}
