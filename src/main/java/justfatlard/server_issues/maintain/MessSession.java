package justfatlard.server_issues.maintain;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import justfatlard.server_issues.Main;

/**
 * A Claude session about a mess report the mod could not settle by itself. Mess reports within the hour
 * of the last session's start go to that session, so one griefing spree is one cleanup, and players
 * cannot start sessions faster than one an hour.
 */
public final class MessSession {
	private MessSession() {}

	static final String ABOUT = "a mess";
	private static final long SAME_TROUBLE_MINUTES = 60;

	/** A session being started, until it is on the desk where {@link #ongoing} finds it. */
	private static CompletableFuture<String> starting;

	public static synchronized void open(Settings settings, Settings.OnMess leeway, int number, String reporter, String what) {
		Optional<String> ongoing = ongoing().map(Desk.Started::id);
		CompletableFuture<String> session;
		if (ongoing.isPresent()) {
			session = CompletableFuture.completedFuture(ongoing.get());
		} else if (starting != null && !starting.isDone()) {
			session = starting;
		} else {
			starting = Paude.start(settings, Briefing.forMess(leeway, what)).thenApply(id -> {
				String at = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
				Desk.recordStart(new Desk.Started(id, reporter, ABOUT, at));
				Paude.name(settings, id, "Mess #" + number);
				Main.LOGGER.info("[{}] #{}: session {} is looking at the mess", Main.MOD_ID, number, id);
				return id;
			});
			watch(starting, number);
			return;
		}
		watch(session.thenCompose(id -> Paude.prompt(settings, id, "Another mess report. " + what)
			.thenApply(done -> {
				Main.LOGGER.info("[{}] #{} went to mess session {}", Main.MOD_ID, number, id);
				return id;
			})), number);
	}

	private static void watch(CompletableFuture<String> told, int number) {
		told.whenComplete((done, error) -> {
			if (error != null) Main.LOGGER.error("[{}] #{} went to no session; its report has it: {}", Main.MOD_ID, number,
				error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
		});
	}

	/** The newest mess session, if it began within the hour. */
	private static Optional<Desk.Started> ongoing() {
		OffsetDateTime since = OffsetDateTime.now().minusMinutes(SAME_TROUBLE_MINUTES);
		return Desk.started().reversed().stream()
			.filter(started -> ABOUT.equals(started.about()))
			.findFirst()
			.filter(started -> OffsetDateTime.parse(started.at()).isAfter(since));
	}
}
