# 資料庫 Migration 策略與規範

## 概述

本文件定義資料庫 Schema 的版本管理、Migration 工具選擇、檔案命名規則，以及與文件同步策略，確保資料庫結構的可追蹤性與一致性。

---

## 1. Migration 工具選擇

### 1.1 工具比較

| 工具 | 優點 | 缺點 | 建議 |
|-----|------|------|------|
| **Flyway** | 簡單、輕量、Spring Boot 原生支援 | 功能較少 | ✅ 推薦 |
| **Liquibase** | 功能強大、支援多種格式（XML/YAML/SQL） | 複雜、學習曲線高 | 複雜需求才用 |
| 原生 SQL 腳本 | 完全掌控 | 無版本管理、易出錯 | 不推薦 |

**結論**：選擇 **Flyway**，理由：
- Spring Boot 原生整合，無需額外配置
- 輕量、易學習
- 支援 SQL 與 Java 兩種格式
- 適合中小型專案

---

### 1.2 Flyway 基本概念

#### 核心原理

Flyway 會在資料庫中建立一個 `flyway_schema_history` 表，記錄已執行的 Migration：

```sql
CREATE TABLE flyway_schema_history (
    installed_rank INT NOT NULL,
    version VARCHAR(50),
    description VARCHAR(200),
    type VARCHAR(20) NOT NULL,
    script VARCHAR(1000) NOT NULL,
    checksum INT,
    installed_by VARCHAR(100) NOT NULL,
    installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    execution_time INT NOT NULL,
    success BOOLEAN NOT NULL,
    PRIMARY KEY (installed_rank)
);
```

每次啟動時，Flyway 會：
1. 檢查 `flyway_schema_history` 表
2. 比對 `src/main/resources/db/migration/` 目錄下的腳本
3. 執行尚未執行的 Migration

---

## 2. 檔案路徑與命名規則

### 2.1 檔案結構

```
src/main/resources/db/migration/
├── V1__initial_schema.sql
├── V2__add_match_cards_table.sql
├── V3__add_indexes.sql
├── V4__add_unique_constraints.sql
└── V5__seed_basic_cards.sql
```

---

### 2.2 命名規則

#### 格式

```
V{版本號}__{描述}.sql
```

| 部分 | 說明 | 範例 |
|-----|------|------|
| `V` | 固定前綴（Version） | `V` |
| `{版本號}` | 遞增的數字（可用點分隔） | `1`, `2`, `3.1`, `3.2` |
| `__` | 雙底線分隔符 | `__` |
| `{描述}` | 描述性名稱（用底線分隔單字） | `initial_schema`, `add_users_table` |
| `.sql` | 副檔名 | `.sql` |

#### 範例

✅ **正確命名**：
- `V1__initial_schema.sql`
- `V2__add_match_cards_table.sql`
- `V3__add_indexes.sql`
- `V4__add_unique_constraints.sql`
- `V5__seed_basic_cards.sql`
- `V3.1__fix_match_cards_constraint.sql`（若 V3 之後需要小修正）

❌ **錯誤命名**：
- `v1_initial_schema.sql`（小寫 v）
- `V1_initial_schema.sql`（單底線）
- `V1-initial-schema.sql`（使用連字符）
- `initial_schema.sql`（缺少版本號）

---

### 2.3 版本號策略

#### 主版本號（Major Version）

用於重大結構變更：

- `V1__initial_schema.sql`：首版 Schema
- `V2__add_match_system.sql`：新增對戰系統表
- `V3__add_effect_system.sql`：新增效果系統表

#### 子版本號（Minor Version）

用於小修正或補充：

- `V1.1__fix_users_table.sql`：修正 users 表
- `V2.1__add_missing_index.sql`：補充索引

---

## 3. 首版 Migration（V1）

### 3.1 `V1__initial_schema.sql`

這是專案的首版 Schema，應包含所有基礎表與索引／約束。

