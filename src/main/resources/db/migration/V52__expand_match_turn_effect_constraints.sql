ALTER TABLE match_turn_effects
    DROP CONSTRAINT IF EXISTS chk_match_turn_effect_type;

ALTER TABLE match_turn_effects
    DROP CONSTRAINT IF EXISTS chk_match_turn_effect_stat_type;

ALTER TABLE match_turn_effects
    ADD CONSTRAINT chk_match_turn_effect_type
    CHECK (
        effect_type IN (
            'BUFF',
            'DEBUFF',
            'BATON_TOUCH_COST_MODIFIER',
            'ALLOW_EXTRA_BLOOM',
            'ACTION_LOCK'
        )
    );

ALTER TABLE match_turn_effects
    ADD CONSTRAINT chk_match_turn_effect_stat_type
    CHECK (
        stat_type IN (
            'DAMAGE_MODIFIER',
            'BATON_TOUCH_COLORLESS_MODIFIER',
            'ALLOW_EXTRA_BLOOM',
            'ACTION_LOCK'
        )
    );
