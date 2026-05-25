# 197-MatchEffect Reveal To Archive Effect Execution Service Extraction Acceptance Review

日期：2026-05-25
狀態：已完成

## 背景

本批延續 production code 主線拆分，在 `DISCARD_HAND` execution service 收斂後，處理同樣屬於 criteria / candidate / zone update 類型的 `REVEAL_TO_ARCHIVE`。

選擇 `REVEAL_TO_ARCHIVE` 的原因：

- 與 `DISCARD_HAND` 相依結構接近，可沿用 `SearchCriteriaParser`、`MatchEffectSearchService` 與 zone order pattern。
- 風險低於 `SUMMON_TO_STAGE` 與戰鬥類效果。
- 可用 focused unit test 鎖住 DECK -> ARCHIVE 移動與 summary payload。

## 本批完成內容

- 新增 `MatchRevealToArchiveEffectExecutionService` package-private component。
- 從 `MatchEffectService` 搬出 `REVEAL_TO_ARCHIVE` 執行流程：
  - action count 解析。
  - `SearchCriteria` 解析。
  - DECK candidate 查詢與裁切。
  - DECK -> ARCHIVE zone update。
  - `is_face_down = FALSE` 與 archive `order_index` 更新。
  - archived ids 與 criteria summary payload 組裝。
- `MatchEffectService` 的 support / gift dispatch 改為委派新 service。
- `MatchBloomEffectDispatcher`、`MatchCollabEffectDispatcher` 改為直接委派新 service。
- 新增 `MatchRevealToArchiveEffectExecutionServiceTest`。

## 責任邊界

`MatchRevealToArchiveEffectExecutionService` 負責：

- `REVEAL_TO_ARCHIVE` 的候選查詢、數量裁切、DECK -> ARCHIVE 更新與 summary payload。
- 將公開後的卡片翻為正面。
- 透過既有 search service 建立 criteria summary。

`MatchEffectService` 保留：

- support / gift 高階 dispatch。
- 共用 `SearchCriteriaParser`、`MatchEffectSearchService` 與 `MatchCardSelectionRequestResolver` 的建構與注入。
- 其他 effect family execution。

本批未處理：

- `SUMMON_TO_STAGE`。
- `BLOOM_FROM_ARCHIVE` / `RETURN_CHEER_TO_DECK_BOTTOM`。
- `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
- 公開 API、資料庫 migration 或 seed data。

## 行數變化

- `MatchEffectService.java`：`9,831` 行 -> `9,780` 行，減少 `51` 行。
- 新增 `MatchRevealToArchiveEffectExecutionService.java`：`113` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchRevealToArchiveEffectExecutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerOfficialGiftHbp06020WhenSelfDownedOnOpponentTurnAndDrawByDistinctFlowGlowNames test`

補充：

- integration test 需要 Testcontainers / PostgreSQL。
- 沙盒內首次執行 `MatchActionServiceIntegrationTest#bloomShouldTriggerRevealToArchiveEffectFromPassiveText` 因 Docker/PostgreSQL socket `Operation not permitted` 失敗；提高權限後可執行，但該測試在目前基準 commit `a3043bf` 也會失敗，原因是既有測試期待指定插入卡立即進 ARCHIVE，非本批 refactor regression。
- 因上述 baseline 失敗，本批改以 HBP06-020 的 `REVEAL_TO_ARCHIVE` Gift flow 作為 focused integration gate。
- `git diff --check` 於 commit 前執行。

## 判讀

- `MatchEffectService` 不再持有公開歸檔的 DECK candidate 查詢、DECK -> ARCHIVE update SQL 與 summary payload 組裝。
- Bloom / Collab dispatcher 已直接依賴具體 reveal-to-archive execution service，主 service 的 execution bridge 進一步縮小。
- 本批只搬移既有行為，沒有修改公開 API 或資料庫 schema。

## 下一步建議

下一批建議處理：

1. `SUMMON_TO_STAGE`：收益較高，但牽涉 stage zone、level normalization、stack 記錄與 destination 判斷，需先補 focused unit test。
2. `BLOOM_FROM_ARCHIVE`：規則流程集中，但和 stack / archive selection 相關，建議排在 stage summon 後評估。
3. 繼續暫緩 `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`，避免過早觸碰核心戰鬥規則。
