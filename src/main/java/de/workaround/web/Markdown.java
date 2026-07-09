package de.workaround.web;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/** Shared Markdown-to-HTML rendering for server-rendered pages (README, markdown blobs, issue descriptions). */
final class Markdown
{
	private static final Parser PARSER = Parser.builder().build();

	// escapeHtml + sanitizeUrls: markdown content is untrusted user input rendered into our page,
	// so raw HTML blocks are escaped and javascript:/data: link targets are stripped.
	private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
		.escapeHtml(true)
		.sanitizeUrls(true)
		.build();

	private Markdown()
	{
	}

	static String render(String markdown)
	{
		return RENDERER.render(PARSER.parse(markdown));
	}

}
