# COLLAB Event Trigger Contract

更新日期：2026-04-27
定位：`COLLAB` pilot event / trigger 契約
用途：定義 COLLAB state mutation 與 follow-up resolution 後會產生哪些事件、trigger handler 如何分類同步與延後責任。

---

## 一、Trigger 目標

COLLAB trigger path 的目標是：

- 把 COLLAB 成功後的事實轉成事件
- 讓 collab effect / gift preview / confirm follow-up 有可描述的 dispatch 邊界
- 避免 resolver 或舊 action method 自己散落 trigger 判斷

第一版重點不是建立全域 trigger framework，而是讓 COLLAB 有局部完整 path。

---

## 二、事件型別

第一版至少定義：

1. `COLLAB_REQUEST_ACCEPTED`
2. `COLLAB_RESOLVED`
3. `COLLAB_EFFECT_PREVIEW_CREATED`
4. `COLLAB_GIFT_PREVIEW_CREATED`
5. `COLLAB_TRIGGER_CONFIRM_REQUIRED`

### 1. `COLLAB_REQUEST_ACCEPTED`

表示 action 已通過 validator，準備進入 resolve。

建議欄位：

- match id
- actor user id
- source card instance id
- requested turn number
- trace id

### 2. `COLLAB_RESOLVED`

表示 state mutation 已完成。

建議欄位：

- match id
- actor user id
- source holomem id
- source card instance id
- source card id
- from zone
- target zone
- holopower card instance id

### 3. `COLLAB_EFFECT_PREVIEW_CREATED`

表示 COLLAB 後偵測到 collab triggered effect preview。

建議欄位：

- match id
- actor user id
- source card instance id
- source card id
- preview summary

### 4. `COLLAB_GIFT_PREVIEW_CREATED`

表示 COLLAB 觸發 gift preview。

建議欄位：

- match id
- actor user id
- source card instance id
- gift preview summary
- trigger count

### 5. `COLLAB_TRIGGER_CONFIRM_REQUIRED`

表示本次 COLLAB 需要 follow-up confirm interaction。

建議欄位：

- match id
- actor user id
- source card instance id
- pending interaction id 或可重建 context
- confirm summary

---

## 三、Event Factory

`CollabEventFactory` 負責從：

- `CollabAction`
- `CollabResolutionResult`
- `CollabEffectResolution`

建立事件列表。

事件順序應固定：

1. request accepted
2. resolved
3. collab effect preview created
4. gift preview created
5. confirm required

其中 preview / confirm 事件只有在 effect resolution result 帶有對應 summary/follow-up 時才建立。

---

## 四、Trigger Dispatcher

`CollabTriggerDispatcher` 應負責：

- 接收 event list
- 依 event type 找出 handler
- 回傳 dispatch result

第一版可接受 handler 仍是 thin/no-op，但必須明確分類：

- `SYNC`
- `DEFERRED`

分類原則：

- `COLLAB_REQUEST_ACCEPTED`：同步事實事件
- `COLLAB_RESOLVED`：同步事實事件
- `COLLAB_EFFECT_PREVIEW_CREATED`：同步產物已建立
- `COLLAB_GIFT_PREVIEW_CREATED`：同步產物已建立
- `COLLAB_TRIGGER_CONFIRM_REQUIRED`：deferred follow-up interaction

---

## 五、Legacy Hook 與新 Event 的關係

目前既有：

- `MatchEventHookService.onHolomemCollab(...)`

可暫時保留在 `CollabEffectResolutionService` 中，作為 legacy hook summary。

但必須滿足：

1. `CollabEventFactory` 不依賴 action log payload。
2. legacy hook summary 回填到 `CollabEffectResolution`。
3. trigger dispatcher 以 `CollabEvent` 為輸入。

---

## 六、完成標準

本 contract 落地後，應能回答：

1. COLLAB 成功後會產生哪些事件？
2. 哪些 trigger 是同步，哪些是 deferred？
3. collab effect / gift preview 事件由哪些 result 欄位驅動？
4. 舊入口是否不再直接散落 trigger dispatch 判斷？
