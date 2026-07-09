package de.workaround.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTest
{

	@Test
	void rendersGfmPipeTablesAsHtmlTables()
	{
		String html = Markdown.render("""
			| Column A | Column B |
			|----------|----------|
			| foo      | bar      |
			""");
		assertTrue(html.contains("<table>"), () -> "expected a <table> element, got: " + html);
		assertTrue(html.contains("<th>Column A</th>"), () -> "expected a header cell, got: " + html);
		assertTrue(html.contains("<td>foo</td>"), () -> "expected a body cell, got: " + html);
	}

	@Test
	void tableCellContentStaysHtmlEscaped()
	{
		String html = Markdown.render("""
			| A |
			|---|
			| <script>alert('xss')</script> |
			""");
		assertFalse(html.contains("<script>"), () -> "raw HTML must stay escaped, got: " + html);
	}

	@Test
	void basicMarkdownStillRenders()
	{
		String html = Markdown.render("Some **bold** text");
		assertTrue(html.contains("<strong>bold</strong>"));
	}

}
