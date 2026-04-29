# Gift Trigger Confirm Message Builder Cleanup Planning

更新日期：2026-04-29
狀態：規劃完成

---

## 一、背景

`GiftTriggeredEffectDetailsMessageBuilder` 已完成 PLAY_CARD / COLLAB / attack post-trigger 三個 Gift details message 入口收斂。

下一個可見重複點是 Gift trigger confirm message 的 outer shell：

- `確認 Gift 效果`
- `是否要執行本次 Gift 觸發效果？`
- details message append

但 `MatchActionService.buildGiftTriggeredEffectConfirmMessage(...)` 仍被多個 legacy flow 使用，不能當 dead code 直接移除。

---

## 二、現況盤點

### PLAY_CARD use case

`PlayCardEffectResolutionService` 仍內建：

- `buildGiftTriggeredEffectConfirmMessage(...)`
- `buildGiftTriggeredEffectDetails(...)`

其中 details 已委派 `GiftTriggeredEffectDetailsMessageBuilder`。

### MatchActionService legacy facade

`MatchActionService.createGiftTriggeredEffectConfirmPendingInteraction(...)` 仍內建：

- pending interaction common payload
- Gift trigger payload
- Gift selection context
- interaction card list
- Gift confirm message

目前呼叫點包含：

- draw reveal 後 main step Gift
- turn cheer 後 main step Gift
- advance phase deferred Gift
- baton touch back Gift
- attack defender Gift pending

這些入口的 source card / card list / pending conversion 行為不同，不能在同一步搬移 pending creation。

### ATTACK post-trigger

`AttackPostTriggerConfirmMessageBuilder` 只負責 attack post-trigger outer message：

- `是否要執行攻擊後觸發效果？`
- `[Down Event]`
- `[Gift]`

它不是一般 Gift trigger confirm outer shell，下一步不應混入。

---

## 三、建議施工目標

下一個 production slice 建議只抽：

`GiftTriggeredEffectConfirmMessageBuilder`

責任限定：

- 接收 `List<Map<String, Object>> giftTriggeredEffects`
- 產出一般 Gift trigger confirm message
- 保留原格式：
  - empty/null：`是否要執行本次 Gift 觸發效果？`
  - non-empty：`是否要執行本次 Gift 觸發效果？\n` + Gift details
- 內部委派 `GiftTriggeredEffectDetailsMessageBuilder`

第一版接線範圍：

- `PlayCardEffectResolutionService.buildGiftTriggeredEffectConfirmMessage(...)`
- `MatchActionService.buildGiftTriggeredEffectConfirmMessage(...)`

不搬：

- pending interaction creation
- additional context
- interaction cards
- attack post-trigger confirm message

---

## 四、Allow / Block 清單

### Allow

- 新增 `GiftTriggeredEffectConfirmMessageBuilder`。
- builder 內部使用 `GiftTriggeredEffectDetailsMessageBuilder`。
- PLAY_CARD / MatchActionService 的一般 Gift trigger confirm message 共用 builder。
- focused tests 覆蓋 empty / null / non-empty / fallback details。

### Block

- 不改 `createGiftTriggeredEffectConfirmPendingInteraction(...)` 的 pending creation responsibility。
- 不改 `giftTriggers` payload。
- 不改 `giftCount`。
- 不改 Gift selection context。
- 不改 interaction cards。
- 不改 attack post-trigger `AttackPostTriggerConfirmMessageBuilder`。
- 不改 Down Event message。
- 不改 SQL。
- 不改 use case timing。

---

## 五、驗證策略

下一個 production slice 至少新增：

- `GiftTriggeredEffectConfirmMessageBuilderTest`

測試涵蓋：

- null input 回傳預設提問句
- empty input 回傳預設提問句
- non-empty input append Gift details
- invalid requestedEffects fallback 仍由 details builder 生效

接線後至少執行：

- `./mvnw -q -Dtest=GiftTriggeredEffectConfirmMessageBuilderTest,GiftTriggeredEffectDetailsMessageBuilderTest,PlayCardEffectResolutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

若有改動 `MatchActionService` 呼叫面，另加跑可覆蓋 Gift pending 的 focused integration / service tests；若只能跑 broad integration，需先接受目前 full suite 既有失敗清單。

---

## 六、完成標準

第一版完成標準：

1. 新增 confirm message builder
2. PLAY_CARD 一般 Gift confirm message 接線
3. MatchActionService legacy 一般 Gift confirm message 接線
4. attack post-trigger message 不變
5. pending payload / context 不變
6. focused tests 通過
7. 文件進度更新

---

## 七、下一步

下一步建議進入 production slice：

`GiftTriggeredEffectConfirmMessageBuilder` 第一版。

不要同一步搬 `createGiftTriggeredEffectConfirmPendingInteraction(...)`，也不要改 attack post-trigger outer message。
