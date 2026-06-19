## Why

The current web UI is a bare-bones inline-styled layout (dark GitHub-grey header, system font, plain tables) with no
visual identity and no consistent components. git-shark should look like *git-shark*: a blue-and-white shark-themed
light theme with a modern, nerdy terminal aesthetic — while staying genuinely usable through proper buttons, tab
navigation, and optional keyboard shortcuts.

## What Changes

- Introduce a design system for the server-rendered UI: design tokens (shark blue/white palette, spacing, typography)
  in a single shared stylesheet served as a static resource, replacing the inline `<style>` block in `layout.html`
- Light theme as the one and only theme (no dark mode in scope)
- Typography: monospace/terminal font for code, refs, hashes, clone URLs, and accents; a clean readable font for body
  text — "nerdy but usable"
- Styled, consistent UI components: buttons (primary/secondary/danger), tab navigation, tables, forms, code blocks,
  badges (visibility, default branch)
- Repository pages get tab navigation (Files / Commits / Branches) instead of bare links
- Optional keyboard shortcuts (hotkeys) for common navigation, progressive enhancement only — UI fully works without
  JavaScript
- All 11 existing Qute templates restyled to use the design system

## Capabilities

### New Capabilities

- `ui-design-system`: Design tokens, shared stylesheet, themed components (buttons, tabs, tables, forms, code blocks,
  badges), shark branding, terminal-accent typography, and optional keyboard shortcuts as progressive enhancement

### Modified Capabilities

- `web-ui`: Repository pages SHALL present Files / Commits / Branches as tab navigation; pages SHALL render usable
  without JavaScript (hotkeys are enhancement-only)

## Impact

- `src/main/resources/templates/**` — all templates touch the new classes/components; `layout.html` loses its inline
  styles and links the shared stylesheet
- New static resources: `src/main/resources/META-INF/resources/` (stylesheet, optional small hotkey script, font files
  if self-hosted — no external CDN to keep native image self-contained and offline-friendly)
- No backend/Java changes expected beyond possibly passing an "active tab" hint to templates
- No new Maven dependencies; native-build unaffected (static resources only)
