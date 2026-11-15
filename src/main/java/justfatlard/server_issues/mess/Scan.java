package justfatlard.server_issues.mess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * The world around a mess report read into a {@link Grid}, with what belongs here judged from a sparse
 * sample of the ground further out: a block common close by and rare out there was brought.
 */
public final class Scan {
	static final int REACH = 16;
	static final int BELOW = 12;
	static final int ABOVE = 24;
	static final int SAMPLED_REACH = 48;
	static final int SAMPLE_STEP_ACROSS = 3;
	static final int SAMPLE_STEP_UP = 2;
	/** Fewer cells sampled than this and nothing can be called foreign: too little ground was loaded to compare. */
	static final int FEWEST_SAMPLED = 2_000;
	/** Foreign when this many times commoner close by than out there. */
	static final int FOREIGN_RATIO = 20;
	static final int FEWEST_FOREIGN = 3;

	private static final Set<Block> JUNK = Set.of(Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK,
		Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK, Blocks.GRAVEL, Blocks.SAND,
		Blocks.RED_SAND, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.COBWEB);
	private static final List<TagKey<Block>> MARKER_TAGS = List.of(BlockTags.DOORS, BlockTags.TRAPDOORS, BlockTags.RAILS,
		BlockTags.BEDS, BlockTags.FENCES, BlockTags.FENCE_GATES, BlockTags.BUTTONS, BlockTags.PRESSURE_PLATES,
		BlockTags.ALL_SIGNS, BlockTags.BANNERS, BlockTags.CANDLES, BlockTags.PORTALS, BlockTags.STAIRS, BlockTags.SLABS,
		BlockTags.WALLS, BlockTags.CAMPFIRES);
	private static final List<String> MARKER_WORDS = List.of("glass", "torch", "lantern", "ladder", "redstone", "repeater",
		"comparator", "lever", "crafting_table", "carpet", "chain", "bars", "scaffolding");
	private static final List<TagKey<Block>> FIRE_KEEPERS = List.of(BlockTags.INFINIBURN_OVERWORLD,
		BlockTags.INFINIBURN_NETHER, BlockTags.INFINIBURN_END, BlockTags.SOUL_FIRE_BASE_BLOCKS);
	private static final List<String> NATURAL_WORDS = List.of("mushroom_block", "mushroom_stem", "bamboo", "cactus",
		"sugar_cane", "dripstone", "kelp", "chorus", "vine", "basalt", "amethyst", "coral", "sculk");
	private static final Pattern PILLAR_WORDS = Pattern.compile("\\b(pillars?|towers?|towering|poles?|columns?)\\b");

	/** The box read, where its corner sits in the world, and the state behind each trait. */
	public record Result(ServerLevel level, BlockPos corner, Grid grid, List<BlockState> states, Findings findings,
			List<Count> commonest, int sampled) {
		public BlockPos at(int cell) {
			return corner.offset(grid.x(cell), grid.y(cell), grid.z(cell));
		}
	}

	/** How much of a block there is close by, and how often it turns up in the ground around. */
	public record Count(String id, int near, double sampledShare, boolean foreign, boolean named) {}

	public static Result around(ServerLevel level, BlockPos at, String description) {
		int bottom = Math.max(level.getMinY(), at.getY() - BELOW);
		int top = Math.min(level.getMaxY(), at.getY() + ABOVE);
		int sx = 2 * REACH + 1, sy = top - bottom + 1, sz = 2 * REACH + 1;
		BlockPos corner = new BlockPos(at.getX() - REACH, bottom, at.getZ() - REACH);

		Map<BlockState, Integer> typeOf = new IdentityHashMap<>();
		List<BlockState> states = new ArrayList<>();
		List<BlockPos> firstSeen = new ArrayList<>();
		Map<Block, Integer> near = new IdentityHashMap<>();
		int[] cells = new int[sx * sy * sz];
		int read = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = 0; x < sx; x++) {
			for (int z = 0; z < sz; z++) {
				boolean loaded = level.isLoaded(pos.set(corner.getX() + x, bottom, corner.getZ() + z));
				for (int y = 0; y < sy; y++) {
					int cell = (y * sz + z) * sx + x;
					if (!loaded) {
						cells[cell] = Grid.UNKNOWN;
						continue;
					}
					pos.set(corner.getX() + x, bottom + y, corner.getZ() + z);
					BlockState state = level.getBlockState(pos);
					Integer type = typeOf.get(state);
					if (type == null) {
						type = states.size();
						typeOf.put(state, type);
						states.add(state);
						firstSeen.add(pos.immutable());
					}
					cells[cell] = type;
					near.merge(state.getBlock(), 1, Integer::sum);
					read++;
				}
			}
		}

