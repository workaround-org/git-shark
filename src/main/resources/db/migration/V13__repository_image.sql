-- Per-repository images: like user avatars (V10), the bytes live on the filesystem
-- (gitshark.storage.repo-images) and the DB keeps only the content type (to serve with the right
-- MIME type) and an update timestamp (to cache-bust the <img> URL). NULL image_content_type means
-- the repository has no custom image and falls back to its owner's avatar.
ALTER TABLE repositories
    ADD COLUMN image_content_type varchar(64),
    ADD COLUMN image_updated_at   timestamptz;
