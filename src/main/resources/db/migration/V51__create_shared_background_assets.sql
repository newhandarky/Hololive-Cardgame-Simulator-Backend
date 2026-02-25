CREATE TABLE IF NOT EXISTS shared_background_assets (
    id BIGSERIAL PRIMARY KEY,
    category VARCHAR(16) NOT NULL,
    image_url TEXT NOT NULL,
    created_by_user_id BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_shared_background_assets_category
        CHECK (category IN ('FIELD', 'CARD'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_shared_background_assets_category_url
    ON shared_background_assets (category, image_url);

CREATE INDEX IF NOT EXISTS idx_shared_background_assets_category_id
    ON shared_background_assets (category, id DESC);
