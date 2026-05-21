# MatchCollabEffectDispatcher Extraction Acceptance Review

日期：2026-05-21
狀態：已完成
commit 建議：`後端：抽出 Collab 效果分派器`

## 背景

`MatchEffectService.applyCollabTriggeredEffects(...)` 原本同時負責：

- Collab effect plan 解析。
- runtime 欄位補齊。
- Collab 專屬條件分支。
- effect type switch 分派。
- summary 組裝。

前一批已完成 `MatchBloomEffectDispatcher`。本批延續相同節奏，把 Collab effect type switch 搬到 package-private component，先降低主 service orchestration 負擔，不在同一批搬動實際 SQL execution method。

## 本批完成內容

- 新增 `MatchCollabEffectDispatcher`：
  - 接收 `MatchCardSelectionExecutionService`。
  - 接收 `MatchEffectService` 作為暫時性 execution bridge。
  - 集中處理 Collab effect type switch。
  - 保留 Collab 專屬 skip 狀態：
    - `HSD13-015` 未退回場上エール時略過 `ADD_CHEER`。
    - `HBP06-078` 未支付附屬エール成本時略過 `SEARCH`。
  - 回傳 `CollabDispatchResult`，包含 `executed`、`unsupported`、`skippedEffects`。
- `MatchEffectService.applyCollabTriggeredEffects(...)` 改為呼叫 `collabEffectDispatcher.execute(...)`。
- `MatchEffectService` 保留 Collab plan 解析、runtime 欄位補齊、dice summary 補值與回傳 summary 組裝。
- 將 Collab 目標解析 helper 放寬為 package-private，供 dispatcher 呼叫。

## 影響範圍

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`
- `src/main/java/com/hololive/cardgame/service/MatchCollabEffectDispatcher.java`
- `doc/系統優化/00-系統優化總覽.md`
- `doc/系統優化/05-重構進度追蹤.md`

## 大檔尺寸變化

- `MatchEffectService.java`：`11,166` -> `10,970` 行，減少 `196` 行。
- 新增 `MatchCollabEffectDispatcher.java`：`256` 行。

本批讓 `MatchEffectService` 低於 11,000 行，主檔少掉 Collab effect type switch 這段相鄰責任。

## 驗證結果

已通過：

- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=CollabEffectResolutionServiceTest,CollabApplicationServiceTest,CollabActionResolverTest,CollabEventFactoryTest test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#collabTriggerConfirmShouldCreateLookTopDeckFollowupInteraction+collabHbp06078ShouldPayAttachedCheerCostThenSearchOshiSameNameDebut+collabHbp06078ShouldSkipSearchWhenNoAttachedCheerCost test`

補充：

- integration tests 需要 Testcontainers / PostgreSQL；沙盒內因 Docker socket `Operation not permitted` 失敗，已提高權限後驗證通過。

## 已知測試差異

下列測試在本批驗證時失敗，但失敗點發生在 Collab plan 解析或既有測試前置資料，不在新 dispatcher 的 effect type switch 內：

- `collabHsd01015ShouldChooseAzkiBranchOnly`：`requestedEffects` 為空，尚未進入 dispatcher 分派。
- `collabHsd01015ShouldChooseSoraBranchOnly`：`requestedEffects` 為空，尚未進入 dispatcher 分派。
- `collabHsd13015ShouldNotTriggerWhenNoStageCheerToReturn`：summary 回傳 `hasCollabEffect = true`，與測試預期不一致。

其中 `collabHsd01015ShouldChooseAzkiBranchOnly` 已單獨重跑，仍出現相同 `requestedEffects` 空集合結果。建議下一批先判斷這些是否為既有解析/測試資料問題，再繼續搬更深的 execution helper。

## 殘留風險

- `MatchCollabEffectDispatcher` 仍依賴整個 `MatchEffectService` 作為 execution bridge。
- package-private helper 可見範圍增加，需在後續 execution service 抽離時收斂。
- Bloom 與 Collab dispatcher 已建立分派邊界，但實際 effect execution SQL 仍集中在 `MatchEffectService`。

## 下一步建議

1. 先釐清 Collab 解析測試差異，尤其是 `HSD01-015` 分支解析與 `HSD13-015` 無場上エール時的期待行為。
2. 若確認是測試資料或既有規格落差，先用 focused commit 修正測試/規格。
3. 行為差異收斂後，再抽低風險 execution service，優先處理 `LOOK_*`、`DRAW`、`NO_OP` 這類副作用較小的 effect family。
