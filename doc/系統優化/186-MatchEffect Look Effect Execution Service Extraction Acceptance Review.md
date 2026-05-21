# 186-MatchEffect Look Effect Execution Service Extraction Acceptance Review

日期：2026-05-21
狀態：已完成

## 背景

本批回到 production code 主線，優先降低 `MatchEffectService.java` 的核心效果執行責任，而不是繼續拆測試巨檔。

選擇 `LOOK_TOP_DECK` / `LOOK_OPPONENT_HAND` / `LOOK_HOLOPOWER` 的原因：

- 屬於相鄰且低風險的 execution family。
- 主要責任是查詢可查看卡片與建立效果摘要。
- 不涉及 SEARCH / RETURN / DAMAGE / GIFT 的大型狀態改寫。

## 本批完成內容

- 新增 `MatchLookEffectExecutionService` package-private component。
- 搬移三個 Look 類效果執行流程：
  - `LOOK_TOP_DECK`
  - `LOOK_OPPONENT_HAND`
  - `LOOK_HOLOPOWER`
- 搬移「查看類互動」卡片清單查詢與對手 userId 解析。
- `MatchEffectService` 改為只在 support / gift dispatch 呼叫新 service。
- `MatchBloomEffectDispatcher` 與 `MatchCollabEffectDispatcher` 改為直接委派新 service，避免 `MatchEffectService` 保留 look wrapper。
- 新增 `MatchLookEffectExecutionServiceTest`，鎖定：
  - 牌庫頂查看摘要。
  - mascot 條件未命中時 no-op。
  - 查看對手手牌候選 payload。
  - 查看對手 Holopower payload。

## 責任邊界

`MatchLookEffectExecutionService` 負責：

- Look 類效果 SQL 查詢。
- Look 類效果摘要 payload。
- Look 類 no-op reason。
- Look 類對手解析與可查看卡片 payload mapping。

`MatchEffectService` 保留：

- support / gift 高階 dispatch。
- 其他 effect family execution。
- Bloom / Collab orchestration 入口與既有 bridge。

本批未處理：

- SEARCH / RETURN actual execution。
- DAMAGE / DOWN / HEAL / MOVE_ZONE。
- passive gift 大型規則。
- 公開 API 或資料庫 migration。

## 行數變化

- `MatchEffectService.java`：`10,970` 行 -> `10,825` 行，減少 `145` 行。
- 新增 `MatchLookEffectExecutionService.java`：`210` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchLookEffectExecutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest='MatchActionServiceIntegrationTest#playSupportLookTopDeckShouldExposePendingInteractionWithCardContext+resolveLookTopDeckDecisionShouldAcceptPlacementOption+playSupportLookOpponentHandShouldExposePendingInteractionWithCardContext+resolveLookOpponentHandDecisionShouldMarkResolved+playSupportLookHolopowerShouldExposePendingInteractionAndResolve' test`
- `./mvnw -q -Dtest='MatchActionServiceIntegrationTest#collabTriggerConfirmShouldCreateLookTopDeckFollowupInteraction+collabTriggerConfirmShouldCreateLookOpponentHandFollowupInteraction+collabTriggerConfirmShouldCreateLookHolopowerFollowupInteraction' test`

補充：

- support / collab integration tests 需要 Testcontainers / PostgreSQL。
- 沙盒內首次執行因 Docker/PostgreSQL socket `Operation not permitted` 失敗；提高權限後已驗證通過。

## 判讀

- `MatchEffectService` 不再持有 Look 類 SQL 與 payload mapping。
- Bloom / Collab dispatcher 已開始直接依賴相鄰 execution service，後續可以逐步降低對 `MatchEffectService` execution bridge 的依賴。
- 本批屬於 production god class 拆分，不是測試檔整理。

## 下一步建議

下一批建議繼續拆低風險共同 execution helpers：

1. 優先抽 `DRAW` execution service。
2. 再依序處理 `DISCARD_HAND`、`MOVE_TO_HOLOPOWER`、`REST`、`SWAP_CENTER_BACK`。
3. 暫緩 `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE` / passive gift，等低風險區塊拆完再處理。

建議下一個 commit：

```text
後端：抽出抽牌效果執行服務
```
