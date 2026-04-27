# BLOOM Trigger Contract

更新日期：2026-04-27
定位：`BLOOM` pilot trigger / event 契約
用途：定義 BLOOM state mutation 後會產生哪些事件、trigger handler 如何分類同步與延後責任，以及 legacy effect preview/confirm 如何逐步收斂。

---

## 一、Trigger 目標

BLOOM trigger path 的目標是：

- 把 BLOOM 成功後的事實轉成事件
- 讓 effect preview / confirm follow-up 有可描述的 dispatch 邊界
- 避免 resolver 或舊 action method 自己散落 trigger 判斷

第一版重點不是建立全域 trigger framework，而是讓 BLOOM 有局部完整 path。

---

## 二、事件型別

第一版至少定義：

1. `BLOOM_REQUEST_ACCEPTED`
2. `BLOOM_RESOLVED`
3. `BLOOM_EFFECT_PREVIEW_CREATED`
4. `BLOOM_TRIGGER_CONFIRM_REQUIRED`

### 1. `BLOOM_REQUEST_ACCEPTED`

表示 action 已通過 validator，準備進入 resolve。

建議欄位：

- match id
- actor user id
- source card instance id
- target holomem id
- requested turn number
- trace id

### 2. `BLOOM_RESOLVED`

表示 state mutation 已完成。

建議欄位：

- match id
- actor user id
- target holomem id
- bloom card id
- bloom level
- stack depth
- extra allowance used
- level override used

### 3. `BLOOM_EFFECT_PREVIEW_CREATED`

表示 BLOOM 後偵測到可預覽的 bloom triggered effect。

建議欄位：

- match id
- actor user id
- target holomem id
- source card id
- preview summary

### 4. `BLOOM_TRIGGER_CONFIRM_REQUIRED`

表示本次 BLOOM 需要 follow-up confirm interaction。

建議欄位：

- match id
- actor user id
- target holomem id
- pending interaction id 或可重建 context
- confirm summary

---

## 三、Event Factory

`BloomEventFactory` 負責從：

- `BloomAction`
- `BloomResolutionResult`

建立事件列表。

事件順序應固定：

1. request accepted
2. resolved
3. preview created
4. confirm required

其中 preview / confirm 事件只有在 resolution result 帶有對應 summary/follow-up 時才建立。

---

## 四、Trigger Dispatcher

`BloomTriggerDispatcher` 應負責：

- 接收 event list
- 依 event type 找出 handler
- 回傳 dispatch result

第一版可接受 handler 仍是 thin/no-op，但必須明確分類：

- `SYNC`
- `DEFERRED`

分類原則：

- `BLOOM_REQUEST_ACCEPTED`：同步事實事件
- `BLOOM_RESOLVED`：同步事實事件
- `BLOOM_EFFECT_PREVIEW_CREATED`：同步產物已建立
- `BLOOM_TRIGGER_CONFIRM_REQUIRED`：deferred follow-up interaction

---

## 五、Legacy preview / confirm 邊界

目前既有 bloom effect preview / confirm 寫入可暫留在 legacy glue。

但必須滿足：

1. action 主方法不再同時負責 validation + mutation + trigger 分類。
2. legacy glue 產生的 preview / follow-up 要回填到 `BloomResolutionResult`。
3. event factory 只讀 resolution result，不回頭查 legacy payload。

後續收斂方向：

- 將 preview 建立搬進 `BloomEffectPreviewHandler`
- 將 confirm interaction 建立搬進 `BloomTriggerConfirmHandler`
- `MatchActionService.bloom(...)` 只保留 response compatibility

---

## 六、完成標準

本 contract 落地後，應能回答：

1. BLOOM 成功後會產生哪些事件？
2. 哪些 trigger 是同步，哪些是 deferred？
3. preview / confirm 是由哪個 result 欄位驅動？
4. 舊入口是否不再直接散落 trigger dispatch 判斷？
