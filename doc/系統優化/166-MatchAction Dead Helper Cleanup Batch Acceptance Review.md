# MatchAction Dead Helper Cleanup Batch Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本批範圍

本文件收束 `164` 至 `165` 的 MatchAction dead helper cleanup batch：

- `164-MatchAction Triggered Effect Dead Helper Cleanup Acceptance Review.md`
- `165-MatchAction Gift Deferred Summary Dead Wrapper Cleanup Acceptance Review.md`

本批目標是移除 `MatchActionService` 內已由其他 service / builder 接手後遺留的無呼叫點 private helper。

## 二、完成內容

- 移除 `buildTriggeredEffectConfirmMessage(...)`。
- 移除 `buildTriggeredEffectDeferredSummary(...)`。
- 移除 `buildGiftTriggeredEffectDeferredSummary(...)`。
- 保留實際仍在使用的 `BloomEffectResolutionService` / `CollabEffectResolutionService` 專屬 message / summary builder。
- 保留 `GiftTriggeredEffectDeferredSummaryBuilder` field，因為仍需注入給 main step / advance phase / baton touch helper。

## 三、Allow / Block 對照

### Allow

- 移除無呼叫點 private helper / wrapper。
- 只用呼叫點查證與 focused tests 作為本批門檻。
- 保留仍有使用者的 shared builder / service dependency。

### Block

- 不改 BLOOM / COLLAB / Gift summary shape。
- 不改 confirm message 文案。
- 不改 pending decision timing。
- 不改 pending context JSON shape。
- 不改 `mainStepGiftEffects` / `batonTouchGiftEffect` / advance phase payload key。
- 不繼續刪除仍屬實際流程的 helper。

## 四、測試結果

已通過：

- `./mvnw -q -Dtest=MatchActionServiceTest,BloomEffectResolutionServiceTest,CollabEffectResolutionServiceTest test`
- `./mvnw -q -Dtest=MatchActionServiceTest,GiftTriggeredEffectDeferredSummaryBuilderTest,MainStepGiftFollowupPayloadAppenderTest,BatonTouchGiftFollowupCreatorTest,AdvancePhasePayloadBuilderTest test`
- `git diff --check`

## 五、大檔尺寸變化

- `MatchActionService.java`：`6,180` -> `6,134` 行，減少 `46` 行。

## 六、剩餘缺口

目前沒有本批 blocker。

保留缺口：

- `MatchActionService` 仍有多個 legacy helper，但目前不再有同級明確 dead wrapper 可安全刪除。
- 後續若要繼續拆，應改以 use case / lifecycle 邊界做 planning，不應延續 blind dead-helper sweep。
- 完整 `MatchActionServiceIntegrationTest` 仍有既有廣域不穩定，不作為本批 blocker。

## 七、結論

MatchAction dead helper cleanup batch 可收束。

本批只移除無呼叫點 helper，未改 BLOOM / COLLAB / Gift 實際流程、payload shape、pending timing 或 message 文案。

## 八、下一步

進入 code review / commit checkpoint。

commit 後建議回到較高層路線盤點下一個高收益 cleanup；候選方向是 MatchAction lifecycle / pending interaction helper，或 MatchEffectService reader / payload builder 型 cleanup。
