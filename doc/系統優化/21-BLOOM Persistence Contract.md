# BLOOM Persistence Contract

更新日期：2026-04-27
定位：`BLOOM` pilot persistence 契約
用途：定義 BLOOM 流程中 DB mutation、action log、pending interaction、event visibility 與 response snapshot 的一致性要求。

---

## 一、Persistence 目標

BLOOM persistence 必須讓外部觀察到一致狀態：

- source card 已離開 hand
- target holomem top card 已更新
- stack 可重建
- action log 可追蹤
- 若需要 confirm，pending interaction 可見
- API response 與 `/state` 不互相矛盾

---

## 二、主要寫入項目

### 1. `match_cards`

source card instance 應更新為：

- `zone = STAGE`
- 清理 hand order
- updated timestamp

### 2. `match_holomem_stack`

應新增一筆 stack record：

- target holomem id
- source card instance id
- stack order

### 3. `match_holomems`

target holomem 應更新：

- current card instance
- current card id
- level
- max hp
- last bloom turn
- updated timestamp

### 4. effect runtime state

若使用 extra bloom allowance：

- 應消耗 allowance

若本次 BLOOM 產生 preview / confirm：

- legacy effect/pending state 第一版可維持既有寫法
- 但必須能回填到 resolution result

### 5. `match_actions`

仍需寫入既有 action log，保持前端與 replay/debug 相容。

action type：

- `BLOOM`

payload 至少保留：

- source card instance id
- target holomem id
- bloom card id
- bloom level
- stack depth
- effect preview summary
- follow-up interaction summary

---

## 三、Transaction 要求

API 入口的 BLOOM 流程應在同一 transaction 內完成：

1. validate context load
2. state mutation
3. effect preview / pending interaction persistence
4. action log
5. event dispatch result 建立

Publish snapshot / response 可在 transaction 後重建，但不可讓前端觀察到：

- state 已更新但 pending interaction 不存在
- pending interaction 已存在但 state 尚未更新
- action log 指向不存在的 stack/top card

---

## 四、Action Log 與 Event 的關係

action log 是對外可讀紀錄。event 是內部 orchestration 事實。

第一版允許：

- event 不另存資料表
- action log payload 維持既有相容格式

但不允許：

- event factory 依賴 action log payload 才能知道發生什麼事
- resolver 直接組 action log payload

---

## 五、Idempotency 第一版限制

`BloomAction` 必須帶 `idempotencyKey`。

第一版 persistence 可暫不建立專用 idempotency table，但必須保留後續落點：

- action payload 保存 key
- 或 `match_actions` 增加 action key 欄位
- 或建立 action submission table

在完整 idempotency persistence 前，once-per-turn / last bloom turn 是主要重送保護。

---

## 六、驗證項目

最低測試要求：

1. direct application integration test 可證明 `resolveState` 會移動 hand card、append stack、更新 holomem top card。
2. API/legacy entry focused test 可證明既有 BLOOM rejection 沒退步。
3. event factory test 可證明 preview / confirm 事件只在 result 有資訊時建立。

---

## 七、完成標準

本 contract 落地後，應能回答：

1. BLOOM 寫入了哪些 table？
2. action log 何時寫入？
3. pending interaction 何時可見？
4. event 與 action log 是否已分離？
5. `/state` 與 action response 是否能重建一致結果？
