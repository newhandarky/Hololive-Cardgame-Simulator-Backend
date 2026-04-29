# Gift Trigger Confirm Message Builder Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收一般 Gift trigger confirm message builder 第一版。

範圍包含：

- `GiftTriggeredEffectConfirmMessageBuilder`
- `GiftTriggeredEffectConfirmMessageBuilderTest`
- `PlayCardEffectResolutionService` 接線
- `MatchActionService` legacy 一般 Gift confirm message 接線

不包含：

- `createGiftTriggeredEffectConfirmPendingInteraction(...)` 搬移
- pending payload shape
- Gift selection context
- interaction cards
- attack post-trigger outer message
- Down Event message
- SQL 欄位
- use case timing

---

## 二、完成條件檢查

### builder 建立

狀態：完成

`GiftTriggeredEffectConfirmMessageBuilder` 已建立，負責一般 Gift trigger confirm message outer shell。

輸出格式：

- empty / null / empty details：`是否要執行本次 Gift 觸發效果？`
- non-empty：`是否要執行本次 Gift 觸發效果？\n` + Gift details

builder 內部委派 `GiftTriggeredEffectDetailsMessageBuilder`，不重複 details formatting。

focused tests 覆蓋：

- null input
- empty input
- empty trigger details
- non-empty Gift details append
- requestedEffects normalize / 去重
- invalid requestedEffects fallback

### PLAY_CARD 接線

狀態：完成

`PlayCardEffectResolutionService.buildGiftTriggeredEffectConfirmMessage(...)` 已改用 `GiftTriggeredEffectConfirmMessageBuilder`。

保留：

- `確認 Gift 效果`
- pending decision 建立時機
- pending context `giftTriggers`
- `giftCount`
- selection context
- source card payload
- triggered resolution order

### MatchActionService legacy 接線

狀態：完成

`MatchActionService.buildGiftTriggeredEffectConfirmMessage(...)` 已改用 `GiftTriggeredEffectConfirmMessageBuilder`。

保留：

- `createGiftTriggeredEffectConfirmPendingInteraction(...)` 呼叫面
- legacy main step Gift pending
- advance phase deferred Gift pending
- baton touch back Gift pending
- attack defender Gift pending
- pending payload / context / cards
- pending conversion

---

## 三、Allow / Block 清單

### Allow

- PLAY_CARD / MatchActionService 共用一般 Gift trigger confirm message builder。
- builder 內部委派 Gift details builder。
- empty details 回到預設 Gift confirm message，避免產生只有提問句加空白內容的訊息。

### Block

- 不搬 `createGiftTriggeredEffectConfirmPendingInteraction(...)`。
- 不改 pending payload shape。
- 不改 `giftTriggers`。
- 不改 `giftCount`。
- 不改 Gift selection context。
- 不改 interaction cards。
- 不改 attack post-trigger `AttackPostTriggerConfirmMessageBuilder`。
- 不改 Down Event message。
- 不改 SQL。
- 不改 use case timing。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=GiftTriggeredEffectConfirmMessageBuilderTest,GiftTriggeredEffectDetailsMessageBuilderTest,PlayCardEffectResolutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

測試涵蓋：

- confirm message builder focused behavior
- details builder focused behavior
- PLAY_CARD Gift confirm pending baseline

---

## 五、剩餘缺口

無 blocker。

可後續補強但不阻擋本輪收斂：

- 補 MatchActionService legacy Gift pending 呼叫面的 focused regression tests。
- 評估 `createGiftTriggeredEffectConfirmPendingInteraction(...)` 是否可再拆出 pending interaction service。
- 補 API / integration smoke 驗證一般 Gift pending decision message 文本。
- 重新評估 full `MatchActionServiceIntegrationTest` 既有失敗清單，規劃測試穩定化。

---

## 六、結論

Gift trigger confirm message builder 第一版通過 acceptance review。

本輪已完成：

1. builder 建立
2. focused tests
3. PLAY_CARD 接線
4. MatchActionService legacy 接線
5. pending creation / payload / context 邊界保留
6. attack post-trigger message 邊界保留
7. acceptance review

下一步建議評估一般 Gift pending interaction creation 是否可規劃成獨立 builder/service；若風險偏高，先補 MatchActionService legacy Gift pending focused regression tests 或測試穩定化規劃。
