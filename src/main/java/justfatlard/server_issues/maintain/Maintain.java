package justfatlard.server_issues.maintain;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import justfatlard.server_issues.IssueLog;
import justfatlard.server_issues.Main;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /maintain} and {@code /claude}: a maintainer starts or joins a Claude session in paude about
 * players' reports, and talks with it from chat. Only players named in the settings see the commands,
 * and they do nothing until the player unlocks them with their passphrase, once per visit.
 */
public final class Maintain {
	private Maintain() {}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> register(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(Desk::open);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> Desk.close());
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Desk.disconnected(handler.getPlayer().getUUID()));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			Settings settings = Settings.get();
			if (settings != null && settings.maintains(player.getGameProfile().name()))
				player.sendSystemMessage(Component.literal("Maintaining: /maintain unlock <passphrase>").withStyle(ChatFormatting.GRAY));
		});
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("maintain")
			.requires(Maintain::maintainer)
			.then(Commands.literal("unlock")
				.then(Commands.argument("passphrase", StringArgumentType.greedyString())
					.executes(Maintain::unlock)))
			.then(Commands.literal("start")
				.executes(context -> start(context, ""))
				.then(Commands.argument("reports", StringArgumentType.greedyString())
					.executes(context -> start(context, StringArgumentType.getString(context, "reports")))))
			.then(Commands.literal("sessions").executes(Maintain::sessions))
			.then(Commands.literal("join")
				.then(Commands.argument("session", StringArgumentType.word()).executes(Maintain::join)))
			.then(Commands.literal("leave").executes(Maintain::leave))
			.then(Commands.literal("link").executes(Maintain::link))
			.then(Commands.literal("answer")
				.then(Commands.argument("key", StringArgumentType.word()).executes(Maintain::answer))));
		dispatcher.register(Commands.literal("claude")
			.requires(Maintain::maintainer)
			.then(Commands.argument("message", StringArgumentType.greedyString()).executes(Maintain::say)));
	}

	private static boolean maintainer(CommandSourceStack source) {
		Settings settings = Settings.get();
		ServerPlayer player = source.getPlayer();
		return settings != null && player != null && settings.maintains(player.getGameProfile().name());
	}

	private static int unlock(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayer();
		Settings settings = Settings.get();
		if (player == null || settings == null) return 0;
		switch (Desk.unlock(settings, player, StringArgumentType.getString(context, "passphrase"))) {
			case WRONG -> {
				context.getSource().sendFailure(Component.literal("That is not the passphrase."));
				return 0;
			}
			case TOO_MANY -> {
				context.getSource().sendFailure(Component.literal("Too many wrong tries. Wait ten minutes."));
				return 0;
			}
			case OPENED -> {}
		}
		String seat = Desk.currentSession(player.getUUID());
		context.getSource().sendSuccess(() -> Component.literal(seat != null
			? "Unlocked until you leave. Back at session " + shortId(seat) + "."
			: "Unlocked until you leave. /maintain start <report numbers> or /maintain sessions.")
			.withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	private static int start(CommandContext<CommandSourceStack> context, String asked) {
		ServerPlayer player = unlockedPlayer(context);
		if (player == null) return 0;
		Settings settings = Settings.get();

		List<Integer> numbers = new ArrayList<>();
		for (String part : asked.split("[\\s,#]+")) {
			if (part.isEmpty()) continue;
			try {
				numbers.add(Integer.parseInt(part));
			} catch (NumberFormatException e) {
				context.getSource().sendFailure(Component.literal("Report numbers only, like /maintain start 12 14."));
				return 0;
			}
		}
		List<IssueLog.Report> all = IssueLog.all();
		List<IssueLog.Report> reports = new ArrayList<>();
		for (int number : numbers) {
			Optional<IssueLog.Report> found = all.stream().filter(report -> report.number() == number).findFirst();
			if (found.isEmpty()) {
				context.getSource().sendFailure(Component.literal("There is no report #" + number + "."));
				return 0;
			}
			reports.add(found.get());
		}

		String name = player.getGameProfile().name();
		String about = numbers.isEmpty() ? "anything" : String.join(" ", numbers.stream().map(n -> "#" + n).toList());
		context.getSource().sendSuccess(() -> Component.literal("Starting a session about " + about + "...")
			.withStyle(ChatFormatting.GRAY), false);

		Paude.start(settings, Briefing.forReports(name, reports)).whenComplete((id, error) -> {
			if (error != null) {
				fail(player, error);
				return;
			}
			Desk.recordStart(new Desk.Started(id, name, about,
				OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()));
			Desk.sit(player, id, "0", false);
			Paude.name(settings, id, "In game: " + about + " (" + name + ")");
			Main.LOGGER.info("[{}] {} started maintainer session {} about {}", Main.MOD_ID, name, id, about);
			MutableComponent started = Component.literal("Session " + shortId(id)
				+ " started. Claude is reading up; its replies land here. Talk to it with /claude <message>.")
				.withStyle(ChatFormatting.GREEN);
			if (settings.browserUrl() != null) started.append(Component.literal(" ")).append(browserButton());
			Desk.tell(player.getUUID(), started);
		});
		return 1;
	}

	private static int sessions(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = unlockedPlayer(context);
		if (player == null) return 0;
		Settings settings = Settings.get();
		List<Desk.Started> started = Desk.started();
		if (started.isEmpty()) {
			context.getSource().sendSuccess(() -> Component.literal("No sessions yet: /maintain start <report numbers>."), false);
			return 0;
		}
		List<Desk.Started> latest = started.subList(Math.max(0, started.size() - 8), started.size());
		String here = Desk.currentSession(player.getUUID());
		List<CompletableFuture<String>> statuses = latest.stream()
			.map(session -> Paude.status(settings, session.id()).exceptionally(error -> "gone")).toList();
		CompletableFuture.allOf(statuses.toArray(CompletableFuture[]::new)).whenComplete((done, error) -> {
			MutableComponent list = Component.literal("Sessions, latest last:");
			for (int i = 0; i < latest.size(); i++) {
				Desk.Started session = latest.get(i);
				String status = statuses.get(i).join();
				list.append(Component.literal("\n" + shortId(session.id()) + " " + session.about() + " by "
					+ session.startedBy() + ", " + session.at().replace('T', ' ') + ", " + status + " "));
				list.append(session.id().equals(here)
					? Component.literal("(here)").withStyle(ChatFormatting.GRAY)
					: Component.literal("[join]").withStyle(style -> style.withColor(ChatFormatting.GREEN)
						.withClickEvent(new ClickEvent.RunCommand("/maintain join " + shortId(session.id())))));
			}
			Desk.tell(player.getUUID(), list);
		});
		return latest.size();
	}

	private static int join(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = unlockedPlayer(context);
		if (player == null) return 0;
		Optional<Desk.Started> found = Desk.find(StringArgumentType.getString(context, "session"));
		if (found.isEmpty()) {
			context.getSource().sendFailure(Component.literal("No one session starts with that. /maintain sessions lists them."));
			return 0;
		}
		String id = found.get().id();
		Desk.sit(player, id, null, false);
		context.getSource().sendSuccess(() -> Component.literal("At session " + shortId(id) + " (" + found.get().about()
			+ "). New replies land here; /maintain link for what came before.").withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	private static int leave(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = unlockedPlayer(context);
		if (player == null) return 0;
		Desk.stand(player.getUUID());
		context.getSource().sendSuccess(() -> Component.literal("Left the session. It carries on in paude."), false);
		return 1;
	}

	private static int link(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = seatedPlayer(context);
		if (player == null) return 0;
		Settings settings = Settings.get();
		if (settings.browserUrl() == null) {
			context.getSource().sendFailure(Component.literal("No browser address set: paude.browserUrl in config/server-issues.json."));
			return 0;
		}
		Paude.invite(settings, Desk.currentSession(player.getUUID()), player.getGameProfile().name())
			.whenComplete((link, error) -> {
				if (error != null) {
					fail(player, error);
					return;
				}
				Desk.tell(player.getUUID(), Component.literal("[Open the session in a browser]")
					.withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
						.withClickEvent(new ClickEvent.OpenUrl(URI.create(link)))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(
							"Good for a day. Whoever has this link can type into Claude, so keep it to yourself.")))));
			});
		return 1;
	}

	private static int answer(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = seatedPlayer(context);
		if (player == null) return 0;
		Paude.Asking asking = Desk.askingOf(player.getUUID());
		String key = StringArgumentType.getString(context, "key");
		if (asking == null || asking.options().stream().noneMatch(option -> option.key().equals(key))) {
			context.getSource().sendFailure(Component.literal("Claude is not asking that now."));
			return 0;
		}
		Paude.answer(Settings.get(), Desk.currentSession(player.getUUID()), asking.question(), key)
			.whenComplete((done, error) -> {
				Desk.answered(player.getUUID());
				if (error != null) fail(player, error);
			});
		return 1;
	}

	private static int say(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = seatedPlayer(context);
		if (player == null) return 0;
		String message = StringArgumentType.getString(context, "message").trim();
		String name = player.getGameProfile().name();
		context.getSource().sendSuccess(() -> Component.literal("> " + message).withStyle(ChatFormatting.GRAY), false);
		Paude.prompt(Settings.get(), Desk.currentSession(player.getUUID()), name + ": " + message)
			.whenComplete((done, error) -> {
				if (error != null) fail(player, error);
			});
		return 1;
	}

	private static ServerPlayer unlockedPlayer(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayer();
		if (player == null || Settings.get() == null) return null;
		if (!Desk.isUnlocked(player)) {
			context.getSource().sendFailure(Component.literal("Unlock first: /maintain unlock <passphrase>"));
			return null;
		}
		return player;
	}

	private static ServerPlayer seatedPlayer(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = unlockedPlayer(context);
		if (player != null && Desk.currentSession(player.getUUID()) == null) {
			context.getSource().sendFailure(Component.literal("Not at a session: /maintain start, or /maintain sessions to join one."));
			return null;
		}
		return player;
	}

	private static Component browserButton() {
		return Component.literal("[browser]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
			.withClickEvent(new ClickEvent.RunCommand("/maintain link"))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("A link to this session in paude"))));
	}

	private static void fail(ServerPlayer player, Throwable error) {
		Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null ? error.getCause() : error;
		String message = cause instanceof Paude.Refused ? cause.getMessage() : "Something went wrong reaching paude.";
		if (!(cause instanceof Paude.Refused)) Main.LOGGER.error("[{}] Maintainer call failed", Main.MOD_ID, cause);
		Desk.tell(player.getUUID(), Component.literal(message).withStyle(ChatFormatting.RED));
	}

	private static String shortId(String id) {
		return id.substring(0, Math.min(8, id.length()));
	}
}
