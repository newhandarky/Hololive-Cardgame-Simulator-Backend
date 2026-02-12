# HOLOLIVE Official Card Game - 資料庫設計文件

## 設計說明

本資料庫設計依照 HOLOLIVE Official Card Game (hOCG) 的正式規則，採用多張表分離不同功能卡片的架構。

### 關鍵機制說明

#### 1. ホロパワー（Holo Power）機制
- **ホロパワー 不是能量卡（エール）**
- 當玩家執行「コラボ（Collaboration）」動作時：
  - 從 **メインデッキ頂部** 抽 1 張卡
  - **裏面朝下** 放到 **ホロパワーゾーン**
  - 這些卡就是「ホロパワー」，用來支付 **推しスキル** 的消耗
- 使用推しスキル時：
  - 消耗指定數量的ホロパワー（從裏面 → 表面 → 丟到アーカイブ）

#### 2. 推しスキル（Oshi Skill）兩種類型
- **推しスキル（小招）**：每回合可用 1 次，消耗 ホロパワー
- **SP推しスキル（大招）**：整場對戰只能用 1 次，消耗 ホロパワー

#### 3. アーカイブ（Archive）
- 遊戲中被擊倒的ホロメン、使用完的サポート、消耗的エール、消耗的ホロパワー，都進入 **アーカイブ（Archive）**
- **注意**：不是「墓地」或「棄牌區」，官方術語是「アーカイブ」

#### 4. エール（Cheer）的用途
- エール 用來：
  - **支付アーツ（Arts）** 的消耗（角色攻擊技能）
  - 送給場上的ホロメン（在 エールステップ 時）
- **重要**：エール 不用來支付推しスキル，推しスキル只消耗 ホロパワー

---

## 資料表結構

### 一、共用：卡片主表 + 顏色定義

#### 1. `cards`（所有卡共用基本資訊）

```sql
CREATE TABLE cards (
    card_id VARCHAR(50) PRIMARY KEY,       -- 例如 "HLM-001-D"
    name VARCHAR(255) NOT NULL,            -- 卡名
    rarity VARCHAR(20),                    -- 稀有度：N / R / SR / UR
    image_url VARCHAR(512),                -- 卡面圖檔 URL
    card_type VARCHAR(20) NOT NULL         -- 'OSHI', 'MEMBER', 'SUPPORT', 'CHEER'
      CONSTRAINT chk_card_type
        CHECK (card_type IN ('OSHI', 'MEMBER', 'SUPPORT', 'CHEER')),
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**用途說明**：
- 所有卡片的共用資訊表
- `card_type` 區分卡片類型：推しカード / ホロメンカード / サポートカード / エールカード
- 前端根據 `card_type` 判別是哪類卡，後端用 `card_id` 去 JOIN 到對應的功能表

---

#### 2. `colors`（顏色定義）

```sql
CREATE TABLE colors (
    code VARCHAR(20) PRIMARY KEY,          -- 'WHITE', 'GREEN', 'RED', 'BLUE', 'YELLOW', 'PURPLE'
    name VARCHAR(50) NOT NULL              -- 顏色名稱
);

-- 預先插入官方的 6 種顏色
INSERT INTO colors (code, name) VALUES 
    ('WHITE', '白'),
    ('GREEN', '緑'),
    ('RED', '赤'),
    ('BLUE', '青'),
    ('YELLOW', '黄'),
    ('PURPLE', '紫');
