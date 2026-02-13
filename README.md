# HOLOLIVE Card Game Backend

目前此專案為後端 + 資料庫開發主目錄，包含：
- Spring Boot API（本地埠 `8090`）
- PostgreSQL（本地容器埠 `5432`）
- Flyway migration（`V1` schema、`V5` seed）
- JWT 驗證與本地 mock 登入
- Lobby MVP API（create/join/ready/start）與 WebSocket 房間推送

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
- `src/main/java/com/hololive/cardgame/controller/`：API 控制器
- `src/main/java/com/hololive/cardgame/service/`：服務層
- `src/main/java/com/hololive/cardgame/config/`：Security/CORS/WebSocket 設定

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
```

### WebSocket（房間）
- 連線路徑：`ws://localhost:8090/ws/matches/{matchId}`
- 前端會收到房間事件（如 `USER_JOINED`, `READY_UPDATED`, `MATCH_STARTED`）

## 當前進度
- 已完成：
  - 後端基礎骨架與 DB migration
  - JWT + mock 登入
  - 受保護 API（`/api/users/**`, `/api/matches/**`）
  - Lobby 本地雙端連線 MVP（REST + WebSocket）
- 尚未完成：
  - LIFF 真實登入驗證
  - 正式對戰規則引擎完整實作
  - 完整 E2E 測試與部署流程
