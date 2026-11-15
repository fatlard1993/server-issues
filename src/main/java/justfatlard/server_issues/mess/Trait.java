package justfatlard.server_issues.mess;

/**
 * What one block state is to the scan, decided once per state. Plain data, so the patterns can be
 * found and tested without a world.
 *
 * @param open nothing to stand on or attach to: air, grass, flowers, a fluid's way through
 * @param junk carried in bulk and dropped carelessly: dirt, cobblestone, netherrack, sand, gravel, webs
 * @param keepsFire fire on top of it burns for good and is meant to: netherrack, magma, soul soil
 * @param marker only a builder puts it there: a door, a bed, stairs, a torch, anything holding contents
 * @param natural grows or generates in shapes that look placed: trees, cactus, bamboo, dripstone
 * @param foreign far commoner here than in the ground around, so it was brought
 * @param named the reporter's words name it
 */
public record Trait(String id, String name, boolean open, Fluid fluid, boolean source, boolean junk, boolean tnt,
		boolean fire, boolean keepsFire, boolean marker, boolean natural, boolean foreign, boolean named) {

	public enum Fluid { NONE, WATER, LAVA }

	/** Taken by the scan's own patterns when one of them matches. */
	boolean suspect() {
		return junk || foreign || named;
	}

	boolean solidBlock() {
		return !open && fluid == Fluid.NONE && !fire;
	}
}
