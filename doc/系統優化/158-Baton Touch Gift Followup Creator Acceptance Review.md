# Baton Touch Gift Followup Creator Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本文件驗收 BTGF-2 creator extraction。

本步只抽出 baton touch source-card Gift followup creation，不改 baton touch cost、movement、phase transition 或 action append 規則。

## 二、完成內容

- 新增 `BatonTouchGiftFollowup` result record。
- 新增 `BatonTouchGiftFollowupCreator`。
- `MatchActionService.batonTouch(...)` 改為委派 creator 取得 Gift followup result。
- 移除 `MatchActionService.createBatonTouchGiftTriggerDecision(...)` private helper。
- 保留 `BATON_TOUCH` payload assembly 與 action append 在 `MatchActionService`。
- 新增 `BatonTouchGiftFollowupCreatorTest`，覆蓋：
  - 無 Gift effect 時回傳 empty followup。
  - 有 Gift effect 時建立 summary 與 source-card pending decision。
  - source card instance id / card id 會傳入 `GiftPendingDecisionCreator`。

## 三、Allow / Block 對照

### Allow

- 新增 baton touch source-card Gift followup creator。
- 保留 source card instance id / card id。
- 保留 `batonTouchGiftEffect` payload key。
- 保留 `FollowupDecisionPayloadAppender` append 行為。
- 保留 baton touch focused smoke 驗證舊入口。

### Block

- 未套用 `SourcelessGiftPendingDecisionCreator`。
- 未改 `BATON_TOUCH_BACK` trigger type。
- 未改 pending context JSON shape。
- 未改 source cards payload shape。
- 未改 baton touch cost、movement、phase transition 或 once-per-turn rules。
- 未碰 PLAY_CARD、main step、advance phase 或 attack Gift pending。
- 未把 baton touch 整條 flow 搬成 application service。

## 四、測試缺口

目前沒有本批 blocker。

保留缺口：

- 未跑完整 `MatchActionServiceIntegrationTest`；該 suite 既有廣域不穩定，不作為本批 blocker。
- `BatonTouchGiftFollowupCreator` 只負責 Gift followup creation；baton touch 主流程仍留在 `MatchActionService`，後續若要拆主流程需另開規劃。

## 五、驗證結果

- 已通過：`./mvnw -q -Dtest=BatonTouchGiftFollowupCreatorTest,GiftPendingDecisionCreatorTest,GiftTriggeredEffectDeferredSummaryBuilderTest test`
- 已通過，需沙盒外 Docker/Testcontainers：`./mvnw -q -Dtest=MatchActionServiceIntegrationTest#batonTouchShouldCreateGiftConfirmWhenTargetMovedBackTriggersGift test`

## 六、結論

BTGF-2 可收束。

baton touch source-card Gift followup creation 已離開 `MatchActionService`，且 source card pending 語意仍由 focused smoke 保護。

## 七、下一步

進入 code review / commit checkpoint。

commit 後建議收束 Gift / pending cleanup batch，或回到 attack 前置小切片；若繼續拆 baton touch 主流程，需先另開 planning，避免把 cost / movement / Gift followup 混在同一批。
