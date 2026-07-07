## 1. Design tokens & stylesheet

- [x] 1.1 Create `src/main/resources/META-INF/resources/shark.css` with `:root` design tokens (D2 palette, spacing, radii, font stacks per D3)
- [x] 1.2 Add base styles: page background, body/heading typography, links, `.mono` terminal-accent utility
- [x] 1.3 Add component styles: buttons (`.btn`, `.btn-primary`, `.btn-secondary`, `.btn-danger`), tabs (`.tabs`, `.tab`, `.active`), tables, forms, code/pre blocks, badges (`.badge`, visibility + default-branch variants)
- [x] 1.4 Verify all D2 token pairs meet WCAG AA 4.5:1 contrast; adjust token values if needed (all 12 pairs ≥ 4.71:1)

## 2. Layout & branding

- [x] 2.1 Replace the inline `<style>` block in `layout.html` with a `<link rel="stylesheet" href="/shark.css">`
- [x] 2.2 Restyle the header: shark-blue bar (`--shark-deep`), logo mark + "git-shark" wordmark in monospace accent, navigation links (real PNG logo at `/img/shark-logo.png` instead of 🦈 emoji)
- [x] 2.3 Add the hidden hotkey help `<dialog>` to `layout.html` (no visible effect without JS)
- [x] 2.4 Write a failing smoke test asserting pages link `/shark.css` and contain no inline `<style>`, then make it green via 2.1 (`DesignSystemTest`)

## 3. Restyle templates

- [x] 3.1 Restyle `HomeResource/home.html` and `newRepo.html`: repo table with visibility badges, primary button for "new repository", styled form (home additionally got a hero section with the shark logo)
- [x] 3.2 Restyle `RepositoryResource/overview.html`, `tree.html`, `blob.html`: clone URLs and file content in monospace code blocks, empty-repo setup instructions as styled code block
- [x] 3.3 Restyle `RepositoryResource/commits.html` and `branches.html`: monospace hashes/ref names, default-branch badge, secondary-button pagination
- [x] 3.4 Restyle `SettingsResource/keys.html`, `tokens.html`, `tokenCreated.html`: styled forms, danger buttons for delete actions, monospace fingerprints/tokens

## 4. Repository tab navigation

- [x] 4.1 Write failing tests: repo pages render Files/Commits/Branches tabs with correct active tab, and tab links preserve the selected ref
- [x] 4.2 Pass an `activeTab` parameter from `RepositoryResource` page methods to the templates
- [x] 4.3 Add the tab markup (shared Qute fragment or per-template) using the `.tabs` component; make 4.1 green

## 5. Hotkeys (progressive enhancement)

- [x] 5.1 Create `src/main/resources/META-INF/resources/shark-hotkeys.js`: key map (`?` help, `Escape` close, `g h` home), form-field guard, ~1s sequence timeout per D5; include via `<script defer>` in `layout.html`
- [x] 5.2 Write test asserting pages render and forms work without JavaScript (server-side rendering unaffected by the script tag)

## 6. Verification

- [x] 6.1 Run full test suite via bishbash; all green
- [x] 6.2 Manual pass over all 11 pages: light theme consistent, tabs/buttons/badges correct, hotkeys work, `?` overlay opens/closes
- [ ] 6.3 Verify native image still builds and serves `/shark.css` + `/shark-hotkeys.js` (META-INF/resources inclusion per D6)
