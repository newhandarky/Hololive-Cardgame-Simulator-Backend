-- V7：本地開發用卡片持有資料（Deck Editor 可直接看到資料）

WITH target_users AS (
    SELECT id
    FROM users
    WHERE line_user_id IN ('test_line_user_a', 'test_line_user_b')
),
target_cards AS (
    SELECT card_id
    FROM cards
)
INSERT INTO user_cards (user_id, card_id, count)
SELECT u.id, c.card_id, 4
FROM target_users u
CROSS JOIN target_cards c
ON CONFLICT (user_id, card_id) DO NOTHING;
