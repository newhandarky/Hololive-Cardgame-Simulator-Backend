# Gift Pending Shared Helper Cleanup Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本文件驗收 GPSC-2 shared adapter extraction。

本步只收斂 main step 與 advance phase sourceless Gift pending creation adapter：

- `MatchActionService.appendMainStepGiftFollowupPayload(...)`
- `AdvancePhaseFollowupCreator.createAdvancePhaseFollowup(...)`

## 二、完成內容

- 新增 `SourcelessGiftPendingDecisionCreator`。
- 將 `sourceCardInstanceId = null` / `sourceCardId = null` 的 Gift pending 建立語意集中到 shared adapter。
- `MatchActionService` main step Gift followup 改為委派 adapter。
- `AdvancePhaseFollowupCreator` own / opponent Gift followup 改為委派 adapter。
- 移除兩個 sourceless Gift pending private helper 的重複委派邏輯。
- 新增 `SourcelessGiftPendingDecisionCreatorTest` 鎖住 adapter 只用 `null, null` source card 委派。
- 更新 `AdvancePhaseFollowupCreatorTest`，改驗證 advance phase followup 委派 shared adapter。

## 三、舊入口 allow / block 對照

### Allow

- main step sourceless Gift pending 改走 shared adapter。
- advance phase own / opponent sourceless Gift pending 改走 shared adapter。
- 保留 `GiftPendingDecisionCreator` 作為底層 writer / interaction cards creator。
- 保留 focused smoke 驗證舊入口行為。

### Block

- 未碰 baton touch source card Gift pending。
- 未碰 PLAY_CARD played card payload / Gift pending。
- 未碰 attack post-trigger pending 與 defender Gift pending conversion。
- 未重命名或移除 `GiftPendingDecisionCreator`。
- 未改 Gift pending context JSON shape。
- 未改 pending interaction cards、action type、message 文字或前端 payload key。

## 四、測試缺口

目前沒有本批 blocker。

保留缺口：

- 未跑完整 `MatchActionServiceIntegrationTest`；該 suite 既有廣域不穩定，不作為本批 blocker。
- shared adapter 目前是薄委派，沒有額外分支；若未來加入空值防線或語意轉換，需要補更多 unit coverage。

## 五、驗證結果

- 已通過：`./mvnw -q -Dtest=AdvancePhaseFollowupCreatorTest,SourcelessGiftPendingDecisionCreatorTest,GiftPendingDecisionCreatorTest test`
- 已通過，需沙盒外 Docker/Testcontainers：`./mvnw -q -Dtest=MatchActionServiceIntegrationTest#mainStepGiftHsd13013ShouldArchiveStackCostAndAttachCheerFromDeckTop,MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers,MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceEndGiftConfirmForBothPlayers test`

## 六、結論

GPSC-2 可收束。

main step 與 advance phase 的 sourceless Gift pending creation 已有 shared adapter，且 baton touch / PLAY_CARD / attack path 未被牽動。

## 七、下一步

進入 code review / commit checkpoint。

commit 後建議評估下一個低風險切片：優先看 `MatchActionService` 內 remaining main step Gift followup payload 是否還有可抽出的小 builder；若收益偏低，轉向下一個文件路線上的 Gift / pending acceptance review 或 attack 相關前置小切片。
