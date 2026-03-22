ALTER TABLE match_holomem_cheers
ADD COLUMN match_card_id INT REFERENCES match_cards(id) ON DELETE SET NULL;

CREATE INDEX idx_match_holomem_cheers_match_card
    ON match_holomem_cheers(match_card_id);