```sql
-- ========================================
-- HOLOLIVE Card Game Database Schema
-- Version: 1.0
-- Created: 2026-02-12
-- ========================================

-- ========================================
-- 1. 使用者相關
-- ========================================

CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    line_user_id VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    avatar_url VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_line_user_id ON users(line_user_id);

-- ========================================
-- 2. 卡片定義層
-- ========================================

-- 基礎卡片表
CREATE TABLE cards (
    card_id VARCHAR(50) PRIMARY KEY,
    card_name VARCHAR(100) NOT NULL,
    card_type VARCHAR(20) NOT NULL 
      CONSTRAINT chk_card_type 
        CHECK (card_type IN ('OSHI', 'MEMBER', 'SUPPORT', 'CHEER')),
    rarity VARCHAR(20),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_cards_card_type ON cards(card_type);

-- 推しカード
CREATE TABLE oshi_cards (
    card_id VARCHAR(50) PRIMARY KEY REFERENCES cards(card_id) ON DELETE CASCADE,
    color VARCHAR(20) NOT NULL,
    starting_life INT NOT NULL DEFAULT 5,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 推しスキル
CREATE TABLE oshi_skills (
    id SERIAL PRIMARY KEY,
    oshi_card_id VARCHAR(50) NOT NULL REFERENCES oshi_cards(card_id) ON DELETE CASCADE,
    skill_name VARCHAR(100) NOT NULL,
    skill_type VARCHAR(20) NOT NULL 
      CONSTRAINT chk_skill_type 
        CHECK (skill_type IN ('NORMAL', 'SP')),
    holopower_cost INT NOT NULL,
    effect_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_oshi_skills_oshi_card ON oshi_skills(oshi_card_id);

-- ホロメンカード
CREATE TABLE member_cards (
    card_id VARCHAR(50) PRIMARY KEY REFERENCES cards(card_id) ON DELETE CASCADE,
    member_name VARCHAR(100) NOT NULL,
    level VARCHAR(20) NOT NULL 
      CONSTRAINT chk_member_level 
        CHECK (level IN ('DEBUT', 'FIRST', 'SECOND')),
    hp INT NOT NULL,
    color VARCHAR(20) NOT NULL,
    tags VARCHAR(200),
    bloom_level VARCHAR(50),
    gift_effect_json JSONB,
    collab_effect_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_member_cards_level ON member_cards(level);
CREATE INDEX idx_member_cards_color ON member_cards(color);

-- ホロメンアーツ
CREATE TABLE member_arts (
    id SERIAL PRIMARY KEY,
    card_id VARCHAR(50) NOT NULL REFERENCES member_cards(card_id) ON DELETE CASCADE,
    art_name VARCHAR(100) NOT NULL,
    damage INT NOT NULL,
    cost_cheer_json JSONB NOT NULL,
    effect_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_member_arts_card_id ON member_arts(card_id);

-- サポートカード
CREATE TABLE support_cards (
    card_id VARCHAR(50) PRIMARY KEY REFERENCES cards(card_id) ON DELETE CASCADE,
    card_name VARCHAR(100) NOT NULL,
    card_type VARCHAR(20) NOT NULL 
      CONSTRAINT chk_support_type 
        CHECK (card_type IN ('SUPPORT', 'EVENT', 'ITEM', 'FAN', 'MASCOT')),
    limited_type VARCHAR(20),
    effect_json JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- エールカード
CREATE TABLE cheer_cards (
    card_id VARCHAR(50) PRIMARY KEY REFERENCES cards(card_id) ON DELETE CASCADE,
    color VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_cheer_cards_color ON cheer_cards(color);

-- ========================================
-- 3. 卡片持有層
-- ========================================

CREATE TABLE user_cards (
    id SERIAL PRIMARY KEY,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    card_id VARCHAR(50) NOT NULL REFERENCES cards(card_id),
    count INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, card_id)
);

CREATE INDEX idx_user_cards_user ON user_cards(user_id);

-- ========================================
-- 4. 對戰系統
-- ========================================

-- 對戰表
CREATE TABLE matches (
    id SERIAL PRIMARY KEY,
    room_code VARCHAR(6) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL 
      CONSTRAINT chk_match_status 
        CHECK (status IN ('WAITING', 'INITIALIZING', 'ACTIVE', 'FINISHED', 'ABANDONED')),
    current_phase VARCHAR(30),
    turn_number INT NOT NULL DEFAULT 1,
    current_turn_player_id INT REFERENCES users(id),
    player_a_id INT NOT NULL REFERENCES users(id),
    player_b_id INT REFERENCES users(id),
    winner_id INT REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0  -- 樂觀鎖版本號
);

CREATE INDEX idx_matches_room_code ON matches(room_code);
CREATE INDEX idx_matches_status ON matches(status);
CREATE INDEX idx_matches_created_at ON matches(created_at);

-- 對戰玩家狀態
CREATE TABLE match_players (
    id SERIAL PRIMARY KEY,
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES users(id),
    oshi_card_id VARCHAR(50) NOT NULL REFERENCES oshi_cards(card_id),
    current_life INT NOT NULL DEFAULT 5,
    sp_skill_used BOOLEAN NOT NULL DEFAULT FALSE,
    skill_used_this_turn BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(match_id, user_id)
);

CREATE INDEX idx_match_players_match ON match_players(match_id);

-- ========================================
-- 5. 卡片實例層
-- ========================================

-- 對戰中的卡片（所有區域）
CREATE TABLE match_cards (
    id SERIAL PRIMARY KEY,
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    owner_user_id INT NOT NULL REFERENCES users(id),
    card_id VARCHAR(50) NOT NULL REFERENCES cards(card_id),
    zone VARCHAR(20) NOT NULL 
      CONSTRAINT chk_match_card_zone 
        CHECK (zone IN ('DECK', 'HAND', 'CHEER_DECK', 'HOLOPOWER', 'STAGE', 'ARCHIVE', 'LIFE')),
    order_index INT,
    is_face_down BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_match_cards_match_owner_zone ON match_cards(match_id, owner_user_id, zone);
CREATE INDEX idx_match_cards_match_zone ON match_cards(match_id, zone);

-- 場上ホロメン
CREATE TABLE match_holomems (
    id SERIAL PRIMARY KEY,
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    owner_user_id INT NOT NULL REFERENCES users(id),
    match_card_id INT NOT NULL REFERENCES match_cards(id) ON DELETE CASCADE,
    card_id VARCHAR(50) NOT NULL REFERENCES member_cards(card_id),
    zone VARCHAR(20) NOT NULL 
      CONSTRAINT chk_holomem_zone 
        CHECK (zone IN ('CENTER', 'COLLAB', 'BACK')),
    is_rested BOOLEAN NOT NULL DEFAULT FALSE,
    is_face_down BOOLEAN NOT NULL DEFAULT FALSE,
    damage_taken INT NOT NULL DEFAULT 0,
    current_level VARCHAR(20) NOT NULL 
      CONSTRAINT chk_holomem_current_level 
        CHECK (current_level IN ('DEBUT', 'FIRST', 'SECOND')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(match_id, match_card_id)
);

CREATE INDEX idx_match_holomems_match_owner_zone ON match_holomems(match_id, owner_user_id, zone);
CREATE INDEX idx_match_holomems_match_zone ON match_holomems(match_id, zone);

-- ホロメン附著的エール
CREATE TABLE match_holomem_cheers (
    id SERIAL PRIMARY KEY,
    match_holomem_id INT NOT NULL REFERENCES match_holomems(id) ON DELETE CASCADE,
    cheer_card_id VARCHAR(50) NOT NULL REFERENCES cheer_cards(card_id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_match_holomem_cheers_holomem ON match_holomem_cheers(match_holomem_id);

-- ホロパワー
CREATE TABLE match_holopower (
    id SERIAL PRIMARY KEY,
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    owner_user_id INT NOT NULL REFERENCES users(id),
    match_card_id INT NOT NULL REFERENCES match_cards(id) ON DELETE CASCADE,
    card_id VARCHAR(50) NOT NULL REFERENCES cards(card_id),
    is_face_up BOOLEAN NOT NULL DEFAULT FALSE,
    order_index INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(match_id, match_card_id)
);

CREATE INDEX idx_match_holopower_match_owner ON match_holopower(match_id, owner_user_id);
CREATE INDEX idx_match_holopower_match_owner_faceup ON match_holopower(match_id, owner_user_id, is_face_up);

-- ========================================
-- 6. 動作記錄
-- ========================================

CREATE TABLE match_actions (
    id SERIAL PRIMARY KEY,
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES users(id),
    turn_number INT NOT NULL,
    action_order INT NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    payload_json JSONB NOT NULL,
    result_json JSONB,
    state_version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_match_actions_match_turn_order ON match_actions(match_id, turn_number, action_order);
CREATE INDEX idx_match_actions_match_turn ON match_actions(match_id, turn_number);

-- ========================================
-- 7. 觸發器：自動更新 updated_at
-- ========================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_user_cards_updated_at BEFORE UPDATE ON user_cards
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_match_cards_updated_at BEFORE UPDATE ON match_cards
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_match_holomems_updated_at BEFORE UPDATE ON match_holomems
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ========================================
-- 完成
-- ========================================
```

