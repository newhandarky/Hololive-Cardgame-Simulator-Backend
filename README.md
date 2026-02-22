# HOLOLIVE Card Game Backend

目前此專案為後端 + 資料庫開發主目錄，包含：
- Spring Boot API（本地埠 `8090`）
- PostgreSQL（本地容器埠 `5432`）
- Flyway migration（`V1`、`V5`、`V6`、`V7`、`V8`、`V9`、`V10`、`V11`、`V12`、`V13`、`V14`、`V15`、`V16`、`V17`、`V18`、`V19`、`V20`、`V21`、`V22`）
- JWT 驗證與本地 mock 登入
- Lobby / GameRoom MVP（建房、入房、就緒、開始、結束回合）
- WebSocket 房間推送（含 `match` + `gameState` 快照）
- 卡片查詢、卡組編輯、卡片管理（card-admin）

## 開發規範
- 開發與修改前，請先閱讀：`doc/AGENTS — 專案規則.md`
- 若本檔與根目錄規範有衝突，以規範檔為準。

## 技術與套件（目前）
- Java `17`
- Spring Boot `3.5.10`
- Spring Web / Validation / Data JPA / Security / WebSocket / Actuator
- PostgreSQL Driver
- Flyway + `flyway-database-postgresql`
- JJWT（`jjwt-api`, `jjwt-impl`, `jjwt-jackson`）
- Lombok

## 目錄重點
- `src/main/resources/application.yaml`：環境設定
- `src/main/resources/db/migration/`：資料庫 migration
- `scripts/official_card_import.py`：官方卡表批次匯入 SQL 產生器
- `src/main/resources/effects/effect-schema.json`：卡片效果 JSON 最小驗證規格
- `src/main/java/com/hololive/cardgame/controller/`：API 控制器
- `src/main/java/com/hololive/cardgame/service/`：服務層
- `src/main/java/com/hololive/cardgame/config/`：Security/CORS/WebSocket 設定
- `src/main/java/com/hololive/cardgame/websocket/`：房間 WebSocket 推播

## 快速啟動（本地）
### 1) 啟動 PostgreSQL（Docker）
```bash
docker run -d --name holocardgame_db \
  -e POSTGRES_DB=holocardgame_db \
  -e POSTGRES_USER=holocard_user \
  -e POSTGRES_PASSWORD=holocard_password \
  -p 5432:5432 postgres:15
```

若容器已存在：
```bash
docker start holocardgame_db
```

### 2) 啟動後端
```bash
./mvnw spring-boot:run
```

## 常用指令
### 後端
```bash
./mvnw -q compile
./mvnw test
./mvnw clean package
./mvnw dependency:tree
```

### Flyway（手動執行）
```bash
./mvnw flyway:migrate
```

### 資料庫檢查（使用容器內 psql）
```bash
docker exec -it holocardgame_db psql -U holocard_user -d holocardgame_db -c "\\dt"
docker exec -it holocardgame_db psql -U holocard_user -d holocardgame_db -c "select card_id, card_type from cards;"
```

## API / WebSocket 快速驗證
### Health
```bash
curl http://localhost:8090/api/health
```

### 取得 JWT（mock 登入）
```bash
TOKEN=$(curl -fsS -X POST http://localhost:8090/api/auth/line-login \
  -H "Content-Type: application/json" \
  -d '{"idToken":"mock:test_user_a","displayName":"測試玩家A","avatarUrl":"https://example.com/a.png"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
```

### 受保護 API（需 JWT）
```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/users
curl -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/users/test
curl -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/matches/create
```

### Lobby API（需 JWT）
```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"roomCode":"ABC123"}' \
  http://localhost:8090/api/matches/join

curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ready":true}' \
  http://localhost:8090/api/matches/1/ready

curl -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/matches/1/start
curl -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/matches/1/actions/end-turn
curl -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/matches/1/state
```

