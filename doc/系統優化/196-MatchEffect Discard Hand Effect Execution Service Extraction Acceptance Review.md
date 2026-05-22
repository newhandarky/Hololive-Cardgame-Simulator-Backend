# 196-MatchEffect Discard Hand Effect Execution Service Extraction Acceptance Review

日期：2026-05-22
狀態：已完成

## 背景

本批延續 production code 主線拆分，在 `MATCH_RESULT` execution service 收斂後，正式處理 `DISCARD_HAND`。這批風險高於前幾個小型 execution，因為它牽涉成本段解析、`SearchCriteria`、手牌候選查詢與 HAND -> ARCHIVE 移動。

選擇 `DISCARD_HAND` 的原因：

- 已多次在前序批次中列為高收益拆分點。
- 能移出 `MatchEffectService` 中一段完整相鄰 execution responsibility。
- 可用 focused unit test 鎖住 criteria / cost clause 與 hand movement 行為。

## 本批完成內容

- 新增 `MatchDiscardHandEffectExecutionService` package-private component。
- 從 `MatchEffectService` 搬出 `DISCARD_HAND` 執行流程：
  - raw text 成本段擷取。
  - 成本段 `SearchCriteria` 解析。
  - discard count 解析。
  - 無 criteria 時依 HAND 排序自動挑選。
  - 有 criteria 時從 HAND candidates 過濾。
  - HAND -> ARCHIVE zone update。
  - `discardCriteria` 與 discarded ids summary payload 組裝。
- `MatchEffectService` 的 support / gift dispatch 改為委派新 service。
- `MatchBloomEffectDispatcher`、`MatchCollabEffectDispatcher` 改為直接委派新 service。
- 新增 `MatchDiscardHandEffectExecutionServiceTest`。

## 責任邊界

`MatchDiscardHandEffectExecutionService` 負責：

- `DISCARD_HAND` 成本段解析與 discard criteria 建立。
- HAND 候選查詢與裁切到 requested count。
- HAND -> ARCHIVE 更新與 archive order 計算。
- discard summary payload 組裝。

`MatchEffectService` 保留：

- support / gift 高階 dispatch。
- 共用 `SearchCriteriaParser`、`MatchEffectSearchService` 與 `MatchCardSelectionRequestResolver` 的建構與注入。
- 其他 effect family execution。

本批未處理：

- 互動式選手牌流程。
- `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
- `SUMMON_TO_STAGE` / `REVEAL_TO_ARCHIVE`。
- 公開 API、資料庫 migration 或 seed data。

## 行數變化

- `MatchEffectService.java`：`9,914` 行 -> `9,831` 行，減少 `83` 行。
- 新增 `MatchDiscardHandEffectExecutionService.java`：`175` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchDiscardHandEffectExecutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomHbp04059ShouldDiscardOneHandAndDrawByOddRollCount test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomHbp04059ShouldSkipWhenNoHandCardAvailableForCost test`

補充：

- focused integration tests 需要 Testcontainers / PostgreSQL。
- 沙盒內首次執行曾因 Docker/PostgreSQL socket `Operation not permitted` 失敗；提高權限後驗證通過。
- `git diff --check` 於 commit 前執行。

## 判讀

- `MatchEffectService` 不再持有棄手牌的成本段解析、HAND candidate 查詢、HAND -> ARCHIVE update SQL 與 discard summary 組裝。
- Bloom / Collab dispatcher 已直接依賴具體 discard hand execution service，主 service 的 execution bridge 進一步縮小。
- 本批只搬移既有行為，沒有新增互動式選牌流程，也沒有修改公開 API 或資料庫 schema。

## 下一步建議

若本批驗證穩定，下一批可評估：

1. `REVEAL_TO_ARCHIVE`：同樣使用 criteria / candidate / zone update，但風險低於戰鬥類。
2. `SUMMON_TO_STAGE`：收益較高，但牽涉 stage zone 與 stack 記錄，需先補 focused tests。
3. 繼續暫緩 `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`，避免和候選查詢類拆分混在同一批。
