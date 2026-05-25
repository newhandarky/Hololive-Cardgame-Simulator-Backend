# 198-MatchEffect Summon To Stage Effect Execution Service Extraction Acceptance Review

日期：2026-05-25
狀態：已完成

## 背景

本批延續 production code 主線拆分，在 `REVEAL_TO_ARCHIVE` execution service 收斂後，處理同樣屬於 deck candidate / stage zone update 類型的 `SUMMON_TO_STAGE`。

選擇 `SUMMON_TO_STAGE` 的原因：

- 它仍在 `MatchEffectService` 中持有候選查詢、stage zone 判斷、Holomem 建立與 stack 記錄。
- 風險高於純 zone move，但仍低於 `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE` 等戰鬥核心效果。
- 可用 focused unit test 鎖住 DECK -> STAGE、CENTER/BACK 容量判斷、level normalization 與 summary payload。

## 本批完成內容

- 新增 `MatchSummonToStageEffectExecutionService` package-private component。
- 從 `MatchEffectService` 搬出 `SUMMON_TO_STAGE` 執行流程：
  - action count 解析。
  - `SearchCriteria` 解析並強制限定 `MEMBER`。
  - DECK candidate 查詢與裁切。
  - stage 目標區位解析與容量檢查。
  - DECK -> STAGE zone update。
  - `match_holomems` 建立。
  - Holomem stack card 關聯建立。
  - summoned ids / zones 與 criteria summary payload 組裝。
- `MatchEffectService` 的 support / gift dispatch 改為委派新 service。
- `MatchBloomEffectDispatcher`、`MatchCollabEffectDispatcher` 改為直接委派新 service。
- 新增 `MatchSummonToStageEffectExecutionServiceTest`。
- 補正既有 Bloom summon integration test：目前 Bloom effect 需先確認 `TRIGGER_EFFECT_CONFIRM` pending decision 才會真正執行效果，因此測試需先 resolve pending interaction。

## 責任邊界

`MatchSummonToStageEffectExecutionService` 負責：

- `SUMMON_TO_STAGE` 的 DECK candidate 查詢、數量裁切、stage 容量判斷與 DECK -> STAGE 更新。
- 建立 `match_holomems` 與 `match_holomem_stack_cards` 關聯。
- 保留原本預設 BACK、CENTER 可用時優先 CENTER、BACK 上限 5 張的區位判斷。
- 建立 summoned card / Holomem / zone summary payload。

`MatchEffectService` 保留：

- support / gift 高階 dispatch。
- Bloom / Collab orchestration 的入口。
- 其他尚未拆出的 effect family execution。

本批未處理：

- `BLOOM_FROM_ARCHIVE`。
- `RETURN_CHEER_TO_DECK_BOTTOM`。
- `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
- 公開 API、資料庫 migration 或 seed data。

## 行數變化

- `MatchEffectService.java`：`9,780` 行 -> `9,675` 行，減少 `105` 行。
- 新增 `MatchSummonToStageEffectExecutionService.java`：`265` 行。
- 新增 `MatchSummonToStageEffectExecutionServiceTest.java`：`225` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchSummonToStageEffectExecutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomShouldTriggerSummonToStageEffectFromPassiveText test`

補充：

- integration test 需要 Testcontainers / PostgreSQL。
- 沙盒內首次執行 integration test 因 Docker/PostgreSQL socket `Operation not permitted` 失敗；提高權限後可執行。
- 本批調查到 `bloomShouldTriggerSummonToStageEffectFromPassiveText` 既有測試少了 `TRIGGER_EFFECT_CONFIRM` pending decision resolve，導致 effect 尚未執行就檢查 zone。已補上這個測試流程步驟，對齊同檔其他 Bloom triggered effect 測試。
- `git diff --check` 於 commit 前執行。

## 判讀

- `MatchEffectService` 不再持有登場效果的 DECK candidate 查詢、stage zone update、Holomem row 建立與 stack 記錄。
- Bloom / Collab dispatcher 已直接依賴具體 summon-to-stage execution service，主 service 的 execution bridge 再縮小。
- 本批只搬移既有行為，沒有修改公開 API 或資料庫 schema。

## 下一步建議

下一批建議處理：

1. `BLOOM_FROM_ARCHIVE`：規則流程集中，但牽涉 archive selection、Bloom stack 與 stage 狀態，需先補 focused unit test。
2. `RETURN_CHEER_TO_DECK_BOTTOM`：若想維持低中風險，可先抽這個較小的 zone movement effect。
3. 繼續暫緩 `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`，避免過早觸碰核心戰鬥規則。
