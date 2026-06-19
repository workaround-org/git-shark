## ADDED Requirements

### Requirement: Design tokens and shared stylesheet
The system SHALL serve a single shared stylesheet as a static resource that defines all design tokens (colors, spacing, typography, radii) as CSS custom properties. Templates SHALL NOT contain inline `<style>` blocks; all pages SHALL link the shared stylesheet from the base layout.

#### Scenario: Stylesheet served and linked
- **WHEN** any UI page is rendered
- **THEN** it links the shared stylesheet via `<link rel="stylesheet">` and contains no inline `<style>` block

#### Scenario: Tokens defined as custom properties
- **WHEN** the shared stylesheet is loaded
- **THEN** colors, spacing, and font families are defined as CSS custom properties on `:root` and component rules reference these tokens

### Requirement: Shark color palette and light theme
The UI SHALL use a light theme built on a blue-and-white shark palette: white/near-white page backgrounds, shark-blue as the primary accent (header, links, primary buttons, active tab), and dark blue-grey body text. Color contrast for text on its background SHALL meet WCAG AA (≥ 4.5:1 for normal text).

#### Scenario: Light theme rendering
- **WHEN** a user opens any page
- **THEN** the page background is white/near-white with blue accents and no dark-mode styling is applied

#### Scenario: Accessible contrast
- **WHEN** text is rendered on its designated background token
- **THEN** the contrast ratio is at least 4.5:1

### Requirement: Terminal-accent typography
The UI SHALL render code, file contents, commit hashes, ref names, clone URLs, and SSH key fingerprints in a monospace terminal font stack, and body text/navigation in a clean readable sans-serif stack. Any custom font SHALL be self-hosted; no external CDN requests.

#### Scenario: Monospace for technical content
- **WHEN** a page shows a commit hash, branch name, clone URL, or file content
- **THEN** it is rendered in the monospace font stack

#### Scenario: No external font requests
- **WHEN** any page loads
- **THEN** all font resources are served from the application origin

### Requirement: Button components
The shared stylesheet SHALL provide button styles with at least three variants: primary (shark blue), secondary (outlined/neutral), and danger (destructive actions such as repository or token deletion). All form submit controls and link-buttons in the UI SHALL use one of these variants.

#### Scenario: Variants available and applied
- **WHEN** a page renders an action control
- **THEN** it uses the primary, secondary, or danger button style, with danger reserved for destructive actions

### Requirement: Tab navigation component
The shared stylesheet SHALL provide a tab navigation component with a visually distinct active tab. Tabs SHALL be plain links (server-side navigation), not JavaScript-driven.

#### Scenario: Active tab highlighted
- **WHEN** a page with tab navigation is rendered
- **THEN** the tab corresponding to the current page is visually marked active and the others are plain links

### Requirement: Styled tables, forms, and code blocks
The shared stylesheet SHALL style tables (repository lists, commit logs, branch/key/token lists), form elements (inputs, textareas, selects, labels), and code/pre blocks consistently with the design tokens.

#### Scenario: Consistent table styling
- **WHEN** any list page is rendered
- **THEN** its table uses the shared table styles (token-based borders, header row, hover state)

#### Scenario: Code block styling
- **WHEN** file contents or setup instructions are rendered
- **THEN** they appear in a styled code block with monospace font and tinted background

### Requirement: Badges
The shared stylesheet SHALL provide badge styles used for repository visibility (public/private) and for marking the default branch.

#### Scenario: Visibility badge
- **WHEN** a repository is listed or viewed
- **THEN** its visibility is shown as a styled badge

#### Scenario: Default branch badge
- **WHEN** the branches page is rendered
- **THEN** the default branch carries a badge marking it as default

### Requirement: Shark branding in header
The base layout SHALL render a branded header: shark mark plus the product name "git-shark" in the accent typography, on a shark-blue header bar, with the primary navigation.

#### Scenario: Branded header on every page
- **WHEN** any page is rendered
- **THEN** the header shows the shark mark and "git-shark" linking to the home page

### Requirement: Keyboard shortcuts as progressive enhancement
The UI SHALL offer optional keyboard shortcuts implemented in a single small self-hosted script: at minimum `?` opens a shortcut help overlay, and `g h` navigates home. Shortcuts SHALL NOT fire while focus is in an input, textarea, or select. All functionality reachable via shortcuts SHALL also be reachable by mouse, and every page SHALL remain fully usable with JavaScript disabled.

#### Scenario: Help overlay
- **WHEN** a user presses `?` outside a form field
- **THEN** an overlay listing all available shortcuts is shown and can be dismissed with `Escape`

#### Scenario: Shortcuts suppressed in form fields
- **WHEN** focus is inside an input, textarea, or select and a shortcut key is pressed
- **THEN** no shortcut fires and the keystroke goes to the field

#### Scenario: JavaScript disabled
- **WHEN** a user browses with JavaScript disabled
- **THEN** all pages render and all actions (navigation, forms) work; only shortcuts are unavailable