```

**用途說明**：
- 定義遊戲中的 6 種官方顏色
- 用於卡片屬性、能量需求等判定

---

### 二、推しカード（Oshi Card / 主推卡）

#### 1. `oshi_cards`（推しホロメン本體）

```sql
CREATE TABLE oshi_cards (
    card_id VARCHAR(50) PRIMARY KEY
        REFERENCES cards(card_id) ON DELETE CASCADE,

    -- 推し的 LIFE（對應到推しライフ）
    life INT NOT NULL,                     -- 通常是 5 或 6

    -- 推し的顏色（影響組牌規則）
    main_color VARCHAR(20) NOT NULL REFERENCES colors(code),
    sub_color VARCHAR(20) REFERENCES colors(code),  -- 可選的第二屬性色

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**用途說明**：
- 推しカード 是玩家的主推角色，放在推しポジション
- `life` 代表推しライフ，當 LIFE 降到 0 時玩家敗北
- `main_color` 和 `sub_color` 決定這張推しカード 的屬性顏色，影響組牌規則

---

#### 2. `oshi_skills`（推しスキル 列表）

```sql
CREATE TABLE oshi_skills (
    id SERIAL PRIMARY KEY,
    
    oshi_card_id VARCHAR(50) NOT NULL
        REFERENCES oshi_cards(card_id) ON DELETE CASCADE,

    -- 技能類型：推しスキル（每回合 1 次）或 SP推しスキル（每場 1 次）
    skill_type VARCHAR(20) NOT NULL
      CONSTRAINT chk_skill_type
        CHECK (skill_type IN ('NORMAL', 'SP')),

    skill_name VARCHAR(255) NOT NULL,      -- 技能名稱
    description TEXT,                      -- 技能說明文字

    -- 消耗：需要多少張 ホロパワー
    holopower_cost INT NOT NULL,

    -- 效果內容（用 JSON 儲存機器可讀格式）
    effect_json JSONB NOT NULL,            -- 例如 {"type": "damage", "amount": 1, "target": "enemy_center"}

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**用途說明**：
- 一張推しカード 通常有 2 個技能：
  - `NORMAL`：推しスキル（每回合可用 1 次）
  - `SP`：SP推しスキル（整場對戰只能用 1 次）
- `holopower_cost` 表示需要消耗多少張 ホロパワー
- `effect_json` 儲存技能效果的機器可讀格式，供對戰引擎使用

---

### 三、角色卡（ホロメンカード / Member Card）

#### 1. `member_cards`（角色本體）

```sql
CREATE TABLE member_cards (
    card_id VARCHAR(50) PRIMARY KEY
        REFERENCES cards(card_id) ON DELETE CASCADE,

    -- 基本數值
    hp INT NOT NULL,                       -- 角色的 HP

    -- 等級：Debut / 1st / 2nd
    level_type VARCHAR(20) NOT NULL
      CONSTRAINT chk_member_level
        CHECK (level_type IN ('DEBUT', 'FIRST', 'SECOND')),

    -- 顏色（角色的屬性色，影響能量需求）
    main_color VARCHAR(20) NOT NULL REFERENCES colors(code),
    sub_color VARCHAR(20) REFERENCES colors(code),

    -- Bloom（進化）相關
    bloom_level INT,                       -- 可進化的等級（例如 1 代表可以 Bloom 到 1st）
    
    -- 被動效果與觸發條件（GIFT / COLLAB / 入場時等）
    passive_effect_json JSONB,             -- 被動效果內容
    trigger_condition VARCHAR(50),         -- 觸發條件：'GIFT', 'COLLAB', 'ON_ENTER', 'CONTINUOUS' 等

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**用途說明**：
- 角色卡是場上對戰的主要單位
- `level_type` 表示卡片等級：
  - `DEBUT`：初始等級
  - `FIRST`：一階進化（1st）
  - `SECOND`：二階進化（2nd）
- `bloom_level` 表示這張卡可以進化到哪個等級
- `trigger_condition` 表示被動效果的觸發條件：
  - `GIFT`：贈禮效果（特定條件下觸發）
  - `COLLAB`：聯動效果（COLLAB 時觸發）
  - `ON_ENTER`：入場時觸發
  - `CONTINUOUS`：持續效果

---

#### 2. `member_arts`（アーツ列表）

```sql
CREATE TABLE member_arts (
    id SERIAL PRIMARY KEY,

    member_card_id VARCHAR(50) NOT NULL
        REFERENCES member_cards(card_id) ON DELETE CASCADE,

    name VARCHAR(255) NOT NULL,            -- アーツ名稱
    description TEXT,                      -- アーツ說明文字

    -- エール（Cheer）消耗：需要的顏色與數量
    -- 例如：{"WHITE": 1, "GREEN": 2} 表示需要 1 張白色 + 2 張綠色エール
    cost_cheer_json JSONB NOT NULL,

    -- 效果內容（傷害／附加效果／對象等）
    effect_json JSONB NOT NULL,            -- 例如 {"damage": 50, "target": "enemy_center", "additional": "draw_1"}

    -- 順序（一張卡可能有多個 Arts）
    order_index INT NOT NULL DEFAULT 0,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**用途說明**：
- アーツ 是角色卡的攻擊技能
- 一張角色卡可以有 1 個或多個 アーツ
- `cost_cheer_json` 儲存使用這個 アーツ 需要的 エール（Cheer）顏色與數量
  - 例如：`{"WHITE": 1, "GREEN": 2}` 表示需要 1 張白色 + 2 張綠色エール
- `effect_json` 儲存 アーツ 的效果（傷害值、目標、附加效果等）
- `order_index` 用於排序多個 アーツ 的顯示順序

---

### 四、サポートカード（Support Card / 支援卡）

```sql
CREATE TABLE support_cards (
    card_id VARCHAR(50) PRIMARY KEY
        REFERENCES cards(card_id) ON DELETE CASCADE,

    -- LIMITED：每回合最多 1 張
    is_limited BOOLEAN NOT NULL DEFAULT FALSE,

    -- 使用條件（GIFT / COLLAB / 特定情境）
    condition_type VARCHAR(50),            -- 'GIFT', 'COLLAB', 'ANY' 等
    condition_json JSONB,                  -- 更詳細的條件描述

    -- 效果類型與內容
    effect_type VARCHAR(50) NOT NULL,      -- 'DRAW', 'HEAL', 'MOVE', 'BUFF', 'SEARCH' 等
    effect_json JSONB NOT NULL,            -- 效果的詳細內容

    -- 使用對象
    target_type VARCHAR(50) NOT NULL
      CONSTRAINT chk_support_target
        CHECK (target_type IN ('SELF', 'ENEMY', 'BOTH', 'SELF_CENTER', 'ENEMY_CENTER', 'ANY_HOLOMEM')),

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**用途說明**：
- サポートカード 是功能卡，用於輔助對戰
- `is_limited` 表示是否為 LIMITED 卡（每回合最多使用 1 張）
- `condition_type` 和 `condition_json` 定義使用條件：
  - `GIFT`：贈禮條件
  - `COLLAB`：需要在 COLLAB 時使用
  - `ANY`：無特殊條件
- `effect_type` 定義效果類型：
  - `DRAW`：抽牌
  - `HEAL`：回復
  - `MOVE`：移動角色位置
  - `BUFF`：增益效果
  - `SEARCH`：搜尋卡片
- `target_type` 定義作用對象

---

### 五、エールカード（Cheer Card / 應援卡）

```sql
CREATE TABLE cheer_cards (
    card_id VARCHAR(50) PRIMARY KEY
        REFERENCES cards(card_id) ON DELETE CASCADE,

    -- エール 的顏色
    color VARCHAR(20) NOT NULL REFERENCES colors(code),

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**用途說明**：
- エールカード 是能量卡，用來支付 アーツ 的消耗
- 每張 エール 有一個顏色屬性
- エール 在 エールステップ 時會送給場上的 ホロメン
- **注意**：エール 不用來支付推しスキル，推しスキル消耗的是 ホロパワー

---

### 六、玩家與持有卡片

#### 1. `users`（玩家）

```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    line_user_id VARCHAR(255) NOT NULL UNIQUE,  -- LINE User ID
    display_name VARCHAR(255) NOT NULL,         -- 玩家顯示名稱
    avatar_url VARCHAR(512),                    -- 頭像 URL
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**用途說明**：
- 儲存玩家基本資訊
- `line_user_id` 用於與 LINE OA 綁定

---

#### 2. `user_cards`（玩家持有的卡片）

```sql
CREATE TABLE user_cards (
    id SERIAL PRIMARY KEY,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    card_id VARCHAR(50) NOT NULL REFERENCES cards(card_id) ON DELETE CASCADE,
    count INT NOT NULL DEFAULT 1,              -- 持有數量
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, card_id)
);
```

**用途說明**：
- 記錄玩家擁有哪些卡片
- `count` 表示同一張卡片的持有數量

---

### 七、對戰狀態

#### 1. `matches`（對戰本體）

```sql
CREATE TABLE matches (
    id SERIAL PRIMARY KEY,
    
    room_code VARCHAR(20) NOT NULL UNIQUE,      -- 房間代碼
    
    player_a_id INT NOT NULL REFERENCES users(id),
    player_b_id INT NOT NULL REFERENCES users(id),
    
    -- 對戰狀態
    status VARCHAR(20) NOT NULL DEFAULT 'active'
      CONSTRAINT chk_match_status
        CHECK (status IN ('active', 'finished', 'abandoned')),
    
    winner_user_id INT REFERENCES users(id),    -- 勝利者
    
    -- 回合資訊
    current_turn_player_id INT REFERENCES users(id),  -- 現在輪到誰
    turn_number INT NOT NULL DEFAULT 1,               -- 目前回合數
    
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP,
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**用途說明**：
- 記錄一場對戰的基本資訊
- `room_code` 是房間代碼，用於玩家加入對戰
- `status` 記錄對戰狀態：
  - `active`：進行中
  - `finished`：已結束
  - `abandoned`：已放棄
- `current_turn_player_id` 和 `turn_number` 追蹤當前回合狀態

---

#### 2. `match_players`（玩家在對戰中的狀態）

```sql
CREATE TABLE match_players (
    id SERIAL PRIMARY KEY,
    
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- 使用的推しカード
    oshi_card_id VARCHAR(50) NOT NULL REFERENCES oshi_cards(card_id),
    
    -- 當前 LIFE 數量
    current_life INT NOT NULL,
    
    -- SP推しスキル 是否已使用（整場只能用 1 次）
    sp_skill_used BOOLEAN NOT NULL DEFAULT FALSE,
    
    -- 本回合是否已使用 推しスキル（每回合 1 次，回合結束重置）
    skill_used_this_turn BOOLEAN NOT NULL DEFAULT FALSE,
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE (match_id, user_id)
);
```

**用途說明**：
- 記錄玩家在特定對戰中的狀態
- `oshi_card_id` 記錄玩家選擇的推しカード
- `current_life` 追蹤當前剩餘的 LIFE 數量
- `sp_skill_used` 記錄 SP推しスキル 是否已經使用（整場只能用 1 次）
- `skill_used_this_turn` 記錄本回合是否已經使用推しスキル（每回合限 1 次，回合結束時重置為 FALSE）

---

### 八、場上狀態

#### 1. `match_holomems`（場上的ホロメン）

```sql
CREATE TABLE match_holomems (
    id SERIAL PRIMARY KEY,
    
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    owner_user_id INT NOT NULL REFERENCES users(id),
    
    card_id VARCHAR(50) NOT NULL REFERENCES member_cards(card_id),
    
    -- 位置：センターポジション / コラボポジション / バックポジション
    zone VARCHAR(20) NOT NULL
      CONSTRAINT chk_holomem_zone
        CHECK (zone IN ('CENTER', 'COLLAB', 'BACK')),
    
    -- 狀態：直立（アクティブ）或橫置（レスト/お休み）
    is_rested BOOLEAN NOT NULL DEFAULT FALSE,
    
    -- 表／裏
    is_face_down BOOLEAN NOT NULL DEFAULT FALSE,
    
    -- 當前累積傷害（官方規則是累計傷害，不是剩餘HP）
    damage_taken INT NOT NULL DEFAULT 0,
    
    -- 當前等級（如果有 Bloom 過）
    current_level VARCHAR(20) NOT NULL
      CONSTRAINT chk_holomem_current_level
        CHECK (current_level IN ('DEBUT', 'FIRST', 'SECOND')),
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**用途說明**：
- 記錄場上每一隻 ホロメン 的狀態
- `zone` 表示位置：
  - `CENTER`：センターポジション（中央位置，主要對戰位置）
  - `COLLAB`：コラボポジション（聯動位置，本回合限定）
  - `BACK`：バックポジション（後台位置）
- `is_rested` 表示是否橫置（お休み狀態）：
  - `FALSE`：アクティブ（直立狀態，可行動）
  - `TRUE`：レスト/お休み（橫置狀態，已使用過）
- `damage_taken` 累計受到的傷害（不是剩餘 HP）
- `current_level` 記錄當前等級（如果有進化過）

---

#### 2. `match_holomem_cheers`（附著在ホロメン上的エール）

```sql
CREATE TABLE match_holomem_cheers (
    id SERIAL PRIMARY KEY,
    
    match_holomem_id INT NOT NULL REFERENCES match_holomems(id) ON DELETE CASCADE,
    cheer_card_id VARCHAR(50) NOT NULL REFERENCES cheer_cards(card_id),
    
    -- エール 的狀態（裏面或表面）
    is_face_down BOOLEAN NOT NULL DEFAULT TRUE,
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**用途說明**：
- 記錄附著在場上 ホロメン 上的 エール
- 在 エールステップ 時，玩家會從 エールデッキ 翻開 1 張 エール，送給場上的某一隻 ホロメン
- 這些 エール 用來支付 アーツ 的消耗

---

### 九、ホロパワー（Holo Power）

#### `match_holopower`（玩家的ホロパワー區）

```sql
CREATE TABLE match_holopower (
    id SERIAL PRIMARY KEY,
    
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    owner_user_id INT NOT NULL REFERENCES users(id),
    
    -- ホロパワー 就是從メインデッキ頂抽的卡
    card_id VARCHAR(50) NOT NULL REFERENCES cards(card_id),
    
    -- 狀態：裏面（未使用）或表面（已使用但還沒送到アーカイブ）
    is_face_up BOOLEAN NOT NULL DEFAULT FALSE,
    
    -- 順序（如果需要記錄放置順序）
    order_index INT,
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**用途說明**（重點機制）：
- **ホロパワー 的取得**：
  - 當玩家執行 COLLAB 動作時，從メインデッキ頂部抽 1 張卡
  - 這張卡裏面朝下放進 ホロパワーゾーン
  - 這就是 ホロパワー
- **ホロパワー 的使用**：
  - 使用推しスキル時，需要消耗指定數量的 ホロパワー
  - 消耗流程：裏面 → 表面 → 送到アーカイブ
- `is_face_up` 表示這張 ホロパワー 的狀態：
  - `FALSE`：裏面（未使用）
  - `TRUE`：表面（已使用但還沒送到アーカイブ）
- **重要**：ホロパワー 不是 エール，是從主牌組抽出來的卡片

---

### 十、各區域的卡片狀態

#### `match_cards`（追蹤所有卡片在對戰中的位置）

```sql
CREATE TABLE match_cards (
    id SERIAL PRIMARY KEY,
    
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    owner_user_id INT NOT NULL REFERENCES users(id),
    
    card_id VARCHAR(50) NOT NULL REFERENCES cards(card_id),
    
    -- 區域
    zone VARCHAR(20) NOT NULL
      CONSTRAINT chk_match_card_zone
        CHECK (zone IN ('DECK', 'HAND', 'CHEER_DECK', 'HOLOPOWER', 'STAGE', 'ARCHIVE', 'LIFE')),
    
    -- 順序（用於牌庫／手牌排序）
    order_index INT,
    
    -- 表／裏
    is_face_down BOOLEAN NOT NULL DEFAULT TRUE,
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**用途說明**：
- 追蹤對戰中所有卡片的位置和狀態
- `zone` 表示卡片所在區域：
  - `DECK`：メインデッキ（主牌庫，50 張）
  - `HAND`：手牌
  - `CHEER_DECK`：エールデッキ（應援牌庫，20 張）
  - `HOLOPOWER`：ホロパワーゾーン（用於支付推しスキル）
  - `STAGE`：場上（詳細位置記錄在 `match_holomems` 表）
  - `ARCHIVE`：アーカイブ（棄牌區／墓地）
  - `LIFE`：推しライフ區（遊戲開始時根據推しカード的 LIFE 數值，從エールデッキ頂部取出對應數量，裏面放在這裡）
- `order_index` 用於記錄牌庫中的順序（頂部／底部）
- `is_face_down` 記錄卡片是否為裏面朝下

---

### 十一、對戰動作記錄（可選，用於回放）

#### `match_actions`（記錄玩家的每個動作）

```sql
CREATE TABLE match_actions (
    id SERIAL PRIMARY KEY,
    
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES users(id),
    
    -- 動作類型
    action_type VARCHAR(50) NOT NULL,      -- 'PLAY_CARD', 'USE_ART', 'USE_OSHI_SKILL', 'COLLAB', 'END_TURN' 等
    
    -- 動作的詳細內容（JSON 格式）
    payload JSONB NOT NULL,
    
    turn_number INT NOT NULL,              -- 第幾回合
    action_order INT NOT NULL,             -- 在同一回合內的動作順序
    
    executed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**用途說明**：
- 記錄對戰中的每個動作
- 可用於：
  - 對戰回放功能
  - 戰術分析
  - 除錯和驗證
- `action_type` 常見類型：
  - `PLAY_CARD`：出牌
  - `USE_ART`：使用 アーツ
  - `USE_OSHI_SKILL`：使用推しスキル
  - `USE_SP_OSHI_SKILL`：使用 SP推しスキル
  - `COLLAB`：執行 COLLAB 動作
  - `BLOOM`：進化
  - `BATON_TOUCH`：接力交換
  - `END_TURN`：結束回合
- `payload` 儲存動作的詳細參數（JSON 格式）

---

## 資料表關係圖摘要

### 卡片相關
```
cards (主表)
├── oshi_cards (推しカード)
│   └── oshi_skills (推し技能)
├── member_cards (ホロメンカード)
│   └── member_arts (アーツ)
├── support_cards (サポートカード)
└── cheer_cards (エールカード)
```

### 玩家與對戰
```
users (玩家)
├── user_cards (持有卡片)
└── matches (對戰)
    ├── match_players (玩家對戰狀態)
    ├── match_holomems (場上角色)
    │   └── match_holomem_cheers (附著的エール)
    ├── match_holopower (ホロパワー)
    ├── match_cards (卡片位置追蹤)
    └── match_actions (動作記錄)
```

---

## 實作建議

### 第一階段：基礎卡片系統
1. 實作 `cards`、`colors` 基礎表
2. 實作各類卡片表：`oshi_cards`、`member_cards`、`support_cards`、`cheer_cards`
3. 實作 `users` 和 `user_cards`（玩家持有卡片）

### 第二階段：對戰基礎
1. 實作 `matches` 和 `match_players`
2. 實作 `match_cards`（追蹤卡片位置）
3. 實作基本對戰流程（抽牌、出牌、結束回合）

### 第三階段：場上互動
1. 實作 `match_holomems`（場上角色狀態）
2. 實作 `match_holomem_cheers`（エール 附著）
3. 實作 アーツ 使用邏輯

### 第四階段：進階機制
1. 實作 `match_holopower`（ホロパワー 系統）
2. 實作推しスキル 使用邏輯
3. 實作 COLLAB、Bloom、Baton Touch 等特殊動作

### 第五階段：回放與分析
1. 實作 `match_actions`（動作記錄）
2. 實作對戰回放功能
3. 實作戰績統計

---

## 注意事項

1. **JSON 欄位的使用**：
   - `effect_json`、`condition_json`、`cost_cheer_json` 等欄位使用 JSONB 類型
   - 方便儲存複雜的效果和條件邏輯
   - 建議定義明確的 JSON Schema 以便前後端統一解析

2. **ホロパワー vs エール**：
   - **ホロパワー**：從主牌組抽取，用於支付推しスキル
   - **エール**：從エールデッキ翻開，用於支付 アーツ
   - 兩者完全不同，不可混用

3. **アーカイブ的處理**：
   - 所有離場的卡片都進入 アーカイブ（`zone = 'ARCHIVE'`）
   - 包括：被擊倒的ホロメン、使用完的サポート、消耗的エール、消耗的ホロパワー

4. **擴展性考量**：
   - 本設計預留了 JSON 欄位供未來擴展
   - 新增卡片效果時不需要修改資料表結構
   - 建議在應用層定義清楚的效果類型和參數格式

---

## 授權與使用

本資料庫設計文件依照 HOLOLIVE Official Card Game 的官方規則制作，僅供學習與開發使用。

HOLOLIVE Official Card Game 及相關商標屬於 COVER Corporation / hololive production 所有。