### 卡片 / 卡組 / 管理 API（需 JWT）
```bash
# 卡片查詢
curl -H "Authorization: Bearer $TOKEN" "http://localhost:8090/api/cards?type=MEMBER&keyword=星街"
curl -H "Authorization: Bearer $TOKEN" "http://localhost:8090/api/cards/HBP02-069"

# 設定卡片偏好顯示圖（variantId 可由卡片詳細資料 variants 取得）
curl -X PUT -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"variantId":123}' \
  http://localhost:8090/api/cards/HBP02-069/preferred-variant

# 我的卡組
curl -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/decks/me
curl -X PUT -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"count":2}' \
  http://localhost:8090/api/decks/me/cards/MEM-001

# 一鍵補齊本地測試牌組（快速測 Start Match）
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8090/api/decks/me/quick-setup

# card-admin 建卡（目前為登入即可使用，尚未做角色限制）
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "cardId":"CHE-999",
    "name":"測試 Cheer 卡",
    "cardType":"CHEER",
    "rarity":"N",
    "color":"WHITE"
  }' \
  http://localhost:8090/api/card-admin/cards
```

## 官方卡表批次匯入（第一版）
### 1) 產生 SQL migration（以 hSD13 為例）
```bash
python3 scripts/official_card_import.py \
  --expansion hSD13 \
  --output src/main/resources/db/migration/V9__seed_official_hsd13_batch_01.sql
```

### 2) 套用 migration
```bash
./mvnw -q flyway:migrate
```

### 3) 說明
- `V8` 會先擴充欄位（`cards.tags_json`, `cards.expansion_code`, `cards.source_url`）與 `member_cards` Bloom 等級（含 `SPOT`, `BUZZ`）。
- `V9` 為第一批官方資料匯入（`hSD13` 15 張）。
- `V10` 為第二批官方資料匯入（`hSD12` 14 張）。
- `V11` 為第三批官方資料匯入（`hSD11` 10 張）。
- `V12` 為第四批官方資料匯入（`hSD10` 10 張）。
- `V13` 清除舊測試種子卡（`OSHI-001/002`、`MEM-001/002`、`CHE-001/002`、`SUP-001/999`）。
- `V14` 為第五批官方資料匯入（`hSD09` 10 張）。
- `V15` 為第六批官方資料匯入（`hBP01` 133 張）。
- `V16` 新增卡片變體表（`card_variants`）與使用者偏好表（`user_card_variant_prefs`）。
- `V17` 為第七批官方資料匯入（`hBP02` 115 張）。
- `V18` 為第八批官方資料匯入（`hBP03` 128 張，實際新增 123 張）。
- `V19` 為第九批官方資料匯入（`hBP04` 114 張，含部分既有卡號更新）。
- `V20` 為第十批官方資料匯入（`hBP05` 107 張，含部分既有卡號更新）。
- `V21` 為第十一批官方資料匯入（`hBP06` 129 張，含部分既有卡號更新）。
- `V22` 為第十二批官方資料匯入（`hSD01` 23 張，含部分既有卡號更新）。
- 匯入腳本支援自動抓多分頁，並會將平行版本圖片寫入 `card_variants`（`DEFAULT`、`ALT_n`）。
- 匯入腳本產生的技能/アーツ `effect_json` 目前先用 `UNIMPLEMENTED + rawText`，方便後續逐張映射成可執行規則。

### 4) effect_json 驗證
- `CardAdminService` 建卡時會驗證 JSON：
  - `effectJson`：必須有 `type`，且 `type` 必須在 `effect-schema.json` 白名單。
  - `passiveEffectJson` / `conditionJson`：必須是合法 JSON 物件。
- 若 `type = UNIMPLEMENTED`，至少要帶 `rawText` 或 `rawHeader`，避免匯入後無法追溯原始效果文字。

### WebSocket（房間）
- 連線路徑：`ws://localhost:8090/ws/matches/{matchId}`
- 前端會收到房間事件（如 `USER_JOINED`, `READY_UPDATED`, `MATCH_STARTED`, `TURN_ENDED`）
- 每個事件會帶 `match` 與 `gameState` 快照

## 當前進度
- 已完成：
  - 後端基礎骨架與 DB migration
  - JWT + mock 登入
  - 受保護 API（`/api/users/**`, `/api/matches/**`, `/api/cards/**`, `/api/decks/**`, `/api/card-admin/**`）
  - Lobby / GameRoom 本地雙端連線 MVP（REST + WebSocket）
  - `GET /api/matches/{id}/state` 場地快照 API
  - Action pipeline 最小骨架（含 `END_TURN`）
  - 卡片管理建卡 API（card-admin）
- 尚未完成：
  - LIFF 真實登入驗證
  - 正式對戰初始化（發牌、起始場地）與完整規則引擎
  - card-admin 的角色/白名單權限限制
  - 完整 E2E 測試與部署流程
