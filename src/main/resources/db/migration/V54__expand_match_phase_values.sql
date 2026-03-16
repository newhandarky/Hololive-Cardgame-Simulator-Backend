ALTER TABLE matches
    DROP CONSTRAINT IF EXISTS chk_matches_current_phase;

ALTER TABLE matches
    ADD CONSTRAINT chk_matches_current_phase
    CHECK (current_phase IN ('RESET', 'DRAW', 'CHEER', 'MAIN', 'PERFORMANCE', 'END'));
