# AGENTS.md

## 後端入口規範

此目錄是 Spring Boot / Java 17 / Maven 後端 repository。代理人進入後端工作時，應把本檔視為第一入口，優先維持遊戲規則正確性、既有對戰流程相容性，以及可分段驗證的重構節奏。

## 溝通與工作節奏

- 回覆使用繁體中文，技術名詞、指令、檔案路徑可保留英文。
- commit 訊息使用繁體中文。
- 修改前先執行 `git status --short --branch`，確認目前工作區狀態。
- 不要回退使用者或其他工作中的變更。
- 一般後端工作以自動完成為預設；涉及資料庫 migration、公開 API 契約、核心對戰規則、安全/登入流程時，先說明影響面、回滾方式與驗證方式。
- 若做到一個適合 commit 的段落，回報時同時提供繁體中文 commit 訊息。

## 必讀文件

依任務範圍優先閱讀下列文件，不要只憑檔名或片段推測：

- 重構總覽：`doc/系統優化/00-系統優化總覽.md`
- 重構進度：`doc/系統優化/05-重構進度追蹤.md`
- MatchEffect 拆分：`doc/系統優化/01-MatchEffectService拆分路線圖.md`
- MatchAction 拆分：`doc/系統優化/02-MatchActionService拆分路線圖.md`
- REST API：`doc/0212-REST API 契約規範（最小集）.md`
- WebSocket：`doc/0212-WebSocket 通訊規範.md`
- 對戰引擎：`doc/0212-對戰引擎規格文件.md`
- 文件索引：`doc/文件索引與維護規範.md`

若文件與程式碼不一致，先以現有測試與實際程式行為為準，並在回報中指出文件落差。

## 重構熱點

- `MatchEffectService`、`MatchActionService`、大型 integration test 是主要重構熱點。
- 拆分大型 service 時採小步驟：先用測試鎖定既有行為，再抽出 helper/service，最後更新 `doc/系統優化/`。
- 優先抽出純邏輯、摘要建構、候選清單建構、條件解析、payload 組裝等低副作用區塊。
- 避免一次混合大量重構、行為修改與格式化，除非使用者明確要求。
- 若發現既有測試失敗但與本次變更無直接關係，需保留證據並在回報中說明。

## 後端變更流程

- 先用 `rg` 或精準檔案讀取定位定義、呼叫點與相關測試。
- 宣稱 bug 或行為差異前，需讀到定義、使用處與相關注入/呼叫鏈。
- 行為變更優先新增或調整聚焦測試；重構則需保留既有測試語意。
- 文件更新放在 `doc/系統優化/`，並同步更新總覽與進度追蹤。
- 不要修改與任務無關的 migration、seed data、格式或命名。

## 驗證矩陣

- 文件或註解變更：`git diff --check`
- 一般 Java 編譯檢查：`./mvnw -q -DskipTests compile`
- 聚焦單元測試：`./mvnw -q -Dtest=ClassName test`
- 聚焦 integration test：`./mvnw -q -Dtest=MatchActionServiceIntegrationTest#methodName test`
- 完整測試較耗時且可能需要 Docker / Testcontainers；若沙盒權限不足，依使用者授權提高權限重跑。

回報時列出實際執行過的指令與結果；未執行的驗證需說明原因。

## Commit 規則

- 一個 commit 只包含一個清楚段落，例如「抽 helper + 對應測試 + 文件紀錄」。
- 不要把前端變更提交到後端 repo。
- 建議訊息：
  - `後端：抽出卡牌選擇摘要建構器`
  - `後端：補強搜尋效果重構測試`
  - `文件：完善後端代理人協作規範`
  - `文件：更新後端重構進度`
