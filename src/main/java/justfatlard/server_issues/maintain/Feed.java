package justfatlard.server_issues.maintain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * What Claude says and does, from paude's feed of a session, as chat. Prompts are left out: the
 * player who sent one already saw it echoed, and the briefing a session opens with is no chat line.
 */
final class Feed {
	private Feed() {}

	/** Chat is a narrow window: a reply longer than this is cut, and the browser has the rest. */
	private static final int LONGEST_REPLY = 700;
	private static final int LONGEST_TOOL_LINE = 90;
	/** Looking around is most of what Claude does, and none of it is news in chat. */
	private static final Set<String> QUIET_TOOLS = Set.of("Read", "Grep", "Glob", "LS", "ToolSearch", "TodoWrite");

	/** One read of the feed: what to show, where the next read starts, and whether more is already waiting. */
	record Page(String cursor, List<Component> lines, boolean more) {}

	static Page of(JsonObject page) {
		List<Component> lines = new ArrayList<>();
		for (JsonElement element : page.getAsJsonArray("entries")) {
			Component line = chatOf(element.getAsJsonObject());
			if (line != null) lines.add(line);
		}
		return new Page(page.get("cursor").getAsString(), lines, page.has("more") && page.get("more").getAsBoolean());
	}

	private static Component chatOf(JsonObject entry) {
		switch (text(entry, "kind")) {
			case "text" -> {
				String said = plain(text(entry, "text"));
				if (said.isEmpty()) return null;
				MutableComponent reply = Component.literal("Claude: ").withStyle(ChatFormatting.AQUA);
				if (said.length() <= LONGEST_REPLY) return reply.append(Component.literal(said).withStyle(ChatFormatting.WHITE));
				return reply.append(Component.literal(said.substring(0, LONGEST_REPLY) + "...").withStyle(ChatFormatting.WHITE))
					.append(Component.literal(" (the rest: /maintain link)").withStyle(ChatFormatting.DARK_GRAY));
			}
			case "tool" -> {
				String tool = text(entry, "name");
				if (QUIET_TOOLS.contains(tool)) return null;
				String what = text(entry, "summary").replaceAll("\\s+", " ").replace(System.getProperty("user.home"), "~");
				String shown = what.isEmpty() ? tool : tool + ": " + what;
				if (shown.length() > LONGEST_TOOL_LINE) shown = shown.substring(0, LONGEST_TOOL_LINE) + "...";
				return Component.literal("  · " + shown).withStyle(ChatFormatting.DARK_GRAY);
			}
			default -> {
				return null;
			}
		}
	}

	/** Markdown as chat cannot draw it: fences, emphasis and heading marks dropped, blank lines closed up. */
	private static String plain(String markdown) {
		return markdown.replaceAll("```[a-zA-Z]*", "")
			.replaceAll("\\*\\*|__|`", "")
			.replaceAll("(?m)^#{1,6}\\s*", "")
			.replaceAll("[ \t]*\n\\s*", "\n")
			.strip();
	}

	private static String text(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
	}
}
