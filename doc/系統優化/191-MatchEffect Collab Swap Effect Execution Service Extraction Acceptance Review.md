# 191-MatchEffect Collab Swap Effect Execution Service Extraction Acceptance Review

日期：2026-05-22
狀態：已完成

## 背景

本批延續 production code 主線拆分，在 `SWAP_CENTER_BACK` execution service 收斂後，只處理 `SWAP_WITH_COLLAB`，不混入 `DISCARD_HAND` 或高風險戰鬥規則。

選擇 `SWAP_WITH_COLLAB` 的原因：

- 與 `REST`、`SWAP_CENTER_BACK` 同屬 stage 位置操作。
- 責任集中在來源 Holomem、COLLAB 目標查詢與 zone 更新。
- 風險低於 `DISCARD_HAND`，可先繼續縮小 `MatchEffectService` execution bridge。

## 本批完成內容

- 新增 `MatchCollabSwapEffectExecutionService` package-private component。
- 從 `MatchEffectService` 搬出 `SWAP_WITH_COLLAB` 執行流程：
  - 來源 Holomem 解析。
  - 未指定來源時 fallback 第一個 BACK。
  - source 存在性與 `バックポジション限定` 檢查。
  - COLLAB 目標查詢。
  - `残りHP70以下` 條件篩選。
  - source / COLLAB zone 更新。
  - 交換結果 summary payload。
- `MatchEffectService` 的 support / gift dispatch 改為委派新 service。
- `MatchBloomEffectDispatcher` 與 `MatchCollabEffectDispatcher` 改為直接委派新 service，不再經由 `MatchEffectService` swap-with-collab wrapper。
- 新增 `MatchCollabSwapEffectExecutionServiceTest`。
- 沿用既有 focused integration：Bloom passive text 觸發 `SWAP_WITH_COLLAB` 後確認 BACK / COLLAB 交換。

## 責任邊界

`MatchCollabSwapEffectExecutionService` 負責：

- `SWAP_WITH_COLLAB` no-op summary。
- 來源 Holomem fallback 查詢。
- source / COLLAB 目標查詢。
- `バックポジション限定` 與 `残りHP70以下` 條件判斷。
- `match_holomems.zone` 更新。
- `SWAP_WITH_COLLAB` summary payload 組裝。

`MatchEffectService` 保留：

- support / gift 高階 dispatch。
- 目標 Holomem 與 card instance resolver，透過 callback 提供給新 service。
- 其他 effect family execution。

本批未處理：

- `DISCARD_HAND`。
- `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
- passive gift 大型規則。
- 公開 API、資料庫 migration 或 seed data。

## 行數變化

- `MatchEffectService.java`：`10,497` 行 -> `10,382` 行，減少 `115` 行。
- 新增 `MatchCollabSwapEffectExecutionService.java`：`201` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchCollabSwapEffectExecutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomShouldTriggerSwapWithCollabEffectFromPassiveText test`
- `git diff --check`

補充：

- focused integration test 需要 Testcontainers / PostgreSQL。
- 沙盒內首次執行因 Docker/PostgreSQL socket `Operation not permitted` 失敗；提高權限後驗證通過。

## 判讀

- `MatchEffectService` 不再持有 `SWAP_WITH_COLLAB` 的 stage 查詢、zone 更新 SQL 與 summary payload 組裝。
- Bloom / Collab dispatcher 直接依賴 `MatchCollabSwapEffectExecutionService`，主 service 的 execution bridge 繼續縮小。
- 本批只搬移既有行為，沒有新增 dice gate 或規則語意。

## 下一步建議

下一批有兩個合理方向：

1. 若要維持低風險，抽 `ACTION_LOCK` 或 `ALLOW_EXTRA_BLOOM` 這類小型 execution。
2. 若要提高收益，開始規劃 `DISCARD_HAND`，但需先補 criteria / cost parser 與候選查詢 focused tests。

建議下一個 commit 視選擇方向決定；若走低風險主線，可使用：

```text
後端：抽出小型效果執行服務
```
