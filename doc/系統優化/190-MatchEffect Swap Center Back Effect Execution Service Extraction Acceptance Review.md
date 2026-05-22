# 190-MatchEffect Swap Center Back Effect Execution Service Extraction Acceptance Review

日期：2026-05-22
狀態：已完成

## 背景

本批延續 production code 主線拆分，在 `REST` execution service 收斂後，只處理 `SWAP_CENTER_BACK`，不混入 `DISCARD_HAND` 或高風險戰鬥規則。

選擇 `SWAP_CENTER_BACK` 的原因：

- 與 `REST` 同屬 stage 狀態操作，責任邊界相近。
- 範圍集中在 CENTER / BACK 查詢、action lock 檢查與 zone 更新。
- 風險低於 `DISCARD_HAND`，可先延續低風險拆分節奏。

## 本批完成內容

- 新增 `MatchSwapCenterBackEffectExecutionService` package-private component。
- 從 `MatchEffectService` 搬出 `SWAP_CENTER_BACK` 執行流程：
  - dice / mascot 條件未命中時的 no-op summary。
  - 自己或對手 stage owner 解析。
  - CENTER / BACK 查詢。
  - `お休みしていない` 文案對 BACK rested 狀態的篩選。
  - CENTER / BACK action lock 檢查。
  - CENTER 與 BACK 的 zone 更新。
  - 交換結果 summary payload。
- `MatchEffectService` 的 support / gift dispatch 改為委派新 service。
- `MatchBloomEffectDispatcher` 與 `MatchCollabEffectDispatcher` 改為直接委派新 service，不再經由 `MatchEffectService` swap wrapper。
- 新增 `MatchSwapCenterBackEffectExecutionServiceTest`。
- 新增最小 focused integration：支援卡 `SWAP_CENTER_BACK` 讓自家 CENTER / BACK 交換，並驗證 action payload。

## 責任邊界

`MatchSwapCenterBackEffectExecutionService` 負責：

- `SWAP_CENTER_BACK` dice condition gate。
- `SWAP_CENTER_BACK` no-op summary。
- stage owner 判斷。
- CENTER / BACK 查詢。
- action lock 檢查。
- `match_holomems.zone` 更新。
- `SWAP_CENTER_BACK` summary payload 組裝。

`MatchEffectService` 保留：

- support / gift 高階 dispatch。
- 對手解析、目前回合與 action lock helper，透過 callback 提供給新 service。
- 其他 effect family execution。

本批未處理：

- `DISCARD_HAND`。
- `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
- passive gift 大型規則。
- 公開 API 或資料庫 migration。

## 行數變化

- `MatchEffectService.java`：`10,584` 行 -> `10,497` 行，減少 `87` 行。
- 新增 `MatchSwapCenterBackEffectExecutionService.java`：`169` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchSwapCenterBackEffectExecutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportShouldApplySwapCenterBackEffectToOwnStage test`
- `git diff --check`

補充：

- focused integration test 需要 Testcontainers / PostgreSQL。
- 沙盒內首次執行因 Docker/PostgreSQL socket `Operation not permitted` 失敗；提高權限後驗證通過。

## 判讀

- `MatchEffectService` 不再持有 `SWAP_CENTER_BACK` 的 stage 查詢、action lock 判斷、交換 SQL 與 summary payload 組裝。
- Bloom / Collab dispatcher 直接依賴 `MatchSwapCenterBackEffectExecutionService`，主 service 的 execution bridge 繼續縮小。
- 本批屬於低風險 production god class 拆分，未修改公開 API、資料庫 migration 或核心規則語意。

## 下一步建議

下一批有兩個合理方向：

1. 若要維持低風險，先抽 `SWAP_WITH_COLLAB` 或其他 stage move 小區塊。
2. 若要提高收益，開始規劃 `DISCARD_HAND`，但要先補 criteria / cost parser 與候選查詢 focused tests。

建議下一個 commit 視選擇方向決定；若走低風險主線，可使用：

```text
後端：抽出舞台交換效果執行服務
```
