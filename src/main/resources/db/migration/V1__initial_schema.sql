-- V1：HOLOLIVE 卡牌遊戲初始資料庫結構
-- 基礎主檔定義
CREATE TABLE colors (
    code VARCHAR(20) PRIMARY KEY,
    name VARCHAR(50) NOT NULL
);

CREATE TABLE cards (
    card_id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    rarity VARCHAR(20),
    image_url VARCHAR(512),
    card_type VARCHAR(20) NOT NULL
      CONSTRAINT chk_card_type CHECK (card_type IN ('OSHI','MEMBER','SUPPORT','CHEER')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Oshi（主推）卡資料
CREATE TABLE oshi_cards (
    card_id VARCHAR(50) PRIMARY KEY REFERENCES cards(card_id) ON DELETE CASCADE,
    life INT NOT NULL,
    main_color VARCHAR(20) NOT NULL REFERENCES colors(code),
    sub_color VARCHAR(20) REFERENCES colors(code),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE oshi_skills (
    id SERIAL PRIMARY KEY,
    oshi_card_id VARCHAR(50) NOT NULL REFERENCES oshi_cards(card_id) ON DELETE CASCADE,
    skill_type VARCHAR(20) NOT NULL CONSTRAINT chk_skill_type CHECK (skill_type IN ('NORMAL','SP')),
    skill_name VARCHAR(255) NOT NULL,
    description TEXT,
    holopower_cost INT NOT NULL,
    effect_json JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Holomen（成員）卡資料
CREATE TABLE member_cards (
    card_id VARCHAR(50) PRIMARY KEY REFERENCES cards(card_id) ON DELETE CASCADE,
    hp INT NOT NULL,
    level_type VARCHAR(20) NOT NULL CONSTRAINT chk_member_level CHECK (level_type IN ('DEBUT','FIRST','SECOND')),
    main_color VARCHAR(20) NOT NULL REFERENCES colors(code),
    sub_color VARCHAR(20) REFERENCES colors(code),
    bloom_level INT,
    passive_effect_json JSONB,
    trigger_condition VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE member_arts (
    id SERIAL PRIMARY KEY,
    member_card_id VARCHAR(50) NOT NULL REFERENCES member_cards(card_id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    cost_cheer_json JSONB NOT NULL,
    effect_json JSONB NOT NULL,
    order_index INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Support（支援）卡資料
CREATE TABLE support_cards (
    card_id VARCHAR(50) PRIMARY KEY REFERENCES cards(card_id) ON DELETE CASCADE,
    is_limited BOOLEAN NOT NULL DEFAULT FALSE,
    condition_type VARCHAR(50),
    condition_json JSONB,
    effect_type VARCHAR(50) NOT NULL,
    effect_json JSONB NOT NULL,
    target_type VARCHAR(50) NOT NULL CONSTRAINT chk_support_target CHECK (target_type IN ('SELF','ENEMY','BOTH','SELF_CENTER','ENEMY_CENTER','ANY_HOLOMEM')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Cheer（應援）卡資料
CREATE TABLE cheer_cards (
    card_id VARCHAR(50) PRIMARY KEY REFERENCES cards(card_id) ON DELETE CASCADE,
    color VARCHAR(20) NOT NULL REFERENCES colors(code),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 使用者與持有卡資料
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    line_user_id VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_cards (
    id SERIAL PRIMARY KEY,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    card_id VARCHAR(50) NOT NULL REFERENCES cards(card_id) ON DELETE CASCADE,
    count INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, card_id)
);

-- 對戰與玩家狀態
CREATE TABLE matches (
    id SERIAL PRIMARY KEY,
    room_code VARCHAR(20) NOT NULL UNIQUE,
    player_a_id INT NOT NULL REFERENCES users(id),
    player_b_id INT NOT NULL REFERENCES users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'active' CONSTRAINT chk_match_status CHECK (status IN ('active','finished','abandoned')),
    winner_user_id INT REFERENCES users(id),
    current_turn_player_id INT REFERENCES users(id),
    turn_number INT NOT NULL DEFAULT 1,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE match_players (
    id SERIAL PRIMARY KEY,
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    oshi_card_id VARCHAR(50) NOT NULL REFERENCES oshi_cards(card_id),
    current_life INT NOT NULL,
    sp_skill_used BOOLEAN NOT NULL DEFAULT FALSE,
    skill_used_this_turn BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (match_id, user_id)
);

-- 對戰中的卡片實例追蹤
CREATE TABLE match_cards (
    id SERIAL PRIMARY KEY,
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    owner_user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    card_id VARCHAR(50) NOT NULL REFERENCES cards(card_id),
    zone VARCHAR(20) NOT NULL CONSTRAINT chk_match_card_zone CHECK (zone IN ('DECK','HAND','CHEER_DECK','HOLOPOWER','STAGE','ARCHIVE','LIFE')),
    order_index INT,
    is_face_down BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_match_cards_match_owner_zone ON match_cards(match_id, owner_user_id, zone);
CREATE INDEX idx_match_cards_match_zone ON match_cards(match_id, zone);
CREATE INDEX idx_match_cards_match ON match_cards(match_id);

CREATE TABLE match_holomems (
    id SERIAL PRIMARY KEY,
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    owner_user_id INT NOT NULL REFERENCES users(id),
    match_card_id INT NOT NULL REFERENCES match_cards(id) ON DELETE CASCADE,
    card_id VARCHAR(50) NOT NULL REFERENCES member_cards(card_id),
    zone VARCHAR(20) NOT NULL CONSTRAINT chk_holomem_zone CHECK (zone IN ('CENTER','COLLAB','BACK')),
    is_rested BOOLEAN NOT NULL DEFAULT FALSE,
    is_face_down BOOLEAN NOT NULL DEFAULT FALSE,
    damage_taken INT NOT NULL DEFAULT 0,
    current_level VARCHAR(20) NOT NULL CONSTRAINT chk_holomem_current_level CHECK (current_level IN ('DEBUT','FIRST','SECOND')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (match_id, match_card_id)
);
CREATE INDEX idx_match_holomems_match_owner_zone ON match_holomems(match_id, owner_user_id, zone);
CREATE INDEX idx_match_holomems_match_zone ON match_holomems(match_id, zone);
CREATE INDEX idx_match_holomems_match ON match_holomems(match_id);

CREATE TABLE match_holomem_cheers (
    id SERIAL PRIMARY KEY,
    match_holomem_id INT NOT NULL REFERENCES match_holomems(id) ON DELETE CASCADE,
    cheer_card_id VARCHAR(50) NOT NULL REFERENCES cheer_cards(card_id),
    is_face_down BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_match_holomem_cheers_holomem ON match_holomem_cheers(match_holomem_id);

CREATE TABLE match_holopower (
    id SERIAL PRIMARY KEY,
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    owner_user_id INT NOT NULL REFERENCES users(id),
    match_card_id INT NOT NULL REFERENCES match_cards(id) ON DELETE CASCADE,
    card_id VARCHAR(50) NOT NULL REFERENCES cards(card_id),
    is_face_up BOOLEAN NOT NULL DEFAULT FALSE,
    order_index INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (match_id, match_card_id)
);
CREATE INDEX idx_match_holopower_match_owner ON match_holopower(match_id, owner_user_id);
CREATE INDEX idx_match_holopower_match_owner_faceup ON match_holopower(match_id, owner_user_id, is_face_up);

-- 行動紀錄（Action Log）
CREATE TABLE match_actions (
    id SERIAL PRIMARY KEY,
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES users(id),
    action_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    turn_number INT NOT NULL,
    action_order INT NOT NULL,
    executed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_match_actions_match_turn_order ON match_actions(match_id, turn_number, action_order);
CREATE INDEX idx_match_actions_match_turn ON match_actions(match_id, turn_number);
