# 195-MatchEffect Match Result Effect Execution Service Extraction Acceptance Review

日期：2026-05-22
狀態：已完成

## 背景

本批延續 production code 主線拆分，在 `BATON_TOUCH_COST_MODIFIER` execution service 收斂後，只處理 `MATCH_RESULT` / `WIN` / `LOSE` 勝負結果效果。

選擇 `MATCH_RESULT` 的原因：

- 範圍集中在勝負結果解析與 summary payload 組裝。
- 不牽涉資料庫 schema、公開 API、候選查詢或戰鬥傷害規則。
- 可在進入 `DISCARD_HAND` 這類 criteria / cost parser 高風險拆分前，先繼續縮小 `MatchEffectService` execution bridge。

## 本批完成內容

- 新增 `MatchResultEffectExecutionService` package-private component。
- 從 `MatchEffectService` 搬出 `MATCH_RESULT` / `WIN` / `LOSE` 執行流程：
  - structured `result` / `outcome` / `matchResult` 解析。
  - `winner` / `loser` side token 解析。
  - raw text fallback 勝負解析。
  - no-op summary。
  - `matchResult` summary payload 組裝。
- `MatchEffectService` 的 support / gift dispatch 改為委派新 service。
- `MatchBloomEffectDispatcher`、`MatchCollabEffectDispatcher` 改為直接委派新 service。
- 新增 `MatchResultEffectExecutionServiceTest`。

## 責任邊界

`MatchResultEffectExecutionService` 負責：

- `MATCH_RESULT` / `WIN` / `LOSE` / `DRAW` 類勝負結果解析。
- winner / loser user id 推導。
- reason code 推導。
- no-op 與成功 summary payload 組裝。

`MatchEffectService` 保留：

- support / gift 高階 dispatch。
- opponent user resolver，透過 callback 提供給新 service。
- 其他 effect family execution。

本批未處理：

- `DISCARD_HAND`。
- `SUMMON_TO_STAGE` / `REVEAL_TO_ARCHIVE`。
- `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
- 公開 API、資料庫 migration 或 seed data。

## 行數變化

- `MatchEffectService.java`：`10,047` 行 -> `9,914` 行，減少 `133` 行。
- 新增 `MatchResultEffectExecutionService.java`：`167` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchResultEffectExecutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportShouldFinishMatchImmediatelyWhenCardEffectDeclaresWin test`

補充：

- focused integration test 需要 Testcontainers / PostgreSQL。
- 沙盒內首次執行曾因 Docker/PostgreSQL socket `Operation not permitted` 失敗；提高權限後驗證通過。
- `git diff --check` 於 commit 前執行。

## 判讀

- `MatchEffectService` 不再持有勝負結果解析、side token 推導與 match result summary payload 組裝。
- Bloom / Collab dispatcher 已直接依賴具體 Match Result execution service，主 service 的 execution bridge 進一步縮小。
- 本批只搬移既有行為，沒有修改公開 API、資料庫 migration 或核心規則語意。

## 下一步建議

下一批建議正式進入 `DISCARD_HAND` 前置保護：

1. 先補 criteria / cost parser 與 hand candidate focused tests。
2. 再抽 `MatchDiscardHandEffectExecutionService`。
3. 不要同批處理 `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
