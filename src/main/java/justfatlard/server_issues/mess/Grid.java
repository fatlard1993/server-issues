package justfatlard.server_issues.mess;

/**
 * A box of the world read once: each cell holds the index of its {@link Trait}, or {@link #UNKNOWN}
 * where the chunk was not loaded. Unknown cells count as solid and as the box's edge, so nothing that
 * reaches into them is taken for a small thing on its own.
 */
public final class Grid {
	public static final int UNKNOWN = -1;

	final int sx, sy, sz;
	private final int[] cells;
	private final Trait[] traits;

	public Grid(int sx, int sy, int sz, int[] cells, Trait[] traits) {
		if (cells.length != sx * sy * sz) throw new IllegalArgumentException("cells do not fill the box");
		this.sx = sx;
		this.sy = sy;
		this.sz = sz;
		this.cells = cells;
		this.traits = traits;
	}

	public int size() {
		return cells.length;
	}

	public int index(int x, int y, int z) {
		return (y * sz + z) * sx + x;
	}

	public int x(int i) {
		return i % sx;
	}

	public int y(int i) {
		return i / (sx * sz);
	}

	public int z(int i) {
		return (i / sx) % sz;
	}

	public boolean inside(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
	}

	/** @return the cell's trait, or null when it is unknown */
	public Trait at(int i) {
		int type = cells[i];
		return type == UNKNOWN ? null : traits[type];
	}

	/** @return the trait there, or null when it is unknown or outside the box */
	public Trait at(int x, int y, int z) {
		return inside(x, y, z) ? at(index(x, y, z)) : null;
	}
}