---

## 4. 後續 Migration 範例

### 4.1 `V2__add_card_images.sql`（新增欄位）

```sql
-- 為卡片表新增圖片 URL 欄位
ALTER TABLE cards 
ADD COLUMN image_url VARCHAR(500);

-- 為ホロメン表新增縮圖 URL
ALTER TABLE member_cards 
ADD COLUMN thumbnail_url VARCHAR(500);

-- 為推し表新增圖片 URL
ALTER TABLE oshi_cards 
ADD COLUMN image_url VARCHAR(500);
```

---

### 4.2 `V3__add_performance_indexes.sql`（效能優化）

```sql
-- 新增查詢效能索引

-- 對戰動作查詢優化
CREATE INDEX idx_match_actions_user ON match_actions(user_id);
CREATE INDEX idx_match_actions_created_at ON match_actions(created_at);

-- 使用者卡片查詢優化
CREATE INDEX idx_user_cards_card_id ON user_cards(card_id);

-- ホロメンアーツ查詢優化
CREATE INDEX idx_member_arts_damage ON member_arts(damage);

-- 對戰狀態查詢優化
CREATE INDEX idx_matches_player_a ON matches(player_a_id);
CREATE INDEX idx_matches_player_b ON matches(player_b_id);
```

---

### 4.3 `V4__add_match_chat.sql`（新增功能）

