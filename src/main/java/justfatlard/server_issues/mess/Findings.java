package justfatlard.server_issues.mess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the scan would clean on its own, and what it saw but left for a person to judge. */
public record Findings(List<Group> cleaned, List<Group> left) {

	/** One thing seen, in words that read in chat, and the grid cells it is made of. */
	public record Group(String one, String many, int[] cells) {}

	public int cleanedBlocks() {
		return cleaned.stream().mapToInt(group -> group.cells().length).sum();
	}

	/** {@code "3 dirt pillars, fire, a lava spill"}: like groups counted once each. */
	public static String summary(List<Group> groups) {
		Map<String, List<Group>> alike = new LinkedHashMap<>();
		for (Group group : groups) alike.computeIfAbsent(group.one(), one -> new ArrayList<>()).add(group);
		List<String> parts = new ArrayList<>();
		for (List<Group> same : alike.values()) {
			parts.add(same.size() == 1 ? same.getFirst().one() : same.size() + " " + same.getFirst().many());
		}
		return String.join(", ", parts);
	}
}
