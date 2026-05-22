# 193-MatchEffect Extra Bloom Allowance Effect Execution Service Extraction Acceptance Review

日期：2026-05-22
狀態：已完成

## 背景

本批延續 production code 主線拆分，在 `ACTION_LOCK` execution service 收斂後，只處理 `ALLOW_EXTRA_BLOOM`，不混入 `BATON_TOUCH_COST_MODIFIER`、`DISCARD_HAND` 或高風險戰鬥規則。

選擇 `ALLOW_EXTRA_BLOOM` 的原因：

- 屬於 `match_turn_effects` 寫入流程，責任可從 `MatchEffectService` 分離。
- 目前邏輯同時被 support / gift / Bloom / Collab 與 Bloom 後靜態 Gift 使用，集中到專用 service 可降低主 service execution bridge。
- 這批可順手補上 focused unit test，保護 Life、推し、對手 stage 與本回合 Bloom 目標判斷。

## 本批完成內容

- 新增 `MatchExtraBloomAllowanceEffectExecutionService` package-private component。
- 從 `MatchEffectService` 搬出 `ALLOW_EXTRA_BLOOM` 執行流程：
  - raw text no-op 判斷。
  - Life 門檻判斷。
  - 推し名稱條件判斷。
  - 對手 stage 1st Holomem 條件判斷。
  - 本回合已 Bloom 目標查詢。
  - duplicate allowance 檢查。
  - `match_turn_effects` insert。
  - summary payload 組裝。
- `MatchEffectService` 的 support / gift dispatch 改為委派新 service。
- `MatchBloomEffectDispatcher`、`MatchCollabEffectDispatcher` 改為直接委派新 service。
- `MatchEffectService` 保留 `executeAllowExtraBloomEffect(...)` wrapper，維持 `MatchTriggeredCardEffectService` 既有入口相容。
- 新增 `MatchExtraBloomAllowanceEffectExecutionServiceTest`。
- 修正 allowance payload 使用 `Map.of(...)` 時 `holderCardInstanceId = null` 會 NPE 的風險，改以 `LinkedHashMap` 組 payload。

## 責任邊界

`MatchExtraBloomAllowanceEffectExecutionService` 負責：

- `ALLOW_EXTRA_BLOOM` no-op summary。
- Life / 推し / 對手 stage 條件。
- 目標 Holomem 查詢與 allowed names 篩選。
- duplicate allowance 檢查。
- `match_turn_effects` 寫入。
- `ALLOW_EXTRA_BLOOM` summary payload 組裝。

`MatchEffectService` 保留：

- support / gift 高階 dispatch。
- Bloom 後靜態 Gift 相容 wrapper。
- 目前回合、推し條件解析、對手 stage 條件、名稱比對等 shared helper callback。
- 其他 effect family execution。

本批未處理：

- `BATON_TOUCH_COST_MODIFIER`。
- `DISCARD_HAND`。
- `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
- 公開 API、資料庫 migration 或 seed data。

## 行數變化

- `MatchEffectService.java`：`10,305` 行 -> `10,094` 行，減少 `211` 行。
- 新增 `MatchExtraBloomAllowanceEffectExecutionService.java`：`352` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchExtraBloomAllowanceEffectExecutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerOfficialGiftHbp06027AndGrantExtraBloomAllowance test`
- `./mvnw -q -Dtest=MatchBloomEffectIntegrationTest#bloomShouldAllowOfficialGiftHsd10004ToGrantSecondBloomWhenConditionsSatisfied test`
- `./mvnw -q -Dtest=MatchBloomEffectIntegrationTest#bloomShouldNotGrantOfficialGiftHsd10004SecondBloomWhenOshiConditionFails test`

補充：

- focused integration tests 需要 Testcontainers / PostgreSQL。
- 沙盒內首次執行曾因 Docker/PostgreSQL socket `Operation not permitted` 失敗；提高權限後驗證通過。

## 判讀

- `MatchEffectService` 不再持有 `ALLOW_EXTRA_BLOOM` 的條件判斷、目標查詢、turn effect insert SQL 與 summary payload 組裝。
- Bloom / Collab dispatcher 已直接依賴具體 extra bloom allowance execution service，主 service 的 execution bridge 進一步縮小。
- 本批保留既有入口相容，只搬移既有行為並修正 null holder payload 組裝風險。

## 下一步建議

下一批建議抽 `BATON_TOUCH_COST_MODIFIER`：

- 與 `ACTION_LOCK`、`ALLOW_EXTRA_BLOOM` 同屬 `match_turn_effects` 類型寫入。
- 範圍比 `DISCARD_HAND` 更集中，適合在進入 criteria / cost parser 高風險拆分前先收斂。
- 暫緩 `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE` / passive gift，避免把高風險規則改動和低風險拆分混在同一批。

建議下一個 commit 訊息：

```text
後端：抽出 Baton Touch 成本修正效果執行服務
```
