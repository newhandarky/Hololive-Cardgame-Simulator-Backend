# Gift Follow-up Message Builder Planning

更新日期：2026-04-29
狀態：規劃完成

---

## 一、背景

完成 Gift pending payload、selection context、deferred summary builder 後，PLAY_CARD / COLLAB 仍有一段重複的 Gift effect details message 組裝。

目前可觀察到三種相關責任：

1. Gift effect details message
2. use case confirm message outer shell
3. trigger sections payload

這三者不應在同一步混成一個 builder。

---

## 二、現況盤點

### PLAY_CARD

`PlayCardEffectResolutionService` 目前仍內建：

- `buildGiftTriggeredEffectConfirmMessage(...)`
- `buildGiftTriggeredEffectDetails(...)`

其中 `buildGiftTriggeredEffectDetails(...)` 負責組：

- `#N`
- `giftHolderCardId`
- `[triggerType]`
- `效果類型：...`
- `rawText`

### COLLAB

`CollabEffectResolutionService` 目前仍內建：

- `buildCollabTriggeredEffectConfirmMessage(...)`
- `buildTriggeredEffectConfirmMessage(...)`
- `buildCollabTriggerSections(...)`
- `buildGiftTriggeredEffectDetails(...)`

其中 `buildGiftTriggeredEffectDetails(...)` 和 PLAY_CARD 的 details 格式一致。

但 `buildCollabTriggeredEffectConfirmMessage(...)` 還需要組 Collab section：

- `[Collab]`
- Collab rawText
- Collab effectTypes

`buildCollabTriggerSections(...)` 還需要組 payload section：

- `COLLAB_EFFECT`
- `GIFT`

這些是 COLLAB use case 專屬責任。

### ATTACK_POST_TRIGGER

`AttackPostTriggerConfirmMessageBuilder` 已存在，並已有 focused tests。

其中 `buildGiftTriggeredEffectDetails(...)` 和 PLAY_CARD / COLLAB 的 Gift details 格式高度一致，但目前屬於 attack post-trigger builder 的內部 helper。

---

## 三、建議施工目標

下一個 production slice 建議只抽：

`GiftTriggeredEffectDetailsMessageBuilder`

責任限定：

- 接收 `List<Map<String, Object>> giftTriggeredEffects`
- 產出 Gift details 文字
- 保留原格式：
  - `#N`
  - card id
  - `[TRIGGER_TYPE]`
  - `效果類型：...`
  - raw text
- normalize `triggerType`
- normalize / 去重 `requestedEffects`
- invalid requested effects fallback 為 `無可解析效果類型`

第一版接線範圍：

- `PlayCardEffectResolutionService.buildGiftTriggeredEffectDetails(...)`
- `CollabEffectResolutionService.buildGiftTriggeredEffectDetails(...)`

第二步再評估：

- `AttackPostTriggerConfirmMessageBuilder.buildGiftTriggeredEffectDetails(...)`

原因：

- attack post-trigger builder 已有完整 Down Event + Gift message 責任。
- 先接 PLAY_CARD / COLLAB 可降低風險。
- attack 可以在 acceptance review 後再做第二個小 slice。

---

## 四、不做事項

本規劃不建議下一步處理：

- 不抽 PLAY_CARD 的 outer confirm message。
- 不抽 COLLAB 的 outer confirm message。
- 不抽 COLLAB trigger sections。
- 不抽 attack Down Event message。
- 不改 pending decision writer。
- 不改 pending context shape。
- 不改 `match_pending_decisions` SQL 欄位。
- 不處理完整 `MatchActionServiceIntegrationTest` 廣域失敗。

---

## 五、驗證策略

下一個 production slice 建議新增 focused tests：

- empty / null input 回傳空字串或既定 fallback
- 單一 Gift trigger details
- 多個 Gift trigger details
- triggerType normalize
- requestedEffects normalize / 去重
- rawText append
- invalid requestedEffects fallback

接線後至少執行：

- `./mvnw -q -Dtest=GiftTriggeredEffectDetailsMessageBuilderTest,PlayCardEffectResolutionServiceTest,CollabEffectResolutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

若第二步要接 attack：

- 加跑 `AttackPostTriggerConfirmMessageBuilderTest`
- 不在同一步改 Down Event message。

---

## 六、完成標準

第一版完成標準：

1. 新增 `GiftTriggeredEffectDetailsMessageBuilder`
2. PLAY_CARD / COLLAB 共用 builder
3. PLAY_CARD / COLLAB confirm message outer shell 不變
4. COLLAB trigger sections 不變
5. focused tests 通過
6. 文件進度更新

---

## 七、下一步

下一步建議進入 production slice：

`GiftTriggeredEffectDetailsMessageBuilder` 第一版，先接 PLAY_CARD / COLLAB。

不要同一步接 attack post-trigger，也不要抽 trigger sections。
