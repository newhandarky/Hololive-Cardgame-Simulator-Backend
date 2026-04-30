# Gift Pending Shared Helper Cleanup Planning

日期：2026-04-30
狀態：規劃完成

## 背景

`GiftPendingDecisionCreator` 已經集中一般 Gift trigger pending decision 的底層建立責任。經過 advance phase followup creator 與 payload followup adapter 後，無 source card 的 Gift pending semantic adapter 目前分散在兩個地方：

- `MatchActionService.createGiftTriggerDecisionWithoutSourceCard(...)`：main step Gift followup 使用。
- `AdvancePhaseFollowupCreator.createGiftTriggerDecisionWithoutSourceCard(...)`：advance phase performance start / performance end Gift followup 使用。

兩者語意相同：使用標準 Gift trigger interaction cards，且 `sourceCardInstanceId` / `sourceCardId` 都固定為 `null`。

## 目標

規劃一個極小範圍的 shared helper cleanup，只收斂 main step 與 advance phase 的 sourceless Gift pending creation adapter，避免同時牽動 baton touch、PLAY_CARD 或 attack defender Gift pending。

候選設計：

- 新增 package-private adapter，例如 `SourcelessGiftPendingDecisionCreator`。
- adapter 只包裝 `GiftPendingDecisionCreator.createWithGiftTriggerInteractionCards(matchId, userId, null, null, giftEffects, turnNumber)`。
- `MatchActionService` 與 `AdvancePhaseFollowupCreator` 改為委派 adapter。
- 不改 pending action type、context JSON、message、payload schema。

## Allow List

- 新增小型 package-private shared adapter。
- 移除兩個 sourceless Gift pending private helper 的重複委派邏輯。
- 保留 main step / advance phase 既有 call-site 語意。
- 使用既有 focused smoke 驗證 main step 與 advance phase Gift pending。
- 若 adapter 有分支或輸入防線，補 focused unit test；若只是薄委派，可用既有 smoke 作為主要保護。

## Block List

- 不碰 baton touch source card Gift pending。
- 不碰 PLAY_CARD played card payload / Gift pending。
- 不碰 attack post-trigger pending 與 defender Gift pending conversion。
- 不重命名或移除 `GiftPendingDecisionCreator`。
- 不改 Gift pending context JSON shape。
- 不改 pending interaction cards、action type、message 文字或前端 payload key。

## 建議批次

### GPSC-1：planning checkpoint

- 補本文件。
- 更新系統優化總覽與重構進度追蹤。
- 不改 production code。

### GPSC-2：shared adapter extraction

- 新增 sourceless Gift pending shared adapter。
- 讓 `MatchActionService` main step path 與 `AdvancePhaseFollowupCreator` advance phase path 委派 adapter。
- 移除重複 private helper 或把 helper 縮到只保留語意名稱。
- 視實作複雜度補 adapter unit test。

### GPSC-3：focused smoke

建議至少跑：

- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#mainStepGiftHsd13013ShouldArchiveStackCostAndAttachCheerFromDeckTop test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers,MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceEndGiftConfirmForBothPlayers test`

### GPSC-4：acceptance review

- 對照 allow / block list。
- 紀錄舊入口 call-site 清單。
- 紀錄 focused smoke 結果。
- 更新進度追蹤與下一步。

## 風險判讀

這一步的主要價值不是壓縮行數，而是讓「無 source card 的 Gift pending 建立」成為明確概念，避免同一個 `null, null` 委派語意在不同流程內各自存在。第一版應保持 adapter 很薄，讓行為完全由 `GiftPendingDecisionCreator` 與既有 smoke 保護。

## 下一步

進入 code review / commit checkpoint。

commit 後進入 GPSC-2，優先做 shared adapter extraction，範圍只限 main step 與 advance phase sourceless Gift pending。
