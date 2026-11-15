package justfatlard.server_issues.mess;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shapes mess and grief leave, found in a {@link Grid}. A match is cleaned only when nothing about it
 * says a builder meant it: no door, bed, stairs, torch or chest within reach, made of what players drop
 * carelessly or what does not belong here. Whatever looks wrong but might be someone's work is left, with
 * where it is, for a person or Claude to judge.
 */
public final class Patterns {
	static final int SHORTEST_PILLAR = 3;
	/** When the reporter says pillar or tower, a stub of two counts too. */
	static final int SHORTEST_NAMED_PILLAR = 2;
	static final int LARGEST_FLOATING = 64;
	static final int LARGEST_SPLOTCH = 64;
	/** A block the reporter named but nothing else marks as mess is cleaned only in specks this small. */
	static final int LARGEST_NAMED_SPECK = 4;
	static final int MOST_SPILL_SOURCES = 8;
	static final int LARGEST_SPILL = 512;
	static final int MOST_CLEANED = 4096;
	static final int BUILD_REACH = 2;
	/** Half a clump must face open air: natural blobs sit buried in stone, dropped blocks sit out in the open. */
	static final double LEAST_EXPOSED = 0.5;

	private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
	private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
	private static final int[][] AROUND = around();

	private final Grid grid;
	private final boolean pillarsNamed;
	private final boolean[] taken;
	private final boolean[] pointedAt;
	private final List<Findings.Group> cleaned = new ArrayList<>();
	private final List<Findings.Group> left = new ArrayList<>();
	private int cleanedCount;

	private Patterns(Grid grid, boolean pillarsNamed) {
		this.grid = grid;
		this.pillarsNamed = pillarsNamed;
		this.taken = new boolean[grid.size()];
		this.pointedAt = new boolean[grid.size()];
	}

	/** @param pillarsNamed the reporter spoke of pillars or towers */
	public static Findings find(Grid grid, boolean pillarsNamed) {
		Patterns patterns = new Patterns(grid, pillarsNamed);
		patterns.tnt();
		patterns.pillars();
		patterns.spills();
		patterns.floating();
		patterns.splotches();
		// Cleaning a splotch can leave what stood on it hanging, and the fire that burned on it.
		patterns.floating();
		patterns.fire();
		return new Findings(List.copyOf(patterns.cleaned), List.copyOf(patterns.left));
	}

	private void fire() {
		List<Integer> burning = new ArrayList<>();
		for (int i = 0; i < grid.size(); i++) {
			Trait trait = grid.at(i);
			if (trait == null || !trait.fire()) continue;
			int x = grid.x(i), y = grid.y(i), z = grid.z(i);
			Trait below = grid.at(x, y - 1, z);
			if (below != null && below.keepsFire() && !taken[grid.index(x, y - 1, z)]) continue;
			burning.add(i);
		}
		if (!burning.isEmpty()) take(new Findings.Group("fire", "fires", cells(burning)));
	}

	private void tnt() {
		List<Integer> loose = new ArrayList<>();
		List<Integer> wired = new ArrayList<>();
		for (int i = 0; i < grid.size(); i++) {
			Trait trait = grid.at(i);
			if (trait == null || !trait.tnt()) continue;
			(nearBuild(List.of(i)) ? wired : loose).add(i);
		}
		if (!loose.isEmpty()) take(new Findings.Group("TNT", "TNT", cells(loose)));
		if (!wired.isEmpty()) leave(new Findings.Group("TNT beside redstone or a build", "TNT beside redstone or a build", cells(wired)));
	}

	/** One block wide, nothing beside it on any side, standing three or more high: towering up and leaving it. */
	private void pillars() {
		int shortest = pillarsNamed ? SHORTEST_NAMED_PILLAR : SHORTEST_PILLAR;
		for (int x = 0; x < grid.sx; x++) {
			for (int z = 0; z < grid.sz; z++) {
				List<Integer> run = new ArrayList<>();
				for (int y = 0; y <= grid.sy; y++) {
					if (y < grid.sy && pillarPart(x, y, z)) {
						run.add(grid.index(x, y, z));
						continue;
					}
					if (run.size() >= shortest) {
						String name = commonest(run);
						Findings.Group pillar = new Findings.Group("a " + name + " pillar", name + " pillars", cells(run));
						if (nearBuild(run)) leave(new Findings.Group("a " + name + " pillar by a build", name + " pillars by builds", pillar.cells()));
						else take(pillar);
					}
					run.clear();
				}
			}
		}
	}

