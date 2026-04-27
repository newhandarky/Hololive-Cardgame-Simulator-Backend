# PLAY_CARD Event Trigger Contract

更新日期：2026-04-27
定位：`PLAY_CARD` pilot event / trigger 契約
用途：定義 PLAY_CARD state mutation 與 follow-up 完成後要產生哪些事件、事件順序、trigger dispatch 邊界與 legacy action log 的責任差異。

---

## 一、Event 目標

PLAY_CARD events 用來描述內部 orchestration 事實。

它們不是：

- legacy action log payload
- 前端 response DTO
- WebSocket snapshot 本身

第一版 event / trigger 可保持 thin，但必須固定順序與責任邊界。

---

## 二、建議事件

第一版至少定義：

1. `PLAY_CARD_REQUEST_ACCEPTED`
2. `PLAY_CARD_RESOLVED`
3. `PLAY_CARD_ENTER_HOOK_RESOLVED`
4. `PLAY_CARD_GIFT_PREVIEW_CREATED`
5. `PLAY_CARD_GIFT_CONFIRM_REQUIRED`

RESET 開場若沒有 immediate follow-up：

- 仍可只產生 request accepted / resolved
- resolved payload 應標示 `deferredUntilLiveStart = true`

MAIN 正常放置：

- 若 enter hook 有結果，產生 enter hook resolved
- 若 Gift preview 不為空，產生 gift preview created
- 若建立 pending confirm，產生 gift confirm required

---

## 三、事件順序

事件順序固定：

1. request accepted
2. state resolved
3. enter hook resolved
4. gift preview created
5. gift confirm required

trigger dispatcher 應拒絕倒序事件。

---

## 四、Trigger dispatch

第一版 trigger handler 可保持 thin。

建議 sync：

- `PLAY_CARD_REQUEST_ACCEPTED`
- `PLAY_CARD_RESOLVED`
- `PLAY_CARD_ENTER_HOOK_RESOLVED`
- `PLAY_CARD_GIFT_PREVIEW_CREATED`

建議 deferred：

- `PLAY_CARD_GIFT_CONFIRM_REQUIRED`

deferred 的意思是：

- action 本身已完成 state mutation
- 後續效果要等 pending decision resolve

---

## 五、Event payload 最低要求

### `PLAY_CARD_REQUEST_ACCEPTED`

至少包含：

- `cardInstanceId`
- `targetZone`
- `openingReset`

### `PLAY_CARD_RESOLVED`

至少包含：

- `cardInstanceId`
- `cardId`
- `targetZone`
- `matchHolomemId`
- `enteredTurnNumber`
- `faceDown`
- `currentLevel`

### `PLAY_CARD_ENTER_HOOK_RESOLVED`

至少包含：

- `triggerSummary`

### `PLAY_CARD_GIFT_PREVIEW_CREATED`

至少包含：

- `giftEffectSummary`
- `giftTriggerCount`

### `PLAY_CARD_GIFT_CONFIRM_REQUIRED`

至少包含：

- `decisionId`
- `decisionType`
- `triggerType`

---

## 六、Legacy action log 邊界

第一版仍允許 `MatchActionService.playToStage(...)` 寫 legacy action log：

- `OPENING_SET_CENTER`
- `OPENING_SET_BACK`
- `PLAY_TO_STAGE`

但 event factory 不應依賴 action log payload。

action log 是：

- 外部相容
- audit / debug 記錄

event 是：

- 內部 trigger orchestration 的事實來源

---

## 七、完成標準

本 contract 落地後，應能回答：

1. PLAY_CARD 會產生哪些 events？
2. RESET 與 MAIN 的 event 差異在哪？
3. 哪些 trigger 是 sync，哪些是 deferred？
4. event factory 是否不依賴 action log payload？
5. trigger dispatch 分類是否離開 legacy `playToStage(...)`？
