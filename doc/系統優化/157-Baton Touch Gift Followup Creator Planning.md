# Baton Touch Gift Followup Creator Planning

日期：2026-04-30
狀態：規劃完成

## 背景

main step 與 advance phase 的 sourceless Gift pending 已完成 shared helper 與 payload appender cleanup：

- `155-Gift Pending Shared Helper Cleanup Acceptance Review.md`
- `156-Main Step Gift Followup Payload Appender Acceptance Review.md`

`MatchActionService.batonTouch(...)` 仍保留 baton touch Gift followup 的 preview / summary / pending decision 組裝：

- `matchGiftTriggerService.previewGiftTriggeredEffectsOnBatonTouchBack(...)`
- `buildGiftTriggeredEffectDeferredSummary(...)`
- `createBatonTouchGiftTriggerDecision(...)`
- `followupDecisionPayloadAppender.append(...)`

這條路徑不能直接套用 `SourcelessGiftPendingDecisionCreator`，因為 `BATON_TOUCH_BACK` pending 必須保留 source card instance id / card id。

## 既有保護

已存在的保護文件：

- `107-Baton Touch Gift Pending Source Card Regression Acceptance Review.md`
- `108-Baton Touch Gift Pending Adapter Cleanup Acceptance Review.md`

可用 focused smoke：

- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#batonTouchShouldCreateGiftConfirmWhenTargetMovedBackTriggersGift test`

此 smoke 會檢查：

- `BATON_TOUCH_BACK` pending context。
- pending source card instance id / card id。
- `BATON_TOUCH` action payload 內 `batonTouchGiftEffect`。
- resolve 後建立對應 `GIFT_TRIGGER` payload。

## 目標

規劃下一個低風險切片：新增 package-private `BatonTouchGiftFollowupCreator`，只負責 baton touch Gift followup 的 preview / summary / source-card pending decision creation。

建議第一版 API：

```java
class BatonTouchGiftFollowupCreator {
    BatonTouchGiftFollowup create(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        int turnNumber
    );
}
```

`BatonTouchGiftFollowup` 建議包含：

- `Map<String, Object> giftEffectSummary`
- `FollowupInteractionDecision decision`
- `boolean hasGiftEffects()`

`MatchActionService.batonTouch(...)` 保留 payload assembly 與 action append，只改為：

- 在原本 preview / pending creation 位置呼叫 creator。
- 在原本 payload append 位置檢查 `hasGiftEffects()` 後放入 `batonTouchGiftEffect` 並 append pending decision payload。

## Allow List

- 新增 `BatonTouchGiftFollowupCreator` 與小型 result record。
- 保留 `createBatonTouchGiftTriggerDecision(...)` 語意，或將它搬入 creator 內部。
- 保留 source card instance id / card id 傳入 `GiftPendingDecisionCreator`。
- 保留 baton touch movement / cost / phase transition / action append 順序。
- 補 creator unit test。
- 跑 baton touch focused smoke。

## Block List

- 不套用 `SourcelessGiftPendingDecisionCreator`。
- 不改 `BATON_TOUCH_BACK` trigger type。
- 不改 pending context JSON shape。
- 不改 source cards payload shape。
- 不改 baton touch cost、movement、phase transition 或 once-per-turn rules。
- 不碰 PLAY_CARD、main step、advance phase 或 attack Gift pending。
- 不把 baton touch 整條 flow 搬成 application service。

## 建議批次

### BTGF-1：planning checkpoint

- 補本文件。
- 更新系統優化總覽與重構進度追蹤。

### BTGF-2：creator extraction

- 新增 `BatonTouchGiftFollowupCreator`。
- 新增 focused unit test，鎖住：
  - 無 Gift effect 時 result 為空。
  - 有 Gift effect 時產生 summary 與 source-card pending decision。
  - source card instance id / card id 會被傳入 `GiftPendingDecisionCreator`。
- `MatchActionService.batonTouch(...)` 改為委派 creator。

### BTGF-3：focused smoke

- 跑 `MatchActionServiceIntegrationTest#batonTouchShouldCreateGiftConfirmWhenTargetMovedBackTriggersGift`。
- 視 diff 內容補跑 `GiftPendingDecisionCreatorTest` 或 baton touch 相關 source card regression。

### BTGF-4：acceptance review

- 對照 allow / block list。
- 記錄測試結果與剩餘缺口。
- 決定下一步是否回到 attack 前置小切片。

## 風險判讀

這一步不能追求壓縮最大行數；重點是把 baton touch source-card Gift followup 的語意封裝清楚，避免它被誤接到 sourceless Gift pending path。第一版只抽 creator，不改 payload key、不改 pending context、不改 baton touch 主流程規則。

## 下一步

進入 code review / commit checkpoint。

commit 後進入 BTGF-2，實作 `BatonTouchGiftFollowupCreator`。
