package de.workaround.web;

import java.util.List;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/** Shared Markdown-to-HTML rendering for server-rendered pages (README, markdown blobs, issue descriptions). */
final class Markdown
{
	private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());

	private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

	// escapeHtml + sanitizeUrls: markdown content is untrusted user input rendered into our page,
	// so raw HTML blocks are escaped and javascript:/data: link targets are stripped.
	private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
		.extensions(EXTENSIONS)
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
