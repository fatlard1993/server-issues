package justfatlard.server_issues;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.Gson;
import justfatlard.server_issues.maintain.CrashSession;
import justfatlard.server_issues.maintain.Settings;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * A crash written down as a bug report, and with paude set up, handed to a Claude session. A dying
 * server cannot be trusted to say so, and systemd starts it again seconds later, so this looks as the
 * mod loads, before the world does: crash reports written during the run before, and whether that run
 * shut down at all, which a soft crash does and a hard death (out of memory, killed) does not.
 */
public final class Crashes {
	private Crashes() {}

	private static final Path SERVER = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
	private static final Path REPORTS = SERVER.resolve("crash-reports");
	private static final Path LAST_RUN = SERVER.resolve("server-issues-run.json");
	private static final Gson GSON = new Gson();
	private static final String REPORTED_BY = "the server";
	private static final int LONGEST_CAUSE = 160;

	/** {@code stopped} stays null until the run shuts down. */
	private record Run(String started, String stopped) {}

	static void init() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> stopped());
		Settings.OnCrash onCrash = Settings.onCrash();
		if (onCrash == Settings.OnCrash.OFF) {
			forget();
			return;
		}
		Run previous = read();
		write(new Run(Instant.now().toString(), null));
		if (previous == null || previous.started() == null) return;

		List<Path> fresh = writtenSince(Instant.parse(previous.started()));
		boolean diedHard = previous.stopped() == null;
		if (fresh.isEmpty() && !diedHard) return;

		List<String> said = new ArrayList<>();
		for (Path report : fresh) {
			int number = report("Crashed: " + cause(report) + " (crash-reports/" + report.getFileName() + ")");
			said.add(numbered(number) + "crash report " + report + ".");
		}
		if (fresh.isEmpty()) {
			int number = report("Stopped without shutting down or writing a crash report: killed, out of memory,"
				+ " or the JVM itself died. journalctl --user -u minecraft-server has its end.");
			said.add(numbered(number) + "the run before this start ended without shutting down or writing a crash"
				+ " report, which is what being killed (the kernel's out-of-memory killer, a kill -9) or the JVM itself dying"
				+ " looks like. The end of `journalctl --user -u minecraft-server`, `journalctl -k` and any hs_err_pid file in "
				+ SERVER + " say which.");
		}

		Settings settings = Settings.get();
		if (settings != null && (onCrash == Settings.OnCrash.INVESTIGATE || onCrash == Settings.OnCrash.FIX))
			CrashSession.open(settings, onCrash, String.join(" ", said));
	}

	private static void stopped() {
		Run run = read();
		if (run != null) write(new Run(run.started(), Instant.now().toString()));
	}

	private static String numbered(int number) {
		return number < 0 ? "(The bug report did not save.) " : "Bug report #" + number + ": ";
	}

	private static int report(String message) {
		int number = IssueLog.add("bug", REPORTED_BY, "", "", 0, 0, 0, "", message);
		Main.LOGGER.info("[{}] #{} bug from {}: {}", Main.MOD_ID, number, REPORTED_BY, message);
		return number;
	}

	/** The crash reports Minecraft wrote after {@code since}, oldest first: it names them by the time, year first. */
	private static List<Path> writtenSince(Instant since) {
		if (!Files.isDirectory(REPORTS)) return List.of();
		try (Stream<Path> files = Files.list(REPORTS)) {
			return files.filter(file -> file.getFileName().toString().startsWith("crash-"))
				.filter(file -> {
					try {
						return Files.getLastModifiedTime(file).toInstant().isAfter(since);
					} catch (IOException e) {
						return false;
					}
				})
				.sorted()
				.toList();
		} catch (IOException e) {
			Main.LOGGER.warn("[{}] {} does not read: {}", Main.MOD_ID, REPORTS, e.getMessage());
			return List.of();
		}
	}

	/** The line under a crash report's description: the throwable, as in {@code java.lang.Error: Watchdog}. */
	private static String cause(Path report) {
		try (Stream<String> lines = Files.lines(report, StandardCharsets.UTF_8)) {
			List<String> head = lines.limit(12).toList();
			for (int i = 0; i < head.size(); i++) {
				if (!head.get(i).startsWith("Description:")) continue;
				String cause = head.stream().skip(i + 1).filter(line -> !line.isBlank()).findFirst().orElse("").strip();
				return cause.length() > LONGEST_CAUSE ? cause.substring(0, LONGEST_CAUSE) + "..." : cause;
			}
		} catch (IOException | RuntimeException e) {
			Main.LOGGER.warn("[{}] {} does not read: {}", Main.MOD_ID, report, e.getMessage());
		}
		return "see the crash report";
	}

	private static Run read() {
		try {
			if (Files.exists(LAST_RUN)) {
				Run run = GSON.fromJson(Files.readString(LAST_RUN, StandardCharsets.UTF_8), Run.class);
				if (run != null && run.started() != null) Instant.parse(run.started());
				return run;
			}
		} catch (IOException | RuntimeException e) {
			Main.LOGGER.warn("[{}] {} does not read, so the last run is taken as unknown: {}", Main.MOD_ID, LAST_RUN, e.getMessage());
		}
		return null;
	}

	private static void write(Run run) {
		try {
			Files.writeString(LAST_RUN, GSON.toJson(run), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Main.LOGGER.warn("[{}] Could not write {}, so the next start cannot tell how this run ended: {}", Main.MOD_ID, LAST_RUN, e.getMessage());
		}
	}

	/** Off: a run noted while it was on would read as one that never shut down, the day it is on again. */
	private static void forget() {
		try {
			Files.deleteIfExists(LAST_RUN);
		} catch (IOException e) {
			Main.LOGGER.warn("[{}] Could not remove {}: {}", Main.MOD_ID, LAST_RUN, e.getMessage());
		}
	}
}
