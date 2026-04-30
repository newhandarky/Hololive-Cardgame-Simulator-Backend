# Gift Pending Followup Cleanup Batch Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本批範圍

本文件收束 `154` 至 `158` 的 Gift pending / followup cleanup batch：

- `154-Gift Pending Shared Helper Cleanup Planning.md`
- `155-Gift Pending Shared Helper Cleanup Acceptance Review.md`
- `156-Main Step Gift Followup Payload Appender Acceptance Review.md`
- `157-Baton Touch Gift Followup Creator Planning.md`
- `158-Baton Touch Gift Followup Creator Acceptance Review.md`

本批目標是收斂 `MatchActionService` 內 main step、advance phase 與 baton touch Gift pending / followup creation 的殘留責任。

## 二、完成內容

- 新增 `SourcelessGiftPendingDecisionCreator`，集中 main step / advance phase 無 source card Gift pending creation。
- 新增 `MainStepGiftFollowupPayloadAppender`，下沉 main step Gift followup preview / summary / pending payload append。
- 新增 `BatonTouchGiftFollowupCreator` 與 `BatonTouchGiftFollowup`，下沉 baton touch source-card Gift followup creation。
- `AdvancePhaseFollowupCreator` 改用 sourceless shared adapter。
- `MatchActionService` 移除 main step / advance phase / baton touch Gift pending private helper。
- 保留 `GiftPendingDecisionCreator` 作為底層 Gift pending decision creator。

## 三、Allow / Block 對照

### Allow

- 收斂 sourceless Gift pending creation。
- 收斂 main step Gift followup payload assembly。
- 收斂 baton touch source-card Gift followup creation。
- 保留 existing facade / API / payload key。
- 使用 focused unit 與 integration smoke 驗證舊入口。

### Block

- 未改 Gift pending context JSON shape。
- 未改 pending interaction action type。
- 未改 `mainStepGiftEffects` payload key。
- 未改 `batonTouchGiftEffect` payload key。
- 未把 baton touch 接到 sourceless Gift pending path。
- 未碰 PLAY_CARD played-card Gift pending。
- 未碰 attack post-trigger / defender Gift pending conversion。
- 未改 baton touch cost、movement、phase transition 或 once-per-turn rules。

## 四、測試結果

已通過：

- `./mvnw -q -Dtest=AdvancePhaseFollowupCreatorTest,SourcelessGiftPendingDecisionCreatorTest,GiftPendingDecisionCreatorTest test`
- `./mvnw -q -Dtest=MainStepGiftFollowupPayloadAppenderTest,FollowupDecisionPayloadAppenderTest,GiftTriggeredEffectDeferredSummaryBuilderTest,SourcelessGiftPendingDecisionCreatorTest test`
- `./mvnw -q -Dtest=BatonTouchGiftFollowupCreatorTest,GiftPendingDecisionCreatorTest,GiftTriggeredEffectDeferredSummaryBuilderTest test`

已通過，需沙盒外 Docker/Testcontainers：

- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#mainStepGiftHsd13013ShouldArchiveStackCostAndAttachCheerFromDeckTop test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers,MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceEndGiftConfirmForBothPlayers test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#batonTouchShouldCreateGiftConfirmWhenTargetMovedBackTriggersGift test`

## 五、剩餘缺口

目前沒有本批 blocker。

保留缺口：

- 完整 `MatchActionServiceIntegrationTest` 仍有既有廣域不穩定，不作為本批 blocker。
- PLAY_CARD Gift pending creation 仍屬 PLAY_CARD effect resolution path，本批未動。
- attack post-trigger / defender Gift pending conversion 仍屬 attack path，本批未動。
- baton touch 主流程 cost / movement / phase transition 仍留在 `MatchActionService`；若要拆需另開 planning。

## 六、結論

Gift pending / followup cleanup batch 可收束。

main step、advance phase 與 baton touch 的 Gift followup creation 已各自有清楚邊界；source-card 與 sourceless path 也已分離。

## 七、下一步

進入 code review / commit checkpoint。

commit 後建議回到 attack 前置小切片路線，優先評估 `ATTACK_DAMAGE` 之後的下一段已規劃 use case，而不是繼續擴大 Gift pending cleanup。
