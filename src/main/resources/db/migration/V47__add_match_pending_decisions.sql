CREATE TABLE match_pending_decisions (
    id BIGSERIAL PRIMARY KEY,
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    decision_type VARCHAR(50) NOT NULL,
    source_action_type VARCHAR(50) NOT NULL,
    source_card_instance_id BIGINT,
    source_card_id VARCHAR(50),
    effect_type VARCHAR(50) NOT NULL,
    min_select INT NOT NULL DEFAULT 1,
    max_select INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    context_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP NULL
);

CREATE INDEX idx_match_pending_decisions_match_user_status
    ON match_pending_decisions(match_id, user_id, status, id);
