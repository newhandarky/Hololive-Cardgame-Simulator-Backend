若與根目錄衝突，以根目錄為準

# AGENTS.md — 專案規則（OpenCode / Coding Agent）

## 0) 溝通與輸出格式

- 回覆一律使用繁體中文（zh-TW），除非我明確要求英文。
- 程式碼與指令可以維持英文，但解釋與討論請用繁體中文。
- 做 code review 時請先列出風險/問題，再提出具體可落地的修改建議（偏 patch/diff 或逐檔清單）。
- 回覆建議固定結構：
    1. 已確認的事實（你讀到/執行過什麼，附檔名/路徑）
    2. 風險/問題（按嚴重度排序）
    3. 修改方案（逐檔、最小變更）
    4. 驗證方式（本機或 Antigravity 環境執行）

---

## 1) 讀檔/定位規則（避免工具迴圈）

- 在使用任何 read/scan 工具之前，必須先用 `ls` 或檔案樹確認目錄存在。
- 不允許臆測 Spring Boot Controller/Service 路徑（例如 `src/main/java/com/hololive/cardgame/controller`）；若找不到，改從專案結構或 `pom.xml`/`build.gradle` 反查並定位實際檔案路徑。
- 不允許臆測 React 元件路徑（例如 `src/components/Match`）；若找不到，改從 `src/App.tsx` 或路由定義反查實際檔案。
- 若遇到 ENOENT，必須停止重試同一路徑，改列出上一層目錄內容並重新定位。

---

### 1.1 禁止「片段誤判」（重要）

- 你不得在只讀到片段（snippet）或只看到錯誤訊息時，就斷言「這段程式碼有錯 / 有 bug / 型別錯 / 不存在」。
- 在宣稱某檔案有 bug/型別錯之前，必須先讀完整檔案；若檔案過大，必須分段讀完，並在回覆中註明你讀到的範圍（例如：已讀 1–200 行、201–400 行）。
- 若你的判斷依賴符號定義（class/function/interface/type/component），必須同時讀到：
    - 定義處（definition）
    - 呼叫/使用處（usage）
    - 相關 import / dependency injection（若適用）
- 若仍無法確認，請提出「需要補充的最小上下文」（列出要再讀的檔案/區塊），不要直接下結論。

---

### 1.2 工具使用優先序（降低 token 與長尾延遲）

- 優先使用最小讀取策略：先 `glob` 找檔名，再 `read` 精準讀必要檔案；避免一次讀很多檔案。
- 避免讀入大量第三方目錄內容（例如 `node_modules/`、`target/`、`build/`、`dist/`、`.mvn/`）；需要查依賴時改用 `pom.xml`、`package.json` 或精準搜尋。
- 每次準備修改前，先列出「將讀取的檔案清單（最多 3 個）」與理由；我確認後再讀。

---

## 2) Java 後端指令執行規範

### 2.1 開發環境

- 所有 Java 相關指令（如 `mvn`, `./mvnw`, `gradle`, `./gradlew`）應在 **Antigravity 環境** 或 **本機已配置的 JDK 17 環境** 中執行。
- 若使用 Docker 開發，所有 Java/Maven/Gradle 指令必須在容器內執行，例如：
  ```bash
  docker compose exec backend bash
  # 進入容器後
  ./mvnw spring-boot:run
  ```
- 請勿在未配置 JDK 的環境中執行 Java 指令，以確保環境一致性。

### 2.2 常用後端指令

- **啟動 Spring Boot**：
  ```bash
  ./mvnw spring-boot:run
  # 或
  ./gradlew bootRun
  ```
- **執行測試**：
  ```bash
  ./mvnw test
  # 或
  ./gradlew test
  ```
- **打包專案**：
  ```bash
  ./mvnw clean package
  # 或
  ./gradlew build
  ```
- **查看依賴**：
  ```bash
  ./mvnw dependency:tree
  # 或
  ./gradlew dependencies
  ```

---

## 3) React 前端指令執行規範

### 3.1 開發環境

- 所有前端指令（如 `npm`, `yarn`, `pnpm`）應在 **Antigravity 環境** 或 **本機已安裝 Node.js 18+ 的環境** 中執行。
- 若使用 Docker 開發，所有 npm/yarn 指令必須在容器內執行，例如：
  ```bash
  docker compose exec frontend bash
  # 進入容器後
  npm run dev
  ```

### 3.2 常用前端指令

- **安裝依賴**：
  ```bash
  npm install
  # 或
  yarn install
  ```
- **啟動開發伺服器**：
  ```bash
  npm run dev
  # 或
  yarn dev
  ```
- **執行測試**：
  ```bash
  npm test
  # 或
  yarn test
  ```
- **建置生產版本**：
  ```bash
  npm run build
  # 或
  yarn build
  ```
- **檢查 TypeScript 型別**：
  ```bash
  npx tsc --noEmit
  ```
- **執行 ESLint**：
  ```bash
  npm run lint
  # 或
  npx eslint src/
  ```