	private boolean pillarPart(int x, int y, int z) {
		int i = grid.index(x, y, z);
		Trait trait = grid.at(i);
		if (trait == null || taken[i] || !trait.solidBlock() || trait.marker() || trait.natural() || !trait.suspect()) return false;
		for (int[] side : SIDES) {
			int nx = x + side[0], nz = z + side[2];
			if (!grid.inside(nx, y, nz) || !empty(grid.index(nx, y, nz))) return false;
		}
		return true;
	}

	/**
	 * Lava or water poured out, not pooled: a few sources with most of it flowing away from them. Natural
	 * springs look the same, so a spill is cleaned only when that fluid does not belong here or was named.
	 */
	private void spills() {
		boolean[] seen = new boolean[grid.size()];
		for (int start = 0; start < grid.size(); start++) {
			Trait first = grid.at(start);
			if (seen[start] || first == null || first.fluid() == Trait.Fluid.NONE) continue;
			Trait.Fluid fluid = first.fluid();
			Body body = flood(start, seen, LARGEST_SPILL, i -> {
				Trait trait = grid.at(i);
				return trait != null && trait.fluid() == fluid;
			}, FACES);
			if (body.edge || body.overflowed) continue;
			int sources = 0;
			boolean foreign = false, named = false;
			for (int i : body.cells) {
				Trait trait = grid.at(i);
				if (trait.source()) sources++;
				foreign |= trait.foreign();
				named |= trait.named();
			}
			int flowing = body.cells.size() - sources;
			if (sources > MOST_SPILL_SOURCES || flowing < 2 * Math.max(1, sources)) continue;
			String what = fluid == Trait.Fluid.LAVA ? "lava" : "water";
			Findings.Group group = new Findings.Group("a " + what + " spill", what + " spills", cells(body.cells));
			if (foreign || named) take(group);
			else leave(new Findings.Group("a " + what + " fall from " + sources + (sources == 1 ? " source" : " sources")
				+ " that could be a natural spring", what + " falls that could be natural springs", group.cells()));
		}
	}

	/**
	 * Patches of a block that does not belong here, or that the reporter named, sitting out in the open.
	 * Junk is cleaned up to a size; anything else foreign is only pointed at, since a build is foreign too.
	 */
	private void splotches() {
		boolean[] seen = new boolean[grid.size()];
		for (int start = 0; start < grid.size(); start++) {
			Trait first = grid.at(start);
			if (seen[start] || taken[start] || first == null || !first.solidBlock() || first.marker() || first.natural()
				|| !(first.foreign() || first.named())) continue;
			String id = first.id();
			Body body = flood(start, seen, Integer.MAX_VALUE, i -> {
				Trait trait = grid.at(i);
				return trait != null && !taken[i] && trait.id().equals(id);
			}, AROUND);
			if (exposed(body.cells) < LEAST_EXPOSED) continue;
			String name = first.name();
			int size = body.cells.size();
			if (nearBuild(body.cells)) {
				leave(new Findings.Group(size + " " + name + " next to a build", name + " next to builds", cells(body.cells)));
			} else if ((first.junk() && size <= LARGEST_SPLOTCH) || (first.named() && size <= LARGEST_NAMED_SPECK)) {
				take(new Findings.Group("a " + name + " splotch", name + " splotches", cells(body.cells)));
			} else {
				String why = first.foreign() ? " that does not belong here" : " the report names";
				leave(new Findings.Group(size + " " + name + why, "patches of " + name, cells(body.cells)));
			}
		}
	}

	/** A small clump touching nothing: left hanging after towering down, or after what held it was cleaned. */
	private void floating() {
		boolean[] seen = new boolean[grid.size()];
		for (int start = 0; start < grid.size(); start++) {
			if (seen[start] || empty(start) || grid.at(start) == null) continue;
			Body body = flood(start, seen, LARGEST_FLOATING, i -> grid.at(i) != null && !empty(i), FACES);
			if (body.edge || body.overflowed || pointedAt[start]) continue;
			boolean natural = false, marker = false, suspect = true;
			for (int i : body.cells) {
				Trait trait = grid.at(i);
				natural |= trait.natural();
				marker |= trait.marker();
				suspect &= trait.suspect();
			}
			if (natural) continue;
			if (marker) leave(new Findings.Group("a floating build", "floating builds", cells(body.cells)));
			else if (suspect) take(new Findings.Group("a floating clump", "floating clumps", cells(body.cells)));
			else leave(new Findings.Group("a floating clump of " + names(body.cells), "floating clumps", cells(body.cells)));
		}
	}

