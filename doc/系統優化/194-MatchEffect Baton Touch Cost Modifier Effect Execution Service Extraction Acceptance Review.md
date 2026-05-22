# 194-MatchEffect Baton Touch Cost Modifier Effect Execution Service Extraction Acceptance Review

日期：2026-05-22
狀態：已完成

## 背景

本批延續 production code 主線拆分，在 `ALLOW_EXTRA_BLOOM` execution service 收斂後，只處理 `BATON_TOUCH_COST_MODIFIER`，不混入 `DISCARD_HAND` 或高風險戰鬥規則。

選擇 `BATON_TOUCH_COST_MODIFIER` 的原因：

- 同屬 `match_turn_effects` 寫入流程，責任集中在 modifier 解析、目標解析與 turn effect insert。
- 範圍比 `DISCARD_HAND` 更小，適合在進入 criteria / cost parser 高風險拆分前先收斂。
- 可繼續縮小 `MatchEffectService` execution bridge。

## 本批完成內容

- 新增 `MatchBatonTouchCostModifierEffectExecutionService` package-private component。
- 從 `MatchEffectService` 搬出 `BATON_TOUCH_COST_MODIFIER` 執行流程：
  - raw text / structured modifier 解析。
  - 無有效 modifier 時 no-op。
  - 指定 Holomem 目標解析。
  - 目標缺失時 fallback 自家或對手 CENTER。
  - `match_turn_effects` insert。
  - summary payload 組裝。
- `MatchEffectService` 的 support / gift dispatch 改為委派新 service。
- `MatchBloomEffectDispatcher`、`MatchCollabEffectDispatcher` 改為直接委派新 service。
- `MatchEffectService` 保留 `executeBatonTouchCostModifierEffect(...)` wrapper，維持既有內部 call-site 相容。
- 新增 `MatchBatonTouchCostModifierEffectExecutionServiceTest`。

## 責任邊界

`MatchBatonTouchCostModifierEffectExecutionService` 負責：

- `BATON_TOUCH_COST_MODIFIER` no-op summary。
- modifier 解析。
- fallback CENTER 查詢。
- `BATON_TOUCH_COLORLESS_MODIFIER` turn effect 寫入。
- summary payload 組裝。

`MatchEffectService` 保留：

- support / gift 高階 dispatch。
- 目標 Holomem、對手玩家、目前回合、Holomem owner 與 card instance resolver，透過 callback 提供給新 service。
- 其他 effect family execution。

本批未處理：

- `DISCARD_HAND`。
- `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
- 公開 API、資料庫 migration 或 seed data。

## 行數變化

- `MatchEffectService.java`：`10,094` 行 -> `10,047` 行，減少 `47` 行。
- 新增 `MatchBatonTouchCostModifierEffectExecutionService.java`：`169` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchBatonTouchCostModifierEffectExecutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#batonTouchShouldApplyColorlessModifierBeforeCostValidation test`

補充：

- focused integration test 需要 Testcontainers / PostgreSQL。
- 整合測試以提高權限執行，確保 Docker socket 與 PostgreSQL 連線可用。

## 判讀

- `MatchEffectService` 不再持有 Baton Touch 成本修正的 modifier 解析、fallback CENTER 查詢、turn effect insert SQL 與 summary payload 組裝。
- Bloom / Collab dispatcher 已直接依賴具體 Baton Touch cost modifier execution service，主 service 的 execution bridge 進一步縮小。
- 本批只搬移既有行為，沒有修改公開 API、資料庫 migration 或核心規則語意。

## 下一步建議

下一批有兩個合理方向：

1. 若要維持低風險，可抽小型 turn effect / stage effect execution。
2. 若要提高收益，可開始規劃 `DISCARD_HAND`，但需先補 criteria / cost parser 與候選查詢 focused tests。

建議下一批若進入 `DISCARD_HAND`，先做測試保護與 parser / candidate 邊界盤點，不要同批搬動高風險戰鬥規則。