```sql
-- 新增對戰聊天功能

CREATE TABLE match_chat_messages (
    id SERIAL PRIMARY KEY,
    match_id INT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES users(id),
    message TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_match_chat_match ON match_chat_messages(match_id);
CREATE INDEX idx_match_chat_created_at ON match_chat_messages(match_id, created_at);
```

---

## 5. 與文件同步策略

### 5.1 文件更新流程

每次新增 Migration 時，**必須同步更新** `hololive-card-game-database-schema.md` 文件：

```
1. 撰寫 Migration SQL（例如：V2__add_card_images.sql）
2. 執行 Migration（本機測試）
3. 更新文件（hololive-card-game-database-schema.md）
4. Commit 時包含兩者：
   - db/migration/V2__add_card_images.sql
   - docs/hololive-card-game-database-schema.md
```

---

### 5.2 文件版本標記

在文件頂部標記當前對應的 Migration 版本：

```markdown
# HOLOLIVE 卡牌遊戲資料庫設計

**版本**：V4（對應 Flyway Migration V4__add_match_chat.sql）  
**更新日期**：2026-02-15  
**作者**：開發團隊
```

---

### 5.3 Git Commit 訊息規範

```bash
# 好的 Commit 訊息
git commit -m "feat(db): V2 新增卡片圖片欄位 (add card image URLs)"

# 包含文件更新
git commit -m "feat(db): V3 新增效能索引，並更新資料庫文件"

# 修正 Migration
git commit -m "fix(db): V2.1 修正 card_images 預設值"
```

