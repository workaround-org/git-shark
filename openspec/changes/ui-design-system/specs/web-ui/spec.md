## ADDED Requirements

### Requirement: Repository tab navigation
Repository pages SHALL present Files, Commits, and Branches as tab navigation using the shared tab component, with the tab of the current page marked active. The tabs SHALL be plain links and preserve the currently selected ref where applicable.

#### Scenario: Tabs on repository pages
- **WHEN** a user opens a repository's file browser, commit history, or branches page
- **THEN** the page shows Files / Commits / Branches tabs with the current section marked active

#### Scenario: Ref preserved across tabs
- **WHEN** a user is browsing files at a non-default branch and switches to the Commits tab
- **THEN** the commit history is shown for that same branch

### Requirement: No-JavaScript usability
All web UI pages SHALL render and remain fully operable (navigation, forms, downloads) without JavaScript. Client-side scripting SHALL only be used for progressive enhancement such as keyboard shortcuts.

#### Scenario: Full functionality without JavaScript
- **WHEN** a user with JavaScript disabled creates a repository, browses files, and manages SSH keys
- **THEN** every step completes successfully using server-rendered pages and standard form submissions
