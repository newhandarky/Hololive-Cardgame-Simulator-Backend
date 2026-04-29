# Gift Confirm Pending Input Builder Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收一般 Gift trigger confirm pending input builder 第一版。

範圍包含：

- `GiftTriggeredEffectConfirmPendingInputBuilder`
- `GiftTriggeredEffectConfirmPendingInputBuilderTest`
- `PlayCardEffectResolutionService` 接線
- `MatchActionService` legacy 一般 Gift pending input 接線

不包含：

- `FollowupTriggerConfirmPendingDecisionWriter` 搬移
- legacy `createTriggeredEffectConfirmPendingInteraction(...)` 搬移
- `buildGiftTriggerInteractionCards(...)` 搬移
- interaction cards shape
- attack pending conversion
- attack post-trigger pending shape
- SQL 欄位
- use case timing

---

## 二、完成條件檢查

### builder 建立

狀態：完成

`GiftTriggeredEffectConfirmPendingInputBuilder` 已建立，負責產生 `FollowupTriggerConfirmPendingDecisionInput`。

builder 內部委派：

- `GiftTriggerPendingPayloadBuilder`
- `GiftSelectionPendingContextBuilder`
- `GiftTriggeredEffectConfirmMessageBuilder`

固定欄位：

- `sourceActionType=GIFT`
- `effectType=GIFT_TRIGGER`
- `title=確認 Gift 效果`

focused tests 覆蓋：

- fixed input shape
- source card 欄位保留
- cards 原樣傳入
- `giftTriggers`
- `giftCount`
- confirm message
- empty Gift triggers fallback
- selection context

### PLAY_CARD 接線

狀態：完成

`PlayCardEffectResolutionService.createGiftTriggeredEffectConfirmPendingInteraction(...)` 已改用 `GiftTriggeredEffectConfirmPendingInputBuilder` 產生 input。

保留：

- `FollowupTriggerConfirmPendingDecisionWriter` 呼叫
- pending decision 建立時機
- source card payload 建立
- triggered resolution order

### MatchActionService legacy 接線

狀態：完成

`MatchActionService.createGiftTriggeredEffectConfirmPendingInteraction(...)` 已改用 `GiftTriggeredEffectConfirmPendingInputBuilder` 產生 input。

保留：

- legacy `createTriggeredEffectConfirmPendingInteraction(...)` 呼叫
- `buildGiftTriggerInteractionCards(...)`
- draw reveal / turn cheer / advance phase / baton touch / attack defender Gift 呼叫點
- attack pending conversion
- attack post-trigger pending shape

---

## 三、Allow / Block 清單

### Allow

- PLAY_CARD / MatchActionService 共用一般 Gift pending input builder。
- input builder 收斂 `giftTriggers`、`giftCount`、selection context、confirm message 與 fixed fields。
- PLAY_CARD 繼續自行呼叫 writer。
- MatchActionService 繼續自行呼叫 legacy pending creation。

### Block

- 不搬 writer。
- 不搬 legacy pending creation。
- 不搬 source cards loader / builder。
- 不改 interaction cards shape。
- 不改 attack pending conversion。
- 不改 attack post-trigger pending。
- 不改 pending SQL。
- 不改 use case timing。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=GiftTriggeredEffectConfirmPendingInputBuilderTest,PlayCardEffectResolutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

測試涵蓋：

- builder focused behavior
- PLAY_CARD Gift confirm pending baseline

---

## 五、剩餘缺口

無 blocker。

可後續補強但不阻擋本輪收斂：

- 補 MatchActionService legacy Gift pending 呼叫面的 focused regression tests。
- 評估是否再抽 legacy pending creation adapter，但需先保護 source cards 與 attack conversion。
- 補 API / integration smoke 驗證一般 Gift pending decision context。
- 重新評估 full `MatchActionServiceIntegrationTest` 既有失敗清單，規劃測試穩定化。

---

## 六、結論

Gift confirm pending input builder 第一版通過 acceptance review。

本輪已完成：

1. builder 建立
2. focused tests
3. PLAY_CARD 接線
4. MatchActionService legacy 接線
5. writer / source cards / attack conversion 邊界保留
6. acceptance review

下一步建議先補 MatchActionService legacy Gift pending focused regression tests，再評估是否能繼續拆 legacy pending creation adapter。
