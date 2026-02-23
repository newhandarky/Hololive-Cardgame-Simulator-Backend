-- V43：補上對戰 phase，提供前後端統一回合階段狀態

ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS current_phase VARCHAR(20) NOT NULL DEFAULT 'RESET';

UPDATE matches
SET current_phase = 'RESET'
WHERE current_phase IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_matches_current_phase'
    ) THEN
        ALTER TABLE matches
            ADD CONSTRAINT chk_matches_current_phase
            CHECK (current_phase IN ('RESET','MAIN','PERFORMANCE','END'));
    END IF;
END $$;
