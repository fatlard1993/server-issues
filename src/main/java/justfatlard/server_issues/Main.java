package justfatlard.server_issues;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import justfatlard.server_issues.maintain.Maintain;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /bug} and {@code /idea}: something went wrong, or something could be better, said where
 * it happened and kept for the admins. {@code /issues} reads the latest open ones back, and
 * closes or reopens one, for ops.
 */
public class Main implements ModInitializer {
	public static final String MOD_ID = "server-issues";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** A report every few seconds at most, per player, so a held key does not fill the file. */
	private static final long COOLDOWN_MILLIS = 5_000;
	/** Chat's own limit: nothing longer can be typed anyway. */
	private static final int LONGEST = 256;
	/** How far a player's eyes reach for "what were you looking at". */
	private static final double LOOK_REACH = 8.0;
	private static final int SHOWN_BY_DEFAULT = 5;

	private static final Map<UUID, Long> lastSent = new ConcurrentHashMap<>();

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> register(dispatcher));
		Maintain.init();
		Crashes.init();
		LOGGER.info("[{}] Reports go to {}", MOD_ID, IssueLog.FILE.toAbsolutePath());
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("bug")
			.then(Commands.argument("what happened", StringArgumentType.greedyString())
				.executes(context -> submit(context, "bug"))));
		dispatcher.register(Commands.literal("idea")
			.then(Commands.argument("what you would like", StringArgumentType.greedyString())
				.executes(context -> submit(context, "idea"))));
		dispatcher.register(Commands.literal("issues")
			.requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
			.executes(context -> list(context.getSource(), SHOWN_BY_DEFAULT))
			.then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
				.executes(context -> list(context.getSource(), IntegerArgumentType.getInteger(context, "count"))))
			.then(Commands.literal("close")
				.then(Commands.argument("number", IntegerArgumentType.integer(1))
					.executes(context -> close(context, ""))
					.then(Commands.argument("why", StringArgumentType.greedyString())
						.executes(context -> close(context, StringArgumentType.getString(context, "why").trim())))))
			.then(Commands.literal("reopen")
				.then(Commands.argument("number", IntegerArgumentType.integer(1))
					.executes(Main::reopen))));
	}

	private static int submit(CommandContext<CommandSourceStack> context, String kind) {
		CommandSourceStack source = context.getSource();
		String message = context.getArgument(kind.equals("bug") ? "what happened" : "what you would like", String.class).trim();
		if (message.length() > LONGEST) message = message.substring(0, LONGEST);

		ServerPlayer player = source.getPlayer();
		long now = System.currentTimeMillis();
		if (player != null) {
			Long last = lastSent.get(player.getUUID());
			if (last != null && now - last < COOLDOWN_MILLIS) {
				source.sendFailure(Component.literal("Give it a few seconds before sending another."));
				return 0;
			}
		}

		BlockPos at = BlockPos.containing(source.getPosition());
		int number = IssueLog.add(kind,
			player != null ? player.getGameProfile().name() : source.getTextName(),
			player != null ? player.getUUID().toString() : "",
			source.getLevel().dimension().identifier().toString(),
			at.getX(), at.getY(), at.getZ(), lookingAt(player), message);
		if (number < 0) {
			source.sendFailure(Component.literal("That did not save. Tell an admin another way, please."));
			return 0;
		}

		if (player != null) lastSent.put(player.getUUID(), now);
		LOGGER.info("[{}] #{} {} from {}: {}", MOD_ID, number, kind, source.getTextName(), message);
		String what = kind.equals("bug") ? "Bug" : "Idea";
		source.sendSuccess(() -> Component.literal("Thanks! " + what + " #" + number + " saved for the admins.")
			.withStyle(ChatFormatting.GREEN), false);
		return number;
	}

	/** The block in front of the player, for a bug that is about one; empty when there is none. */
	private static String lookingAt(ServerPlayer player) {
		if (player == null) return "";
		HitResult hit = player.pick(LOOK_REACH, 0F, false);
		if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return "";
		return BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(block.getBlockPos()).getBlock()).toString();
	}

	private static int list(CommandSourceStack source, int count) {
		IssueLog.Snapshot snapshot = IssueLog.read();
		List<IssueLog.Report> open = snapshot.open();
		if (snapshot.reports().isEmpty()) {
			source.sendSuccess(() -> Component.literal("No reports yet."), false);
			return 0;
		}
		if (open.isEmpty()) {
			source.sendSuccess(() -> Component.literal("Nothing open; all " + snapshot.reports().size() + " reports are closed."), false);
			return 0;
		}
		List<IssueLog.Report> latest = open.subList(Math.max(0, open.size() - count), open.size());
		source.sendSuccess(() -> Component.literal(open.size() + " open of " + snapshot.reports().size()
			+ " reports, latest " + latest.size() + ":"), false);
		for (IssueLog.Report report : latest) {
			source.sendSuccess(() -> Component.literal("#" + report.number() + " " + report.time().replace('T', ' ')
				+ " " + report.kind() + " from " + report.player() + ": " + report.message()), false);
		}
		return latest.size();
	}

	private static int close(CommandContext<CommandSourceStack> context, String why) {
		CommandSourceStack source = context.getSource();
		int number = IntegerArgumentType.getInteger(context, "number");
		if (why.length() > LONGEST) why = why.substring(0, LONGEST);
		IssueLog.Change change = IssueLog.close(number, source.getTextName(), why);
		if (change == IssueLog.Change.DONE) {
			LOGGER.info("[{}] #{} closed by {}{}", MOD_ID, number, source.getTextName(), why.isEmpty() ? "" : ": " + why);
		}
		return answer(source, number, change, "closed", "is already closed");
	}

	private static int reopen(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		int number = IntegerArgumentType.getInteger(context, "number");
		IssueLog.Change change = IssueLog.reopen(number, source.getTextName());
		if (change == IssueLog.Change.DONE) LOGGER.info("[{}] #{} reopened by {}", MOD_ID, number, source.getTextName());
		return answer(source, number, change, "reopened", "is not closed");
	}

	private static int answer(CommandSourceStack source, int number, IssueLog.Change change, String done, String already) {
		switch (change) {
			case DONE -> {
				source.sendSuccess(() -> Component.literal("#" + number + " " + done + ".").withStyle(ChatFormatting.GREEN), true);
				return 1;
			}
			case NO_SUCH_REPORT -> source.sendFailure(Component.literal("There is no report #" + number + "."));
			case ALREADY -> source.sendFailure(Component.literal("#" + number + " " + already + "."));
			case NOT_SAVED -> source.sendFailure(Component.literal("That did not save; #" + number + " is unchanged."));
		}
		return 0;
	}
}
