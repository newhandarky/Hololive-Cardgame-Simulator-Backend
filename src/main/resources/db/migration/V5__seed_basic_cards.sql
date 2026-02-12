-- V5: Seed basic colors, sample users, and minimal cards for testing

-- Colors
INSERT INTO colors (code, name) VALUES
 ('WHITE','白'),
 ('GREEN','緑'),
 ('RED','赤'),
 ('BLUE','青'),
 ('YELLOW','黄'),
 ('PURPLE','紫')
ON CONFLICT (code) DO NOTHING;

-- Sample users
INSERT INTO users (line_user_id, display_name, avatar_url) VALUES
 ('test_line_user_a','測試玩家A','https://example.com/avatarA.png'),
 ('test_line_user_b','測試玩家B','https://example.com/avatarB.png')
ON CONFLICT (line_user_id) DO NOTHING;

-- Base cards
INSERT INTO cards (card_id,name,rarity,image_url,card_type) VALUES
 ('OSHI-001','星街すいせい 推し', 'SR', NULL,'OSHI'),
 ('OSHI-002','兎田ぺこら 推し', 'SR', NULL,'OSHI'),
 ('MEM-001','星街すいせい デビュー', 'R', NULL,'MEMBER'),
 ('MEM-002','兎田ぺこら デビュー', 'R', NULL,'MEMBER'),
 ('SUP-001','全力応援', 'N', NULL,'SUPPORT'),
 ('CHE-001','白エール', 'N', NULL,'CHEER'),
 ('CHE-002','青エール', 'N', NULL,'CHEER')
ON CONFLICT (card_id) DO NOTHING;

-- Oshi cards
INSERT INTO oshi_cards (card_id, life, main_color, sub_color) VALUES
 ('OSHI-001', 6, 'BLUE', NULL),
 ('OSHI-002', 6, 'GREEN', NULL)
ON CONFLICT (card_id) DO NOTHING;

INSERT INTO oshi_skills (oshi_card_id, skill_type, skill_name, description, holopower_cost, effect_json) VALUES
 ('OSHI-001','NORMAL','流星突擊','對敵方中心造成1點傷害',1,'{"type":"damage","amount":1,"target":"enemy_center"}'),
 ('OSHI-001','SP','彗星衝刺','造成2點傷害並抽1',2,'{"type":"multi","effects":[{"type":"damage","amount":2,"target":"enemy_center"},{"type":"draw","amount":1}]}'),
 ('OSHI-002','NORMAL','ぺこジャンプ','對敵方中心造成1點傷害',1,'{"type":"damage","amount":1,"target":"enemy_center"}'),
 ('OSHI-002','SP','うさぎ大暴走','全場敵方各1點',2,'{"type":"aoe","amount":1,"target":"all_enemy"}')
ON CONFLICT DO NOTHING;

-- Member cards
INSERT INTO member_cards (card_id, hp, level_type, main_color, sub_color, bloom_level, passive_effect_json, trigger_condition) VALUES
 ('MEM-001', 5, 'DEBUT', 'BLUE', NULL, 1, NULL, NULL),
 ('MEM-002', 5, 'DEBUT', 'GREEN', NULL, 1, NULL, NULL)
ON CONFLICT (card_id) DO NOTHING;

INSERT INTO member_arts (member_card_id, name, description, cost_cheer_json, effect_json, order_index) VALUES
 ('MEM-001','ソロライブ','對敵方中心造成1點', '{"BLUE":1}', '{"type":"damage","amount":1,"target":"enemy_center"}',0),
 ('MEM-002','ぺこキック','對敵方中心造成1點', '{"GREEN":1}', '{"type":"damage","amount":1,"target":"enemy_center"}',0)
ON CONFLICT DO NOTHING;

-- Support card
INSERT INTO support_cards (card_id, is_limited, condition_type, condition_json, effect_type, effect_json, target_type) VALUES
 ('SUP-001', FALSE, 'ANY', NULL, 'DRAW', '{"cards":1}', 'SELF')
ON CONFLICT (card_id) DO NOTHING;

-- Cheer cards details
INSERT INTO cheer_cards (card_id, color) VALUES
 ('CHE-001','WHITE'),
 ('CHE-002','BLUE')
ON CONFLICT (card_id) DO NOTHING;
