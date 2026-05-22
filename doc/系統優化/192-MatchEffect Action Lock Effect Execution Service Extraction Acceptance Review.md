# 192-MatchEffect Action Lock Effect Execution Service Extraction Acceptance Review

日期：2026-05-22
狀態：已完成

## 背景

本批延續 production code 主線拆分，在 `SWAP_WITH_COLLAB` execution service 收斂後，只處理 `ACTION_LOCK`，不混入 `ALLOW_EXTRA_BLOOM` 或高風險戰鬥規則。

選擇 `ACTION_LOCK` 的原因：

- 屬於 turn effect 寫入流程，責任集中在 raw text 解析與 `match_turn_effects` insert。
- 風險低於 `ALLOW_EXTRA_BLOOM`，後者仍依賴 Life、推し、對手場上等級與本回合 Bloom 目標判斷。
- 可繼續縮小 `MatchEffectService` execution bridge。

## 本批完成內容

- 新增 `MatchActionLockEffectExecutionService` package-private component。
- 從 `MatchEffectService` 搬出 `ACTION_LOCK` 執行流程：
  - raw text 解析封鎖 action。
  - CENTER / COLLAB zone 解析。
  - affected user 判斷。
  - expires turn 判斷。
  - specific Holomem target 解析。
  - `match_turn_effects` insert。
  - summary payload 組裝。
- `MatchEffectService` 的 support / gift dispatch 改為委派新 service。
- `MatchBloomEffectDispatcher`、`MatchCollabEffectDispatcher` 改為直接委派新 service，不再經由 `MatchEffectService` action lock wrapper。
- 新增 `MatchActionLockEffectExecutionServiceTest`。
- 沿用既有 focused integration：
  - 支援卡 `ACTION_LOCK` 阻擋 Baton Touch。
  - 支援卡 `ACTION_LOCK` 阻擋指定 Holomem Bloom。

## 責任邊界

`MatchActionLockEffectExecutionService` 負責：

- `ACTION_LOCK` no-op summary。
- raw text action / zone 解析。
- affected user 與 expires turn 判斷。
- specific target payload 組裝。
- `match_turn_effects` 寫入。
- `ACTION_LOCK` summary payload 組裝。

`MatchEffectService` 保留：

- support / gift 高階 dispatch。
- 對手、目前回合、效果目標與 card instance resolver，透過 callback 提供給新 service。
- `isActionLockActive(...)` 等 action lock 讀取與命中判斷 helper。
- 其他 effect family execution。

本批未處理：

- `ALLOW_EXTRA_BLOOM`。
- `DISCARD_HAND`。
- `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
- 公開 API、資料庫 migration 或 seed data。

## 行數變化

- `MatchEffectService.java`：`10,382` 行 -> `10,305` 行，減少 `77` 行。
- 新增 `MatchActionLockEffectExecutionService.java`：`172` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchActionLockEffectExecutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#batonTouchShouldBeBlockedByActionLockEffect test`
- `./mvnw -q -Dtest=MatchBloomEffectIntegrationTest#actionLockBloomShouldBlockBloomOnTargetHolomem test`
- `git diff --check`

補充：

- focused integration tests 需要 Testcontainers / PostgreSQL。
- 沙盒內首次執行因 Docker/PostgreSQL socket `Operation not permitted` 失敗；提高權限後驗證通過。

## 判讀

- `MatchEffectService` 不再持有 `ACTION_LOCK` 的 raw text 解析、turn effect insert SQL 與 summary payload 組裝。
- Bloom / Collab dispatcher 已直接依賴具體 action lock execution service，主 service 的 execution bridge 進一步縮小。
- 本批只搬移既有行為，沒有修改公開 API、資料庫 migration 或核心規則語意。

## 下一步建議

下一批有兩個合理方向：

1. 若要維持低風險，可抽 `ALLOW_EXTRA_BLOOM`，但先整理 helper callback 邊界。
2. 若要提高收益，開始規劃 `DISCARD_HAND`，但需先補 criteria / cost parser 與候選查詢 focused tests。

建議下一個 commit 視選擇方向決定；若走低風險主線，可使用：

```text
後端：抽出額外 Bloom 許可效果執行服務
```
