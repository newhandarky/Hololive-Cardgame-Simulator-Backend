# 189-MatchEffect Rest Effect Execution Service Extraction Acceptance Review

日期：2026-05-21
狀態：已完成

## 背景

本批延續 production code 主線拆分，在 `MOVE_TO_HOLOPOWER` execution service 收斂後，只處理 `REST`，不混入 `DISCARD_HAND`、`SWAP_CENTER_BACK` 或高風險戰鬥規則。

選擇 `REST` 的原因：

- 責任集中在目標解析後的休息狀態更新。
- 相對 `DISCARD_HAND` 更少依賴 criteria / cost parser。
- 可透過小型 callback 沿用既有 target resolver，不需要同批搬動大量 target helper。

## 本批完成內容

- 新增 `MatchRestEffectExecutionService` package-private component。
- 從 `MatchEffectService` 搬出 `REST` 執行流程：
  - dice / mascot 條件未命中時的 no-op summary。
  - 目標 Holomem 解析後的 `is_rested = TRUE` 更新。
  - raw text 指向 `バックホロメン` 時的第一個 BACK fallback。
  - 休息結果 summary payload。
- `MatchEffectService` 的 support / gift dispatch 改為委派新 service。
- `MatchBloomEffectDispatcher` 與 `MatchCollabEffectDispatcher` 改為直接委派新 service，不再經由 `MatchEffectService` rest wrapper。
- 新增 `MatchRestEffectExecutionServiceTest`。
- 新增最小 focused integration：支援卡 `REST` 指定對手 Holomem 後，確認目標變成 rested。

## 責任邊界

`MatchRestEffectExecutionService` 負責：

- `REST` dice condition gate。
- `REST` no-op summary。
- 第一個 BACK fallback 查詢。
- `match_holomems.is_rested` 更新。
- `REST` summary payload 組裝。

`MatchEffectService` 保留：

- support / gift 高階 dispatch。
- 目標解析 helper 與對手解析 helper，透過 callback 提供給新 service。
- 其他 effect family execution。

本批未處理：

- `DISCARD_HAND`。
- `SWAP_CENTER_BACK`。
- `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
- passive gift 大型規則。
- 公開 API 或資料庫 migration。

## 行數變化

- `MatchEffectService.java`：`10,630` 行 -> `10,584` 行，減少 `46` 行。
- 新增 `MatchRestEffectExecutionService.java`：`148` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchRestEffectExecutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportShouldApplyRestEffectToSelectedOpponentHolomem test`
- `git diff --check`

補充：

- focused integration test 需要 Testcontainers / PostgreSQL。
- 沙盒內首次執行因 Docker/PostgreSQL socket `Operation not permitted` 失敗；提高權限後驗證通過。

## 判讀

- `MatchEffectService` 不再持有 `REST` 的休息 SQL、BACK fallback 與 summary payload 組裝。
- Bloom / Collab dispatcher 直接依賴 `MatchRestEffectExecutionService`，主 service 的 execution bridge 繼續縮小。
- 本批屬於低風險 production god class 拆分，未修改公開 API、資料庫 migration 或核心規則語意。

## 下一步建議

下一批建議優先抽 `SWAP_CENTER_BACK` execution service：

1. `SWAP_CENTER_BACK` 與本批 `REST` 同屬 stage 狀態操作，責任邊界相近。
2. 風險仍低於 `DISCARD_HAND`，可延續低風險拆分節奏。
3. `DISCARD_HAND` 建議等 `SWAP_CENTER_BACK` 收斂後，再補 criteria / cost parser focused tests 進行拆分。

建議下一個 commit：

```text
後端：抽出中心後台交換效果執行服務
```
