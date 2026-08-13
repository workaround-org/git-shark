package de.workaround.protect;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The rendered pages worth metering: those that walk git history or build a diff, plus search. Cheap
 * pages (repository overview, branch and tag lists, issue pages) are left alone, and so are the
 * machine surfaces — git transport, {@code /api/v1}, the ActivityPub endpoints and the MCP server —
 * which authenticate their own callers and are consumed by tools that would break on a challenge.
 */
final class ExpensivePaths
{
	private static final List<Pattern> PATTERNS = List.of(
		// a single commit: parse the commit plus a full tree-to-tree diff
		Pattern.compile("^repos/[^/]+/[^/]+/commit/.+$"),
		// a page of history: revwalk over the ref
		Pattern.compile("^repos/[^/]+/[^/]+/commits(/.*)?$"),
		// a merge request page renders the branch diff
		Pattern.compile("^repos/[^/]+/[^/]+/merge-requests/\\d+$"),
		// repository + people search across the instance
		Pattern.compile("^search$"));

	private ExpensivePaths()
	{
	}

	static boolean isExpensive(String path)
	{
		String normalized = normalize(path);
		return PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).matches());
	}

	private static String normalize(String path)
	{
		if (path == null)
		{
			return "";
		}
		String normalized = path.startsWith("/") ? path.substring(1) : path;
		return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
	}
}
