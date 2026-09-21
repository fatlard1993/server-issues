package justfatlard.server_issues.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

/**
 * This mod loads, its mixins bind, and a world comes up with it installed.
 *
 * <p>Written out rather than sharing Pandorical's harness, because this mod does not depend on
 * Pandorical and a test that dragged it in would be testing the mod beside a mod it never ships
 * with. The same few lines, run as this mod actually runs.
 */
public final class SmokeTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!FabricLoader.getInstance().isModLoaded("server-issues")) {
			throw new AssertionError("server-issues is not loaded: the test is testing nothing");
		}

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			// Long enough for anything that registers on join to have thrown if it was going to.
			context.waitTicks(60);
		}
		// Closing a world still coming out of a pause deadlocks the harness's own tick lock.
		context.waitTicks(10);
	}
}
