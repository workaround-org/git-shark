package de.workaround.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandleSuggesterTest
{
	@Test
	void stripsSpnDomain()
	{
		assertEquals("miggi", HandleSuggester.suggest("miggi@sso.mymiggi.de"));
	}

	@Test
	void lowercasesAndReplacesInvalidChars()
	{
		assertEquals("john-doe", HandleSuggester.suggest("John.Doe"));
	}

	@Test
	void trimsLeadingAndTrailingSeparators()
	{
		assertEquals("foo", HandleSuggester.suggest("__foo__"));
	}

	@Test
	void clampsToMaxLength()
	{
		String suggestion = HandleSuggester.suggest("a".repeat(80));
		assertTrue(suggestion.length() <= 39, "suggestion must fit the handle length limit");
	}

	@Test
	void returnsValidHandleMatchingPattern()
	{
		String suggestion = HandleSuggester.suggest("Miggi@sso.mymiggi.de");
		assertTrue(suggestion.matches("^[a-z0-9][a-z0-9-]{0,38}$"), "suggestion must satisfy the handle pattern");
	}

	@Test
	void blankClaimYieldsBlankSuggestion()
	{
		assertEquals("", HandleSuggester.suggest(null));
		assertEquals("", HandleSuggester.suggest("   "));
	}
}
