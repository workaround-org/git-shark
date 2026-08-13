package de.workaround.protect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only the renderings that walk git history or build diffs are metered. Cheap pages, the git
 * transport, the REST API and the federation endpoints must stay untouched.
 */
class ExpensivePathsTest
{
	@Test
	void commitAndDiffRenderingsAreExpensive()
	{
		assertTrue(ExpensivePaths.isExpensive("/repos/alice/board/commit/0123456789abcdef"));
		assertTrue(ExpensivePaths.isExpensive("repos/alice/board/commits/main"));
		assertTrue(ExpensivePaths.isExpensive("/repos/alice/board/merge-requests/7"));
		assertTrue(ExpensivePaths.isExpensive("/search"));
	}

	@Test
	void cheapPagesAreNotMetered()
	{
		assertFalse(ExpensivePaths.isExpensive("/repos/alice/board"));
		assertFalse(ExpensivePaths.isExpensive("/repos/alice/board/branches"));
		assertFalse(ExpensivePaths.isExpensive("/repos/alice/board/merge-requests"));
		assertFalse(ExpensivePaths.isExpensive("/"));
		assertFalse(ExpensivePaths.isExpensive("/alice"));
	}

	@Test
	void machineSurfacesAreNeverMetered()
	{
		assertFalse(ExpensivePaths.isExpensive("/repos/alice/board/info/refs"));
		assertFalse(ExpensivePaths.isExpensive("/repos/alice/board/git-upload-pack"));
		assertFalse(ExpensivePaths.isExpensive("/api/v1/repos/alice/board/commits"));
		assertFalse(ExpensivePaths.isExpensive("/ap/users/alice/outbox"));
	}
}
