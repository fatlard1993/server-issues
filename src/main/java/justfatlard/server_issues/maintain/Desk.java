package justfatlard.server_issues.maintain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import justfatlard.server_issues.Main;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who is unlocked, which session each maintainer sits at, and the relay that carries Claude's side
 * of it into their chat. A seat outlasts a disconnect, so the restart a redeploy causes drops the
 * maintainer back where they were, with what Claude said meanwhile, once they unlock again.
 */
final class Desk {
	private Desk() {}

	private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("server-issues-sessions.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final long POLL_MILLIS = 2_000;
	/** Wrong passphrases allowed per name in a window, so guessing is slower than asking an admin. */
	private static final int MISSES_ALLOWED = 5;
	private static final long MISS_WINDOW_MILLIS = 10 * 60_000;
	/** What a maintainer coming back is shown of what they missed; the browser has all of it. */
	private static final int CATCH_UP_LINES = 12;

	/** A session this mod started: the only ones a maintainer can reach from the game. */
	record Started(String id, String startedBy, String about, String at) {}

	/** Where a maintainer sits, and how far into its feed they have been shown; a null cursor means from now. */
	record Seat(String id, String cursor) {}

	private record Saved(List<Started> sessions, Map<String, Seat> seats) {}

	private static final class Watch {
		final String sessionId;
		volatile String cursor;
		/** Coming back: what was missed piles up here until the feed is read to its end, then only its tail is shown. */
		List<Component> backlog;
		/** The dialog last shown, so each one is shown once; null when Claude is not asking. */
		volatile String shownDialog;
		volatile Paude.Asking asking;
		final AtomicBoolean polling = new AtomicBoolean();

		Watch(String sessionId, String cursor, boolean catchingUp) {
			this.sessionId = sessionId;
			this.cursor = cursor;
			this.backlog = catchingUp ? new ArrayList<>() : null;
		}
	}

	private static MinecraftServer server;
	private static ScheduledExecutorService poller;
	private static final Set<UUID> unlocked = ConcurrentHashMap.newKeySet();
	private static final Map<String, Deque<Long>> misses = new ConcurrentHashMap<>();
	private static final Map<UUID, Watch> watches = new ConcurrentHashMap<>();
	private static Saved saved;

	static synchronized void open(MinecraftServer running) {
		server = running;
		saved = load();
		poller = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "server-issues relay");
			thread.setDaemon(true);
			return thread;
		});
		poller.scheduleWithFixedDelay(Desk::poll, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
	}

	static synchronized void close() {
		for (UUID player : List.copyOf(watches.keySet())) leaveSeatFor(player);
		if (poller != null) poller.shutdownNow();
		unlocked.clear();
	}

	static boolean isUnlocked(ServerPlayer player) {
		return unlocked.contains(player.getUUID());
	}

	enum Unlock { OPENED, WRONG, TOO_MANY }

	static Unlock unlock(Settings settings, ServerPlayer player, String passphrase) {
		String name = player.getGameProfile().name().toLowerCase(Locale.ROOT);
		Deque<Long> recent = misses.computeIfAbsent(name, key -> new ArrayDeque<>());
		long now = System.currentTimeMillis();
		synchronized (recent) {
			while (!recent.isEmpty() && now - recent.peekFirst() > MISS_WINDOW_MILLIS) recent.pollFirst();
			if (recent.size() >= MISSES_ALLOWED) return Unlock.TOO_MANY;
			if (!settings.passphraseMatches(name, passphrase)) {
				recent.addLast(now);
				Main.LOGGER.warn("[{}] Wrong maintainer passphrase for {} ({} in ten minutes)", Main.MOD_ID, name, recent.size());
				return Unlock.WRONG;
			}
			recent.clear();
		}
		unlocked.add(player.getUUID());
		Main.LOGGER.info("[{}] {} unlocked maintaining", Main.MOD_ID, name);
		Seat seat = seatOf(player.getUUID());
		if (seat != null) sit(player.getUUID(), seat.id(), seat.cursor(), true);
		return Unlock.OPENED;
	}

	/** Gone from the server: locked again, and the seat kept with where they had read to. */
	static void disconnected(UUID player) {
		unlocked.remove(player);
		leaveSeatFor(player);
	}

	static synchronized List<Started> started() {
		return List.copyOf(saved.sessions);
	}

	static synchronized void recordStart(Started started) {
		saved.sessions.add(started);
		save();
	}

	/** A recorded session by the start of its id. */
	static synchronized Optional<Started> find(String idPrefix) {
		List<Started> matching = saved.sessions.stream().filter(started -> started.id().startsWith(idPrefix)).toList();
		return matching.size() == 1 ? Optional.of(matching.getFirst()) : Optional.empty();
	}

	static String currentSession(UUID player) {
		Watch watch = watches.get(player);
		return watch == null ? null : watch.sessionId;
	}

	/** Relay this session to the player from {@code cursor}: "0" for a new one, null to start from now. */
	static void sit(UUID player, String sessionId, String cursor, boolean catchingUp) {
		watches.put(player, new Watch(sessionId, cursor, catchingUp));
		synchronized (Desk.class) {
			saved.seats.put(player.toString(), new Seat(sessionId, cursor));
			save();
		}
	}

	static void stand(UUID player) {
		watches.remove(player);
		synchronized (Desk.class) {
			saved.seats.remove(player.toString());
			save();
		}
	}

	static Paude.Asking askingOf(UUID player) {
		Watch watch = watches.get(player);
		return watch == null ? null : watch.asking;
	}

	/** Shown again on the next poll if Claude is still asking, so a refused answer is not a dead end. */
	static void answered(UUID player) {
		Watch watch = watches.get(player);
		if (watch != null) {
			watch.asking = null;
			watch.shownDialog = null;
		}
	}

	static void tell(UUID player, Component message) {
		MinecraftServer running = server;
		if (running == null) return;
		running.execute(() -> {
			ServerPlayer online = running.getPlayerList().getPlayer(player);
			if (online != null) online.sendSystemMessage(message);
		});
	}

	private static Seat seatOf(UUID player) {
		synchronized (Desk.class) {
			return saved.seats.get(player.toString());
		}
	}

	private static void leaveSeatFor(UUID player) {
		Watch watch = watches.remove(player);
		if (watch == null) return;
		synchronized (Desk.class) {
			saved.seats.put(player.toString(), new Seat(watch.sessionId, watch.cursor));
			save();
		}
	}

	private static void poll() {
		Settings settings = Settings.get();
		if (settings == null) return;
		for (Map.Entry<UUID, Watch> entry : watches.entrySet()) {
			UUID player = entry.getKey();
			Watch watch = entry.getValue();
			if (!watch.polling.compareAndSet(false, true)) continue;
			Paude.feed(settings, watch.sessionId, watch.cursor)
				.thenAccept(page -> relay(player, watch, page))
				.thenCompose(relayed -> Paude.status(settings, watch.sessionId))
				.thenCompose(status -> "waiting".equals(status)
					? Paude.asking(settings, watch.sessionId)
					: CompletableFuture.completedFuture(null))
				.whenComplete((asking, error) -> {
					watch.polling.set(false);
					if (error != null) return;
					watch.asking = asking;
					if (asking == null) {
						watch.shownDialog = null;
						return;
					}
					String dialog = asking.title() + "\n" + String.join("\n", asking.details()) + "\n" + asking.question();
					if (dialog.equals(watch.shownDialog)) return;
					watch.shownDialog = dialog;
					showDialog(player, asking);
				});
		}
	}

	private static void relay(UUID player, Watch watch, Feed.Page page) {
		watch.cursor = page.cursor();
		if (watch.backlog == null) {
			page.lines().forEach(line -> tell(player, line));
			return;
		}
		watch.backlog.addAll(page.lines());
		if (page.more()) return;
		List<Component> missed = watch.backlog;
		watch.backlog = null;
		int skipped = Math.max(0, missed.size() - CATCH_UP_LINES);
		if (skipped > 0) tell(player, Component.literal("(" + skipped + " earlier lines: /maintain link)").withStyle(ChatFormatting.DARK_GRAY));
		missed.subList(skipped, missed.size()).forEach(line -> tell(player, line));
	}

	private static void showDialog(UUID player, Paude.Asking asking) {
		MutableComponent shown = Component.literal("Claude asks").withStyle(ChatFormatting.GOLD);
		if (!asking.title().isEmpty()) shown.append(Component.literal(" (" + asking.title() + ")").withStyle(ChatFormatting.GOLD));
		shown.append(Component.literal(":"));
		for (String detail : asking.details().subList(0, Math.min(4, asking.details().size())))
			shown.append(Component.literal("\n  " + detail).withStyle(ChatFormatting.GRAY));
		shown.append(Component.literal("\n" + asking.question()).withStyle(ChatFormatting.YELLOW));
		for (Paude.Option option : asking.options()) {
			shown.append(Component.literal("\n  [" + option.key() + "] " + option.label())
				.withStyle(style -> style.withColor(ChatFormatting.GREEN)
					.withClickEvent(new ClickEvent.RunCommand("/maintain answer " + option.key()))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Answer " + option.key())))));
		}
		tell(player, shown);
	}

	private static Saved load() {
		try {
			if (Files.exists(FILE)) {
				Saved read = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Saved.class);
				if (read != null) return new Saved(new ArrayList<>(read.sessions() == null ? List.of() : read.sessions()),
					new HashMap<>(read.seats() == null ? Map.of() : read.seats()));
			}
		} catch (IOException | RuntimeException e) {
			Main.LOGGER.error("[{}] {} does not read; starting with no sessions: {}", Main.MOD_ID, FILE, e.getMessage());
		}
		return new Saved(new ArrayList<>(), new HashMap<>());
	}

	private static void save() {
		try {
			Files.writeString(FILE, GSON.toJson(saved), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Main.LOGGER.error("[{}] Could not save {}: {}", Main.MOD_ID, FILE, e.getMessage());
		}
	}
}
