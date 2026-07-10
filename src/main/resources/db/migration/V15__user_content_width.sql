-- Per-user display preference for the main content column width. Presets scale the
-- layout's max-width: FULL keeps the current 1120px, COMFORTABLE 85% (952px),
-- COMPACT 70% (784px). Existing and new users default to FULL (no visual change).
ALTER TABLE users
    ADD COLUMN content_width varchar(16) NOT NULL DEFAULT 'FULL';
