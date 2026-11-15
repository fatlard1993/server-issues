package justfatlard.server_issues.mess;

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
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import justfatlard.server_issues.Main;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Every block a mess report cleaned, written down before it goes, so {@code /issues undo} can put it back.
 * One file a report, one line a cleaning, appended like the report log. Blocks holding contents (chests,
 * signs, anything with a block entity) are never cleaned, so what is written is all there was.
 */
public final class Cleanup {
	private Cleanup() {}

	public static final Path FOLDER = FabricLoader.getInstance().getGameDir().resolve("server-issues-messes");

	private static final Gson GSON = new Gson();

	private record Removed(int x, int y, int z, String was) {}

	private record Cleaning(String time, String by, String why, List<Removed> blocks) {}

	private record Undoing(String undone, String by) {}

	public record Undone(int restored, int occupied) {}

	public static Path file(int report) {
		return FOLDER.resolve(report + ".jsonl");
	}

	/** Write down, then clear. @return how many were cleared, or -1 when nothing could be written and nothing was touched */
	public static synchronized int clear(ServerLevel level, List<BlockPos> positions, int report, String by, String why) {
		List<Removed> removed = new ArrayList<>();
		List<BlockPos> clearing = new ArrayList<>();
		for (BlockPos pos : positions) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || state.hasBlockEntity()) continue;
			removed.add(new Removed(pos.getX(), pos.getY(), pos.getZ(), BlockStateParser.serialize(state)));
			clearing.add(pos);
		}
		if (removed.isEmpty()) return 0;
		if (!append(report, new Cleaning(now(), by, why, removed))) return -1;
		BlockState air = Blocks.AIR.defaultBlockState();
		for (BlockPos pos : clearing) level.setBlock(pos, air, Block.UPDATE_ALL);
		return clearing.size();
	}

	/**
	 * Puts back everything cleaned for the report since it was last undone, newest first, wherever it is
	 * still empty: something built there since stays. @return null when there was nothing to undo
	 */
	public static synchronized Undone undo(ServerLevel level, int report, String by) {
		List<Cleaning> since = new ArrayList<>();
		Path file = file(report);
		if (!Files.exists(file)) return null;
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (line.isBlank()) continue;
				JsonObject json = JsonParser.parseString(line).getAsJsonObject();
				if (json.has("undone")) since.clear();
				else since.add(GSON.fromJson(json, Cleaning.class));
			}
		} catch (IOException | RuntimeException e) {
			Main.LOGGER.error("[{}] {} does not read, so nothing was put back: {}", Main.MOD_ID, file, e.getMessage());
			return new Undone(0, 0);
		}
		if (since.isEmpty()) return null;
		if (!append(report, new Undoing(now(), by))) return new Undone(0, 0);

		int restored = 0, occupied = 0;
		for (Cleaning cleaning : since.reversed()) {
			for (Removed block : cleaning.blocks().reversed()) {
				BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
				if (!level.getBlockState(pos).isAir()) {
					occupied++;
					continue;
				}
				try {
					BlockState was = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, block.was(), false).blockState();
					level.setBlock(pos, was, Block.UPDATE_ALL);
					restored++;
				} catch (CommandSyntaxException e) {
					Main.LOGGER.warn("[{}] {} at {} is not a block any more, so it stays gone", Main.MOD_ID, block.was(), pos);
				}
			}
		}
		return new Undone(restored, occupied);
	}

	private static String now() {
		return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
	}

	private static boolean append(int report, Object line) {
		try {
			Files.createDirectories(FOLDER);
			Files.writeString(file(report), GSON.toJson(line) + "\n", StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			return true;
		} catch (IOException e) {
			Main.LOGGER.error("[{}] Could not save to {}: {}", Main.MOD_ID, file(report), e.getMessage());
			return false;
		}
	}
}