	private void take(Findings.Group group) {
		if (cleanedCount + group.cells().length > MOST_CLEANED) {
			leave(new Findings.Group(group.one() + ", more than one report cleans", group.many() + ", more than one report cleans", group.cells()));
			return;
		}
		for (int i : group.cells()) taken[i] = true;
		cleanedCount += group.cells().length;
		cleaned.add(group);
	}

	private void leave(Findings.Group group) {
		for (int i : group.cells()) pointedAt[i] = true;
		left.add(group);
	}

	/** Nothing there to touch: open, fluid, fire, or already taken. Unknown cells are not empty. */
	private boolean empty(int i) {
		Trait trait = grid.at(i);
		if (trait == null) return false;
		return taken[i] || trait.open() && !trait.marker() || trait.fluid() != Trait.Fluid.NONE || trait.fire();
	}

	private boolean nearBuild(List<Integer> cells) {
		for (int i : cells) {
			int x = grid.x(i), y = grid.y(i), z = grid.z(i);
			for (int dx = -BUILD_REACH; dx <= BUILD_REACH; dx++)
				for (int dy = -BUILD_REACH; dy <= BUILD_REACH; dy++)
					for (int dz = -BUILD_REACH; dz <= BUILD_REACH; dz++) {
						Trait trait = grid.at(x + dx, y + dy, z + dz);
						if (trait != null && trait.marker()) return true;
					}
		}
		return false;
	}

	private double exposed(List<Integer> cells) {
		int open = 0;
		for (int i : cells) {
			int x = grid.x(i), y = grid.y(i), z = grid.z(i);
			for (int[] face : FACES) {
				int nx = x + face[0], ny = y + face[1], nz = z + face[2];
				if (grid.inside(nx, ny, nz) && empty(grid.index(nx, ny, nz))) {
					open++;
					break;
				}
			}
		}
		return (double) open / cells.size();
	}

	private record Body(List<Integer> cells, boolean edge, boolean overflowed) {}

	/**
	 * The connected cells from {@code start} that {@code joins}, marking each seen. Past {@code largest} it
	 * stops keeping cells but still walks the rest, so a big body is seen once and not once a cell.
	 */
	private Body flood(int start, boolean[] seen, int largest, java.util.function.IntPredicate joins, int[][] steps) {
		List<Integer> cells = new ArrayList<>();
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		boolean edge = false;
		int count = 0;
		seen[start] = true;
		queue.add(start);
		while (!queue.isEmpty()) {
			int i = queue.poll();
			if (++count <= largest) cells.add(i);
			int x = grid.x(i), y = grid.y(i), z = grid.z(i);
			for (int[] step : steps) {
				int nx = x + step[0], ny = y + step[1], nz = z + step[2];
				if (!grid.inside(nx, ny, nz)) {
					edge = true;
					continue;
				}
				int next = grid.index(nx, ny, nz);
				if (grid.at(next) == null) edge = true;
				if (seen[next] || !joins.test(next)) continue;
				seen[next] = true;
				queue.add(next);
			}
		}
		return new Body(cells, edge, count > largest);
	}

	private String commonest(List<Integer> cells) {
		Map<String, Integer> counts = new HashMap<>();
		for (int i : cells) counts.merge(grid.at(i).name(), 1, Integer::sum);
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
	}

	private String names(List<Integer> cells) {
		Set<String> names = new LinkedHashSet<>();
		cells.stream().map(i -> grid.at(i).name()).sorted(Comparator.naturalOrder()).forEach(names::add);
		return String.join(", ", names);
	}

	private static int[] cells(List<Integer> list) {
		return list.stream().mapToInt(Integer::intValue).toArray();
	}

	private static int[][] around() {
		List<int[]> steps = new ArrayList<>();
		for (int dx = -1; dx <= 1; dx++)
			for (int dy = -1; dy <= 1; dy++)
				for (int dz = -1; dz <= 1; dz++)
					if (dx != 0 || dy != 0 || dz != 0) steps.add(new int[] {dx, dy, dz});
		return steps.toArray(int[][]::new);
	}
}
