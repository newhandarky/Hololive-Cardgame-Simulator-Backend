-- V45：回合內暫時效果（BUFF / DEBUFF）
CREATE TABLE IF NOT EXISTS match_turn_effects (
    id SERIAL PRIMARY KEY,
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    source_user_id INT NOT NULL REFERENCES users(id),
    affected_user_id INT NOT NULL REFERENCES users(id),
    effect_type VARCHAR(20) NOT NULL CONSTRAINT chk_match_turn_effect_type CHECK (effect_type IN ('BUFF', 'DEBUFF')),
    stat_type VARCHAR(30) NOT NULL CONSTRAINT chk_match_turn_effect_stat_type CHECK (stat_type IN ('DAMAGE_MODIFIER')),
    modifier_value INT NOT NULL,
    expires_turn INT NOT NULL,
    payload JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_match_turn_effects_match_affected_turn
    ON match_turn_effects(match_id, affected_user_id, expires_turn);
CREATE INDEX IF NOT EXISTS idx_match_turn_effects_match_turn
    ON match_turn_effects(match_id, expires_turn);
