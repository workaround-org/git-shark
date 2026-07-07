## Context

The web UI is server-rendered with Qute (11 templates, one base `layout.html`). All styling currently lives in a
single inline `<style>` block in the layout: dark grey header, system font, default tables. There is no static
resource pipeline yet — `src/main/resources/META-INF/resources/` does not exist. The service compiles to a GraalVM
native image, so everything must be self-contained (no CDN, no build-time CSS tooling that complicates the Maven
build).

User direction: blue & white (shark colors), light theme only, "modern & nerdy" (terminal font accents) but genuinely
usable with buttons & tabs, optionally hotkeys.

## Goals / Non-Goals

**Goals:**

- One shared stylesheet with CSS custom properties as the single source of design truth
- Shark identity: blue/white light theme, monospace accents, branded header
- Consistent components: buttons, tabs, tables, forms, code blocks, badges
- Tab navigation on repository pages (Files / Commits / Branches)
- Optional hotkeys as progressive enhancement; zero-JS baseline stays fully functional

**Non-Goals:**

- Dark mode / theme switching
- CSS frameworks, preprocessors, or a frontend build step (no Tailwind, no npm)
- Client-side rendering or SPA behavior of any kind
- Syntax highlighting for file contents (separate future change)
- Redesigning page structure beyond styling + repo tabs

## Decisions

### D1: Hand-written vanilla CSS, no framework

A single `shark.css` (~300–500 lines) with custom properties on `:root`. Alternatives: Tailwind (needs npm build
step, conflicts with the Maven-only/native-image-simple setup), classless frameworks like Pico.css (fights the
custom shark identity, adds a dependency for little gain). The UI surface is 11 templates — small enough that
hand-written CSS is cheaper than any framework integration.

### D2: Color tokens

Shark palette (light theme), defined once as custom properties:

| Token | Value | Use |
|---|---|---|
| `--shark-blue` | `#1565c0` | primary actions, links, active tab |
| `--shark-deep` | `#0d3b66` | header bar, headings |
| `--shark-fin` | `#4f9fe0` | hover/focus accents |
| `--shark-belly` | `#f4f8fb` | page/code-block tinted background |
| `--surface` | `#ffffff` | cards, tables, main background |
| `--ink` | `#16242f` | body text (dark blue-grey, not pure black) |
| `--ink-muted` | `#5b7282` | secondary text |
| `--border` | `#d3e0ea` | borders, dividers |
| `--danger` | `#c62828` | destructive actions |

All pairs chosen to clear WCAG AA 4.5:1 against their designated backgrounds (verify during implementation).

### D3: Typography — system font stacks, no font files

Body: `system-ui, -apple-system, "Segoe UI", sans-serif`. Terminal accents: `ui-monospace, "SF Mono", "JetBrains
Mono", "Cascadia Code", Menlo, Consolas, monospace`. Alternative considered: self-hosting JetBrains Mono as WOFF2
(~100 KB, needs `@font-face` plus resource-inclusion config for native image). System stacks give the terminal feel
on every modern OS with zero payload and zero native-image config; revisit only if the rendered result disappoints.
This satisfies "self-hosted, no CDN" trivially — there are no font requests at all.

**Addendum (2026-07-07):** the rendered result did disappoint — during implementation the UI adopted Space Grotesk +
JetBrains Mono, initially via Google Fonts CDN, which violated the "no external font requests" requirement. Resolved by
self-hosting both families as variable WOFF2 (latin + latin-ext, ~97 KB total) under `META-INF/resources/fonts/` with
`@font-face` in `shark.css` and OFL license texts alongside. System stacks remain as fallbacks in the font tokens.

### D4: Tabs are links, active state from a template parameter

The repo templates already render per-section pages; each repository page passes an `activeTab` string into the
layout/partial, and the tab markup sets the `active` class by comparison in Qute. No JS, no URL sniffing in CSS.
Resource classes pass at most one extra template parameter — the only Java change in this change.

### D5: Hotkeys — one small vanilla JS file, allowlist-based

`shark-hotkeys.js` (<100 lines, self-hosted, `defer`): a key→action map (`?` help overlay, `Escape` close, `g h`
home as a two-key sequence with a short timeout). Guard clause ignores events when `event.target` is
input/textarea/select or `isContentEditable`. The help overlay is a `<dialog>` element baked into `layout.html`
(renders nothing visible without JS). Alternatives: a hotkey library (dependency for 5 shortcuts — no) or no JS at
all (drops a feature the user explicitly wants as optional).

### D6: Static resources via Quarkus default mechanism

Files go in `src/main/resources/META-INF/resources/` (`shark.css`, `shark-hotkeys.js`), served by Quarkus
automatically, included in native image automatically (classpath resources under META-INF/resources are picked up
by the default resource config). No additional configuration expected.

## Risks / Trade-offs

- [Hand-written CSS drifts as templates grow] → tokens-only rule: components must reference custom properties;
  review new templates against the design system spec
- [System monospace stack varies across OS — "terminal feel" weaker on some platforms] → accepted for now; D3
  documents the WOFF2 upgrade path if needed
- [Two-key sequence hotkeys (`g h`) can swallow keystrokes if the timeout logic is buggy] → keep sequence window
  short (~1s), only `g` starts a sequence, everything else is single-key
- [Contrast assumptions in D2 might fail AA for small/muted text] → check pairs with a contrast tool during
  implementation and adjust token values, not component CSS

## Migration Plan

Pure additive UI change: ship stylesheet + restyled templates in one release; no data, schema, or API impact.
Rollback = revert the commit.

## Open Questions

- None blocking. Shark logo mark: keep the 🦈 emoji initially (already in use); a proper SVG mark can replace it
  later without spec changes.