---

## 6. Spring Boot 配置

### 6.1 `application.yml` 配置

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/holocardgame_db
    username: holocard_user
    password: holocard_password
    
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true  # 若資料庫已有資料，設為 true
    validate-on-migrate: true  # 驗證 Migration 完整性
    out-of-order: false  # 不允許亂序執行
    table: flyway_schema_history  # 歷史記錄表名
```

---

### 6.2 `pom.xml` 依賴

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>

<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

---

## 7. Migration 最佳實踐

### 7.1 ✅ 推薦做法

1. **每個 Migration 只做一件事**
   - ❌ 不好：`V2__add_fields_and_indexes_and_seed_data.sql`
   - ✅ 好：`V2__add_card_images.sql`、`V3__add_indexes.sql`、`V4__seed_basic_cards.sql`

2. **不要修改已執行的 Migration**
   - 已執行的 Migration 不可修改（Flyway 會檢查 checksum）
   - 若需修正，應建立新的 Migration（例如：`V2.1__fix_card_images.sql`）

3. **測試 Migration**
   - 在本機測試 Migration 是否能成功執行
   - 測試 Rollback（若使用 Flyway Pro）

4. **使用註解**
   - 在 SQL 中加入註解說明用途

5. **備份生產資料**
   - 執行 Migration 前先備份資料庫

---

### 7.2 ❌ 避免事項

1. **不要在 Migration 中使用動態內容**
   - ❌ `INSERT INTO users (created_at) VALUES (NOW());`（NOW() 每次執行結果不同）
   - ✅ `INSERT INTO users (created_at) VALUES ('2026-02-12 00:00:00');`

2. **不要在 Migration 中刪除表或欄位**
   - 除非確定不再需要（建議先標記為 deprecated，下個版本再刪除）

3. **不要在 Migration 中執行大量資料操作**
   - 大量 INSERT／UPDATE 可能導致 Migration 超時
   - 建議分批執行或使用 Java Migration

---

## 8. 回滾策略

### 8.1 Flyway Community 限制

Flyway Community Edition **不支援自動回滾**，只能：
1. 手動撰寫回滾 SQL
2. 還原資料庫備份

---

### 8.2 手動回滾範例

若 `V2__add_card_images.sql` 需要回滾：

```sql
-- V2_rollback__remove_card_images.sql（手動執行）

ALTER TABLE cards DROP COLUMN IF EXISTS image_url;
ALTER TABLE member_cards DROP COLUMN IF EXISTS thumbnail_url;
ALTER TABLE oshi_cards DROP COLUMN IF EXISTS image_url;

-- 刪除 Flyway 歷史記錄（謹慎使用）
DELETE FROM flyway_schema_history WHERE version = '2';
```

---

## 9. 監控與除錯

### 9.1 檢查 Migration 狀態

```bash
# 查看 Flyway 歷史
SELECT * FROM flyway_schema_history ORDER BY installed_rank;
```

---

### 9.2 常見問題排查

| 問題 | 原因 | 解決方式 |
|-----|------|---------|
| `Checksum mismatch` | Migration 檔案被修改 | 還原原始檔案或修復 checksum |
| `Migration already applied` | 版本號重複 | 使用新的版本號 |
| `Syntax error` | SQL 語法錯誤 | 檢查 SQL 語法 |
| `Timeout` | Migration 執行時間過長 | 分批執行或優化 SQL |

---

## 總結

本文件定義了資料庫 Migration 的完整策略，包括：

1. **工具選擇**：Flyway（輕量、Spring Boot 原生支援）
2. **命名規則**：`V{版本號}__{描述}.sql`
3. **首版 Migration**：完整的 Schema + 索引 + 約束
4. **與文件同步**：每次 Migration 都更新文件
5. **最佳實踐**：每個 Migration 只做一件事、不修改已執行的 Migration
6. **回滾策略**：手動撰寫回滾 SQL

遵循這些規範可確保資料庫結構的可追蹤性與一致性。
