# Gift Pending Interaction Creation Cleanup Planning

更新日期：2026-04-29
狀態：規劃完成

---

## 一、背景

Gift trigger confirm message 已完成共用 builder。

下一個重複點是一般 Gift trigger confirm pending interaction creation：

- `giftTriggers` payload
- `giftCount`
- Gift selection context
- `FollowupTriggerConfirmPendingDecisionInput`
- 固定欄位：`GIFT`、`GIFT_TRIGGER`、`確認 Gift 效果`

但目前 pending creation 同時跨 PLAY_CARD 與 `MatchActionService` legacy flows，且 `MatchActionService` 仍負責多種 source cards 與 attack pending conversion，不能直接搬成一個完整 service。

---

## 二、現況盤點

### PLAY_CARD

`PlayCardEffectResolutionService.createGiftTriggeredEffectConfirmPendingInteraction(...)` 目前負責：

- 建立 Gift trigger payload
- 建立 Gift selection context
- 組 `FollowupTriggerConfirmPendingDecisionInput`
- 呼叫 `FollowupTriggerConfirmPendingDecisionWriter`

PLAY_CARD 的 cards 來源單純：由 `FollowupSourceCardPayloadBuilder.buildOwnedCard(...)` 建立。

### MatchActionService legacy

`MatchActionService.createGiftTriggeredEffectConfirmPendingInteraction(...)` 目前負責：

- 建立 Gift trigger payload
- 建立 Gift selection context
- 呼叫 legacy `createTriggeredEffectConfirmPendingInteraction(...)`

呼叫點包含：

- draw reveal 後 main step Gift
- turn cheer 後 main step Gift
- advance phase deferred Gift
- baton touch back Gift
- attack defender Gift pending

`MatchActionService.buildGiftTriggerInteractionCards(...)` 仍負責 source cards：

- source card
- Gift holder card
- 去重
- zone fallback

attack defender Gift pending 還會透過 `AttackPendingDecisionConversionService` 轉成 attack pending decision，這不應在第一版搬移。

---

## 三、建議施工目標

下一個 production slice 建議只抽：

`GiftTriggeredEffectConfirmPendingInputBuilder`

責任限定：

- 接收：
  - match id
  - user id
  - source card instance id
  - source card id
  - cards
  - Gift triggered effects
  - turn number
- 產出 `FollowupTriggerConfirmPendingDecisionInput`
- 內部委派：
  - `GiftTriggerPendingPayloadBuilder`
  - `GiftSelectionPendingContextBuilder`
  - `GiftTriggeredEffectConfirmMessageBuilder`
- 固定欄位：
  - effect type：`GIFT`
  - source action type：`GIFT_TRIGGER`
  - prompt：`確認 Gift 效果`

第一版接線範圍：

- `PlayCardEffectResolutionService.createGiftTriggeredEffectConfirmPendingInteraction(...)`
- `MatchActionService.createGiftTriggeredEffectConfirmPendingInteraction(...)`

保留在原 use case：

- writer 呼叫
- legacy `createTriggeredEffectConfirmPendingInteraction(...)`
- source cards 建立
- attack pending conversion

---

## 四、Allow / Block 清單

### Allow

- 新增 `GiftTriggeredEffectConfirmPendingInputBuilder`。
- 將 `giftTriggers` / `giftCount` / selection context / message 組裝收斂到 input builder。
- PLAY_CARD 使用 builder 後仍呼叫 `FollowupTriggerConfirmPendingDecisionWriter`。
- MatchActionService 使用 builder 後仍呼叫 legacy `createTriggeredEffectConfirmPendingInteraction(...)`。
- focused tests 驗證 input shape。

### Block

- 不搬 `FollowupTriggerConfirmPendingDecisionWriter` 呼叫。
- 不搬 `createTriggeredEffectConfirmPendingInteraction(...)`。
- 不搬 `buildGiftTriggerInteractionCards(...)`。
- 不改 interaction cards shape。
- 不改 attack pending conversion。
- 不改 attack post-trigger pending。
- 不改 pending SQL。
- 不改 use case timing。

---

## 五、驗證策略

下一個 production slice 至少新增：

- `GiftTriggeredEffectConfirmPendingInputBuilderTest`

測試涵蓋：

- fixed effect/source action/prompt 欄位
- source card 欄位保留
- cards 原樣傳入
- `giftTriggers` payload
- `giftCount`
- selection context
- confirm message
- empty Gift triggers fallback

接線後至少執行：

- `./mvnw -q -Dtest=GiftTriggeredEffectConfirmPendingInputBuilderTest,PlayCardEffectResolutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

若 MatchActionService 接線改動較大，需補跑可覆蓋 legacy Gift pending 的 focused test；若只能跑 full `MatchActionServiceIntegrationTest`，需先接受目前既有廣域失敗清單。

---

## 六、完成標準

第一版完成標準：

1. 新增 pending input builder
2. builder focused tests 通過
3. PLAY_CARD pending input 組裝接線
4. MatchActionService legacy pending input 組裝接線
5. writer / legacy creation / source cards / attack conversion 邊界保留
6. 文件進度更新

---

## 七、下一步

下一步建議進入 production slice：

`GiftTriggeredEffectConfirmPendingInputBuilder` 第一版。

不要同一步搬 writer、source cards 或 attack pending conversion。
