# Main Step Gift Followup Payload Appender Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本文件驗收 main step Gift followup payload appender extraction。

本步只下沉 `MatchActionService` 內 main step Gift followup 的 preview / summary / sourceless pending / payload append 組裝責任。

## 二、完成內容

- 新增 `MainStepGiftFollowupPayloadAppender`。
- `MatchActionService` draw reveal resolve 後不需 turn cheer 的 main step Gift followup 改為委派 appender。
- `MatchActionService` TURN_CHEER resolve 後的 main step Gift followup 改為委派 appender。
- 移除 `MatchActionService.appendMainStepGiftFollowupPayload(...)` private helper。
- 新增 `MainStepGiftFollowupPayloadAppenderTest`，覆蓋：
  - 無 Gift effect 時仍寫入 `mainStepGiftEffects` summary。
  - 有 Gift effect 時建立 sourceless pending decision 並 append pending payload fields。

## 三、Allow / Block 對照

### Allow

- 下沉 main step Gift followup payload assembly。
- 保留原本兩個 call-site 與呼叫條件。
- 保留 `mainStepGiftEffects` payload key。
- 保留 sourceless Gift pending decision 建立流程。
- 保留 focused integration smoke 作為舊入口 regression。

### Block

- 未改 Gift trigger preview 規則。
- 未改 Gift pending context JSON shape。
- 未改 draw reveal / send cheer action append 順序。
- 未碰 baton touch source card Gift pending。
- 未碰 PLAY_CARD 或 attack Gift pending。
- 未改前端 payload key 或 pending interaction action type。

## 四、測試缺口

目前沒有本批 blocker。

保留缺口：

- 未跑完整 `MatchActionServiceIntegrationTest`；該 suite 既有廣域不穩定，不作為本批 blocker。
- `MainStepGiftFollowupPayloadAppender` 目前仍使用既有 `MatchGiftTriggerService` preview，未拆 Gift preview 責任。

## 五、驗證結果

- 已通過：`./mvnw -q -Dtest=MainStepGiftFollowupPayloadAppenderTest,FollowupDecisionPayloadAppenderTest,GiftTriggeredEffectDeferredSummaryBuilderTest,SourcelessGiftPendingDecisionCreatorTest test`
- 已通過，需沙盒外 Docker/Testcontainers：`./mvnw -q -Dtest=MatchActionServiceIntegrationTest#mainStepGiftHsd13013ShouldArchiveStackCostAndAttachCheerFromDeckTop test`

## 六、結論

main step Gift followup payload appender extraction 可收束。

`MatchActionService` 不再直接組裝 main step Gift followup payload，但仍保留 baton touch 與其他 use case 的既有責任邊界。

## 七、下一步

進入 code review / commit checkpoint。

commit 後建議重新盤點 `MatchActionService` 內 remaining Gift / pending helper；若 main step / advance phase 相關切片已足夠收束，下一步可轉向 attack 前置小切片或下一個 pending payload appender cleanup。