		Map<Block, Integer> sampled = new HashMap<>();
		int sampledCells = 0;
		for (int dx = -SAMPLED_REACH; dx <= SAMPLED_REACH; dx += SAMPLE_STEP_ACROSS) {
			for (int dz = -SAMPLED_REACH; dz <= SAMPLED_REACH; dz += SAMPLE_STEP_ACROSS) {
				if (Math.abs(dx) <= REACH && Math.abs(dz) <= REACH) continue;
				if (!level.isLoaded(pos.set(at.getX() + dx, bottom, at.getZ() + dz))) continue;
				for (int y = bottom; y <= top; y += SAMPLE_STEP_UP) {
					sampled.merge(level.getBlockState(pos.set(at.getX() + dx, y, at.getZ() + dz)).getBlock(), 1, Integer::sum);
					sampledCells++;
				}
			}
		}

		String words = description.toLowerCase(Locale.ROOT);
		Map<Block, Count> counts = new IdentityHashMap<>();
		for (Map.Entry<Block, Integer> entry : near.entrySet()) {
			Block block = entry.getKey();
			double nearShare = (double) entry.getValue() / Math.max(1, read);
			double sampledShare = sampledCells == 0 ? 0 : (double) sampled.getOrDefault(block, 0) / sampledCells;
			boolean foreign = sampledCells >= FEWEST_SAMPLED && entry.getValue() >= FEWEST_FOREIGN
				&& sampledShare * FOREIGN_RATIO < nearShare;
			counts.put(block, new Count(id(block), entry.getValue(), sampledShare, foreign, named(block, words)));
		}

		Trait[] traits = new Trait[states.size()];
		for (int type = 0; type < states.size(); type++) {
			traits[type] = trait(level, firstSeen.get(type), states.get(type), counts.get(states.get(type).getBlock()));
		}
		Grid grid = new Grid(sx, sy, sz, cells, traits);
		Findings findings = Patterns.find(grid, PILLAR_WORDS.matcher(words).find());
		List<Count> commonest = counts.values().stream()
			.filter(count -> !count.id().endsWith("air"))
			.sorted((a, b) -> Integer.compare(b.near(), a.near()))
			.limit(24)
			.toList();
		return new Result(level, corner, grid, List.copyOf(states), findings, commonest, sampledCells);
	}

	private static Trait trait(ServerLevel level, BlockPos at, BlockState state, Count count) {
		String id = id(state.getBlock());
		FluidState fluidState = state.getFluidState();
		Trait.Fluid fluid = !(state.getBlock() instanceof LiquidBlock) ? Trait.Fluid.NONE
			: fluidState.is(FluidTags.LAVA) ? Trait.Fluid.LAVA
			: fluidState.is(FluidTags.WATER) ? Trait.Fluid.WATER : Trait.Fluid.NONE;
		boolean fire = state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
		boolean marker = state.hasBlockEntity() || MARKER_TAGS.stream().anyMatch(state::is)
			|| MARKER_WORDS.stream().anyMatch(id::contains);
		boolean natural = state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || NATURAL_WORDS.stream().anyMatch(id::contains);
		boolean open = state.isAir() || state.canBeReplaced() || state.getCollisionShape(level, at).isEmpty();
		return new Trait(id, state.getBlock().getName().getString().toLowerCase(Locale.ROOT), open, fluid,
			fluid != Trait.Fluid.NONE && fluidState.isSource(), JUNK.contains(state.getBlock()), state.is(Blocks.TNT), fire,
			FIRE_KEEPERS.stream().anyMatch(state::is), marker, natural, count.foreign(), count.named());
	}

	/**
	 * The reporter's words name a block by its id or its name, as a whole word, so "cobblestone" does not
	 * name stone. A few words players use for the thing and not the block count too.
	 */
	private static boolean named(Block block, String words) {
		String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
		String spoken = id.replace('_', ' ');
		String name = block.getName().getString().toLowerCase(Locale.ROOT);
		if (says(words, spoken) || says(words, name)) return true;
		return switch (id) {
			case "cobblestone", "cobbled_deepslate", "mossy_cobblestone" -> says(words, "cobble");
			case "water" -> says(words, "flood") || says(words, "flooded") || says(words, "flooding");
			default -> false;
		};
	}

	private static boolean says(String words, String phrase) {
		return Pattern.compile("\\b" + Pattern.quote(phrase) + "s?\\b").matcher(words).find();
	}

	static String id(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}
}
