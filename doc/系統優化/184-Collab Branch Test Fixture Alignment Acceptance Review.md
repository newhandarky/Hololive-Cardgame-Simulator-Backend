# Collab Branch Test Fixture Alignment Acceptance Review

日期：2026-05-21
狀態：已完成
commit 建議：`測試：對齊 Collab 分支前置資料`

## 背景

`183-MatchCollabEffectDispatcher Extraction Acceptance Review.md` 記錄了三個 Collab integration test 差異：

- `collabHsd01015ShouldChooseAzkiBranchOnly`
- `collabHsd01015ShouldChooseSoraBranchOnly`
- `collabHsd13015ShouldNotTriggerWhenNoStageCheerToReturn`

本批先釐清根因，確認差異來自測試前置資料，不是 `MatchCollabEffectDispatcher` 的分派邏輯。

## 根因

- `HSD01-015` 兩個分支測試額外建立第二個 `CENTER` Holomem，但 runtime context 依 `ORDER BY h.id LIMIT 1` 讀取最早建立的 `CENTER`，因此沒有讀到測試想指定的 `AZKi` / `ときのそら`。
- `HSD13-015` 無場上エール測試使用標準開局資料，但未明確清空該玩家場上的 cheer，導致 runtime context 的 `ownedStageCheerCount` 不是測試預期的 0。

## 本批完成內容

- `collabHsd01015ShouldChooseAzkiBranchOnly`：
  - 改為覆寫既有 first center card instance 的 card definition。
  - 避免建立第二個 `CENTER` 造成 runtime context 讀錯目標。
- `collabHsd01015ShouldChooseSoraBranchOnly`：
  - 同樣改為覆寫既有 first center card instance。
- `collabHsd13015ShouldNotTriggerWhenNoStageCheerToReturn`：
  - 在測試開始時清空該玩家所有 `match_holomem_cheers`。
  - 明確建立「無場上エール」前置條件。
- 未修改 production code。

## 影響範圍

- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`
- `doc/系統優化/00-系統優化總覽.md`
- `doc/系統優化/05-重構進度追蹤.md`

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#collabHsd01015ShouldChooseAzkiBranchOnly+collabHsd01015ShouldChooseSoraBranchOnly+collabHsd13015ShouldNotTriggerWhenNoStageCheerToReturn test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#collabHsd01015ShouldChooseAzkiBranchOnly+collabHsd01015ShouldChooseSoraBranchOnly+collabTriggerConfirmShouldCreateLookTopDeckFollowupInteraction+collabHbp06078ShouldPayAttachedCheerCostThenSearchOshiSameNameDebut+collabHbp06078ShouldSkipSearchWhenNoAttachedCheerCost+collabHsd13015ShouldReturnStageCheerThenAddCheer+collabHsd13015ShouldNotTriggerWhenNoStageCheerToReturn test`
- `./mvnw -q -DskipTests compile`

補充：

- integration tests 需要 Testcontainers / PostgreSQL，已提高權限後驗證通過。

## 下一步建議

- Collab dispatcher 的 focused test 缺口已收斂，下一批可回到重構主線。
- 建議優先抽低風險 execution family，例如 `LOOK_TOP_DECK` / `LOOK_OPPONENT_HAND` / `LOOK_HOLOPOWER`，降低 `MatchEffectService` 內實際 execution helper 的密度。
