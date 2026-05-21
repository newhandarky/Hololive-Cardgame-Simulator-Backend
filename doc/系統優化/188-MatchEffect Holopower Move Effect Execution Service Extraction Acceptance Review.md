# 188-MatchEffect Holopower Move Effect Execution Service Extraction Acceptance Review

日期：2026-05-21
狀態：已完成

## 背景

本批延續 production code 主線拆分，在 `DRAW` execution service 收斂後，只處理 `MOVE_TO_HOLOPOWER`，不混入 `DISCARD_HAND` 或高風險戰鬥規則。

選擇 `MOVE_TO_HOLOPOWER` 的原因：

- 責任集中在來源區解析與卡片移動。
- 相對 `DISCARD_HAND` 更少依賴 criteria / cost parser。
- Bloom / Collab dispatcher 已能直接依賴具體 execution service，適合繼續削減 `MatchEffectService` bridge。

## 本批完成內容

- 新增 `MatchHolopowerMoveEffectExecutionService` package-private component。
- 從 `MatchEffectService` 搬出 `MOVE_TO_HOLOPOWER` 執行流程：
  - 來源區解析：`DECK` / `ARCHIVE` / `HAND`。
  - dice / mascot 條件未命中時的 no-op summary。
  - 來源區卡片查詢。
  - 移動卡片到 `HOLOPOWER`。
  - `HOLOPOWER` order index 計算。
  - 移動結果 summary payload。
- `MatchEffectService` 的 support / gift dispatch 改為委派新 service。
- `MatchBloomEffectDispatcher` 與 `MatchCollabEffectDispatcher` 改為直接委派新 service，不再經由 `MatchEffectService` move wrapper。
- 新增 `MatchHolopowerMoveEffectExecutionServiceTest`，鎖定：
  - 預設從 `DECK` 移動。
  - explicit `HAND` 來源區。
  - explicit `ARCHIVE` 來源區。
  - dice 條件未命中時不移動。
  - 來源區沒有卡片時 `moveApplied = 0`。

## 責任邊界

`MatchHolopowerMoveEffectExecutionService` 負責：

- `MOVE_TO_HOLOPOWER` 來源區解析。
- `MOVE_TO_HOLOPOWER` dice condition gate。
- 來源區卡片查詢與 `HOLOPOWER` order index。
- 卡片移動 SQL。
- `MOVE_TO_HOLOPOWER` summary payload 組裝。

`MatchEffectService` 保留：

- support / gift 高階 dispatch。
- 其他 effect family execution。
- pending decision 與大型規則 orchestration。

本批未處理：

- `DISCARD_HAND`。
- `REST` / `SWAP_CENTER_BACK`。
- `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
- passive gift 大型規則。
- 公開 API 或資料庫 migration。

## 行數變化

- `MatchEffectService.java`：`10,708` 行 -> `10,630` 行，減少 `78` 行。
- 新增 `MatchHolopowerMoveEffectExecutionService.java`：`147` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchHolopowerMoveEffectExecutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest='MatchActionServiceIntegrationTest#collabHbp01031ShouldTakeOneCardFromHolopowerThenRefillFromDeckTop+collabHsd04011ShouldTakeFromHolopowerThenSendOneHandCardToHolopower+playSupportDiceMultiRollMaxShouldUseHighestRollForPerEffectConditions+playSupportDiceMultiRollMinShouldUseLowestRollForPerEffectConditions' test`
- `git diff --check`

補充：

- focused integration tests 需要 Testcontainers / PostgreSQL。
- 沙盒內首次執行因 Docker/PostgreSQL socket `Operation not permitted` 失敗；提高權限後驗證通過。

## 判讀

- `MatchEffectService` 不再持有 `MOVE_TO_HOLOPOWER` 的來源區解析、移動 SQL 與 summary payload 組裝。
- Bloom / Collab dispatcher 直接依賴 `MatchHolopowerMoveEffectExecutionService`，主 service 的 execution bridge 繼續縮小。
- HBP01-031 的特殊調整 payload 現在明確指定後段 `MOVE_TO_HOLOPOWER` 從 `DECK` 補到 `HOLOPOWER`，避免整段文案同時包含「手札に加える」時誤判來源區。
- 本批屬於低風險 production god class 拆分，未修改公開 API、資料庫 migration 或核心規則語意。

## 下一步建議

下一批建議在 `DISCARD_HAND` 與 `REST` 之間二選一：

1. 若要最大化行數收益，抽 `DISCARD_HAND`，但需先補 criteria / cost parser 相關 focused tests。
2. 若要維持低風險節奏，先抽 `REST`，再回頭處理 `DISCARD_HAND`。
3. `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE` / passive gift 繼續暫緩。

建議下一個 commit：

```text
後端：抽出 Holopower 移動效果執行服務
```
