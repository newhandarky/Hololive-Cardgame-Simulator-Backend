# 187-MatchEffect Draw Effect Execution Service Extraction Acceptance Review

日期：2026-05-21
狀態：已完成

## 背景

本批延續 production code 主線拆分，在 Look 類效果收斂後，只處理 `DRAW` execution，不混入其他 effect family。

選擇 `DRAW` 的原因：

- 抽牌效果範圍集中，行為明確。
- 已有 Action Pipeline 與 SQL fallback 的既有流程可被 focused unit test 鎖定。
- Bloom / Collab dispatcher 已可直接依賴具體 execution service，適合繼續削減 `MatchEffectService` execution bridge。

## 本批完成內容

- 新增 `MatchDrawEffectExecutionService` package-private component。
- 從 `MatchEffectService` 搬出 `DRAW` 執行流程：
  - 抽牌數解析。
  - dice / mascot 條件未命中時的 no-op summary。
  - Action Pipeline 優先執行。
  - Action Pipeline 未移動卡片時的 SQL fallback。
  - 抽牌結果 summary payload。
- `MatchEffectService` 的 support / gift / flow glow archive reveal draw 改為委派新 service。
- `MatchBloomEffectDispatcher` 與 `MatchCollabEffectDispatcher` 改為直接委派新 service，不再經由 `MatchEffectService` draw wrapper。
- 新增 `MatchDrawEffectExecutionServiceTest`，鎖定：
  - Action Pipeline 成功移動卡片時使用 pipeline 結果。
  - Action Pipeline 未移動卡片時 fallback 到 SQL 抽牌。
  - dice 條件未命中時不抽牌並回傳 no-op summary。

## 責任邊界

`MatchDrawEffectExecutionService` 負責：

- `DRAW` 效果的抽牌數解析。
- `DRAW` 的 dice condition gate。
- 抽牌 Action Pipeline 呼叫。
- 抽牌 SQL fallback。
- `DRAW` summary payload 組裝。

`MatchEffectService` 保留：

- support / gift 高階 dispatch。
- flow glow archive reveal 的流程協調。
- 其他 effect family execution。
- pending decision 與大型規則 orchestration。

本批未處理：

- `DISCARD_HAND`。
- `MOVE_TO_HOLOPOWER`。
- `REST` / `SWAP_CENTER_BACK`。
- `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
- passive gift 大型規則。
- 公開 API 或資料庫 migration。

## 行數變化

- `MatchEffectService.java`：`10,825` 行 -> `10,708` 行，減少 `117` 行。
- 新增 `MatchDrawEffectExecutionService.java`：`209` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchDrawEffectExecutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest='MatchActionServiceIntegrationTest#playSupportShouldMoveCardToArchiveAndApplyDrawEffect+playSupportDiceMultiRollMaxShouldUseHighestRollForPerEffectConditions+collabHsd01015ShouldChooseSoraBranchOnly' test`
- `./mvnw -q -Dtest=MatchBloomEffectIntegrationTest#bloomShouldTriggerDrawEffectFromPassiveText test`

補充：

- focused integration tests 需要 Testcontainers / PostgreSQL。
- 沙盒內執行整合測試需要提高權限存取 Docker/PostgreSQL socket；提高權限後驗證通過。

## 判讀

- `MatchEffectService` 不再持有 `DRAW` 的 Action Pipeline fallback、SQL fallback 與 summary payload 組裝。
- Bloom / Collab dispatcher 直接依賴 `MatchDrawEffectExecutionService`，主 service 的 execution bridge 進一步縮小。
- 本批屬於低風險 production god class 拆分，未修改公開 API、資料庫 migration 或核心規則語意。

## 下一步建議

下一批建議優先抽 `MOVE_TO_HOLOPOWER` execution service：

1. `MOVE_TO_HOLOPOWER` 相對 `DISCARD_HAND` 更少依賴 criteria / cost parser，適合作為下一個低風險拆分。
2. 本批先不要混入 `DISCARD_HAND`，避免同時處理 hand criteria、成本文字解析與卡片移動。
3. `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE` / passive gift 繼續暫緩。

建議下一個 commit：

```text
後端：抽出 Holopower 移動效果執行服務
```