---

## 4) 專案結構與目錄對應

### 4.1 後端專案（Java + Spring Boot）

```
hololive-cardgame-backend/
├── src/
│   ├── main/
│   │   ├── java/com/hololive/cardgame/
│   │   │   ├── controller/      # REST API 控制器
│   │   │   ├── service/         # 業務邏輯層
│   │   │   ├── repository/      # 資料庫訪問層（JPA）
│   │   │   ├── entity/          # 資料庫實體（Entity）
│   │   │   ├── dto/             # 資料傳輸物件
│   │   │   ├── config/          # 配置類
│   │   │   └── CardgameApplication.java
│   │   └── resources/
│   │       ├── application.yml  # 應用配置
│   │       └── db/
│   │           └── schema.sql   # 資料庫 Schema
│   └── test/                    # 測試程式
├── pom.xml                       # Maven 依賴（或 build.gradle）
└── README.md
```

**常用操作路徑**：
- 新增 API：`src/main/java/.../controller/`
- 業務邏輯：`src/main/java/.../service/`
- 資料庫存取：`src/main/java/.../repository/`
- 配置修改：`src/main/resources/application.yml`

---

### 4.2 前端專案（React + TypeScript）

```
hololive-cardgame-frontend/
├── src/
│   ├── components/           # 可重用元件
│   ├── pages/               # 頁面元件
│   │   ├── Home.tsx
│   │   ├── Match.tsx
│   │   └── NotFound.tsx
│   ├── services/            # API 呼叫服務
│   │   └── api.ts
│   ├── hooks/               # Custom Hooks（如 useLiff）
│   ├── types/               # TypeScript 型別定義
│   ├── App.tsx              # 主應用元件
│   └── main.tsx             # 應用入口
├── public/                  # 靜態資源
├── .env                     # 環境變數（不提交到 Git）
├── package.json
├── tsconfig.json
└── vite.config.ts           # Vite 配置（或 react-scripts）
```

**常用操作路徑**：
- 新增頁面：`src/pages/`
- 新增元件：`src/components/`
- API 整合：`src/services/api.ts`
- LIFF 整合：`src/hooks/useLiff.ts`

---

## 5) 常用 Docker 指令（若使用 Docker）

### 5.1 基本操作

- **啟動所有服務**：
  ```bash
  docker compose up -d
  ```
- **查看服務狀態**：
  ```bash
  docker compose ps
  ```
- **查看日誌**：
  ```bash
  docker compose logs -f backend
  docker compose logs -f frontend
  ```
- **停止所有服務**：
  ```bash
  docker compose down
  ```
- **重啟特定服務**：
  ```bash
  docker compose restart backend
  docker compose restart frontend
  ```

### 5.2 進入容器執行指令

- **進入後端容器**：
  ```bash
  docker compose exec backend bash
  ```
- **進入前端容器**：
  ```bash
  docker compose exec frontend bash
  ```
- **進入資料庫容器**：
  ```bash
  docker compose exec postgres psql -U holocard_user -d holocardgame_db
  ```

---

## 6) 變更策略（避免誤改）

### 6.1 最小化修改原則

- 任何 edit 都必須最小化：只改必要行數，避免重排無關格式（除非我要求）。
- 若不確定某段邏輯是否被使用，先提出驗證路徑：
  - **後端**：檢查 Controller 路由映射（`@GetMapping`, `@PostMapping`）、Service 呼叫鏈
  - **前端**：檢查元件引用（`import`）、路由定義（`Route`）、API 呼叫點

### 6.2 高風險變更確認

涉及以下類型的修改，必須先列出影響面與回滾方式：
- **資料庫相關**：Schema 變更（`schema.sql`）、Entity 修改、Repository 查詢邏輯
- **API 變更**：REST API 路徑、請求/回應格式、HTTP 狀態碼
- **核心業務邏輯**：對戰規則（回合、アーツ、ホロパワー、LIFE 計算）
- **驗證與安全**：LINE Login、LIFF 初始化、使用者權限
- **前端狀態管理**：重要的 State/Context 修改、API 整合邏輯

### 6.3 修改前檢查清單

在進行修改前，請先確認：
1. 我已讀取並理解相關檔案的完整內容
2. 我已確認此修改不會破壞現有功能
3. 我已列出受影響的檔案清單（最多 3 個）
4. 我已提供驗證方式（API 測試、元件渲染、單元測試等）

---

## 7) TypeScript 與型別檢查

### 7.1 型別定義規範

- 所有前端 API 回應必須定義對應的 TypeScript interface，放在 `src/types/` 目錄
- 例如：
  ```typescript
  // src/types/api.ts
  export interface User {
    id: number;
    lineUserId: string;
    displayName: string;
    avatarUrl?: string;
  }

  export interface Match {
    id: number;
    roomCode: string;
    status: 'active' | 'finished' | 'abandoned';
    playerA: User;
    playerB: User;
  }
  ```

