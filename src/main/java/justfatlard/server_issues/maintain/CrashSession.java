package justfatlard.server_issues.maintain;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import justfatlard.server_issues.Main;

/**
 * A Claude session about a crash the server came back from. A crash within the hour of the last one
 * goes to the session already on it, so a crash loop is one investigation and not ten.
 */
public final class CrashSession {
	private CrashSession() {}

	static final String STARTED_BY = "the server";
	private static final long SAME_TROUBLE_MINUTES = 60;
	/** The server can come up before paude does, after a reboot. */
	private static final int TRIES = 5;
	private static final long RETRY_SECONDS = 30;

	public static void open(Settings settings, Settings.OnCrash leeway, String what) {
		Optional<Desk.Started> ongoing = ongoing();
		tell(settings, leeway, ongoing, what, 1).whenComplete((done, error) -> {
			if (error != null) Main.LOGGER.error("[{}] The crash went to no session; its bug report has it: {}", Main.MOD_ID,
				error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
		});
	}

	private static CompletableFuture<Void> tell(Settings settings, Settings.OnCrash leeway, Optional<Desk.Started> ongoing,
			String what, int attempt) {
		CompletableFuture<Void> told = ongoing.isPresent()
			? Paude.prompt(settings, ongoing.get().id(), "The server crashed again. " + what)
				.thenRun(() -> Main.LOGGER.info("[{}] Crashed again: told session {}", Main.MOD_ID, ongoing.get().id()))
			: Paude.start(settings, Briefing.forCrash(leeway, what)).thenAccept(id -> {
				String at = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
				Desk.recordStart(new Desk.Started(id, STARTED_BY, "a crash", at));
				Paude.name(settings, id, "Crash: " + at.substring(0, 16).replace('T', ' '));
				Main.LOGGER.info("[{}] Crashed: session {} is looking into it", Main.MOD_ID, id);
			});
		if (attempt >= TRIES) return told;
		return told.handle((done, error) -> error == null
				? CompletableFuture.<Void>completedFuture(null)
				: CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(RETRY_SECONDS, TimeUnit.SECONDS))
					.thenCompose(waited -> tell(settings, leeway, ongoing, what, attempt + 1)))
			.thenCompose(next -> next);
	}

	/** The newest crash session, if it began within the hour. */
	private static Optional<Desk.Started> ongoing() {
		OffsetDateTime since = OffsetDateTime.now().minusMinutes(SAME_TROUBLE_MINUTES);
		return Desk.started().reversed().stream()
			.filter(started -> STARTED_BY.equals(started.startedBy()))
			.findFirst()
			.filter(started -> OffsetDateTime.parse(started.at()).isAfter(since));
	}
}
