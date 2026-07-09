-- Profile pictures: bytes live on the filesystem (gitshark.storage.avatars), the DB keeps only
-- the content type (needed to serve with the right MIME type) and an update timestamp (used to
-- cache-bust the <img> URL). NULL avatar_content_type means the user has no avatar.
ALTER TABLE users
    ADD COLUMN avatar_content_type varchar(64),
    ADD COLUMN avatar_updated_at   timestamptz;