### 7.2 避免使用 `any`

- 除非絕對必要，否則不使用 `any` 型別
- 若無法確定型別，使用 `unknown` 並配合型別守衛（type guard）
- 第三方套件若無型別定義，優先安裝 `@types/` 套件

---

## 8) 測試與驗證

### 8.1 後端測試

- **單元測試**：測試 Service 層邏輯
  ```bash
  ./mvnw test -Dtest=UserServiceTest
  ```
- **整合測試**：測試 Controller + Repository
  ```bash
  ./mvnw test -Dtest=UserControllerIntegrationTest
  ```
- **API 測試**：使用 Postman、curl 或 REST Client 擴充測試 API

### 8.2 前端測試

- **元件測試**：使用 React Testing Library
  ```bash
  npm test -- Home.test.tsx
  ```
- **型別檢查**：
  ```bash
  npx tsc --noEmit
  ```
- **Lint 檢查**：
  ```bash
  npm run lint
  ```

### 8.3 端對端驗證流程

每次重要修改後，建議執行以下驗證：
1. 後端啟動無錯誤（`./mvnw spring-boot:run`）
2. 前端啟動無錯誤（`npm run dev`）
3. API 可正常呼叫（`curl http://localhost:8080/api/health`）
4. 前端可正常顯示（開啟 `http://localhost:5173`）
5. LIFF 登入流程正常（若已整合）

---

## 9) Git 與版本控制

### 9.1 Commit 訊息規範

使用語意化提交訊息（Conventional Commits）：
- `feat:` 新增功能
- `fix:` 修復 bug
- `refactor:` 重構程式碼
- `docs:` 文件更新
- `test:` 新增或修改測試
- `chore:` 雜項（依賴更新、建置配置等）

範例：
```bash
git commit -m "feat: 新增使用者登入 API"
git commit -m "fix: 修正對戰房間加入邏輯錯誤"
git commit -m "refactor: 重構 Match Service 結構"
```

### 9.2 分支策略

- `main`：穩定版本，隨時可部署
- `develop`：開發分支，整合所有功能
- `feature/*`：功能分支，例如 `feature/line-login`
- `bugfix/*`：修 bug 分支，例如 `bugfix/match-join-error`

---

## 10) 環境變數管理

### 10.1 後端環境變數

在 `src/main/resources/application.yml` 或透過環境變數設定：
```yaml
spring:
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/holocardgame_db}
    username: ${DATABASE_USERNAME:holocard_user}
    password: ${DATABASE_PASSWORD:holocard_password}
```

### 10.2 前端環境變數

在 `.env` 檔案中設定（不提交到 Git）：
```env
VITE_API_BASE_URL=http://localhost:8080/api
VITE_LIFF_ID=your-liff-id-here
```

在 `.env.production` 設定生產環境變數：
```env
VITE_API_BASE_URL=https://your-api-domain.com/api
VITE_LIFF_ID=production-liff-id
```

---

## 11) 常見問題排查

### 11.1 後端無法啟動

1. 檢查 `application.yml` 資料庫連線設定
2. 確認 PostgreSQL 服務是否啟動
3. 檢查 `pom.xml` 依賴是否正確
4. 查看完整錯誤訊息（不要只看片段）

### 11.2 前端 API 呼叫失敗

1. 檢查 CORS 設定（後端 `WebConfig.java`）
2. 確認 `.env` 中的 `VITE_API_BASE_URL` 正確
3. 使用瀏覽器開發者工具查看 Network 請求
4. 確認後端 API 確實在運行

### 11.3 LIFF 初始化失敗

1. 確認 `VITE_LIFF_ID` 正確
2. 檢查 LIFF Endpoint URL 設定
3. 確認在 LINE 內建瀏覽器中開啟（或使用 LIFF Inspector 測試）

---

## 12) 程式碼品質要求

### 12.1 Java 後端

- 使用 Lombok 減少 boilerplate code（`@Data`, `@Service`, `@RestController`）
- 遵循 RESTful API 設計原則
- Service 層處理業務邏輯，Controller 層只負責路由與參數驗證
- 使用 `Optional` 處理可能為 null 的值
- 適當使用 `@Transactional` 處理資料庫交易

### 12.2 React 前端

- 使用函數式元件 + Hooks
- 避免在元件內寫複雜業務邏輯，抽取到 Custom Hooks 或 Service
- 使用 `React.FC` 型別標註元件
- 適當使用 `useMemo` 和 `useCallback` 優化效能
- 所有 useEffect 必須正確設定依賴陣列


### 12.3 共通部分

- 盡可能的在方法, 類別, 檔案, 函式, 變數以及標籤加上繁體中文註解

## 總結

本規範適用於「HOLOLIVE 卡牌遊戲」專案的 **Java Spring Boot 後端** 與 **React TypeScript 前端** 開發。所有開發與修改必須遵循本文件規範，確保程式碼品質與專案一致性。
