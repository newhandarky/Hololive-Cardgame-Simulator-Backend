# COLLAB Persistence Contract

更新日期：2026-04-27
定位：`COLLAB` pilot persistence 契約
用途：定義 COLLAB 流程中 DB mutation、action log、pending interaction、event visibility 與 response snapshot 的一致性要求。

---

## 一、Persistence 目標

COLLAB persistence 必須讓外部觀察到一致狀態：

- source Holomem 已從 BACK 移到 COLLAB
- top deck card 已移入 holopower
- action log 可追蹤
- 若需要 confirm，pending interaction 可見
- API response 與 `/state` 不互相矛盾

---

## 二、主要寫入項目

### 1. `match_holomems`

source Holomem 應更新為：

- `zone = COLLAB`
- updated timestamp

### 2. `match_cards`

deck top card 應更新為：

- `zone = HOLOPOWER`
- 清理或更新 order 欄位
- updated timestamp

實際欄位需沿用既有 `moveTopDeckCardToHolopower(...)` 的資料契約。

### 3. `match_pending_decisions`

若本次 COLLAB 產生 collab effect 或 gift trigger preview：

- 建立 `TRIGGER_EFFECT_CONFIRM`
- `source_action_type = COLLAB`
- `effect_type = COLLAB_TRIGGER`
- context_json 保留 collab / gift trigger sections

### 4. `match_actions`

仍需寫入既有 action log，保持前端與 replay/debug 相容。

action type：

- `COLLAB`

payload 至少保留：

- source card instance id
- source card id
- source zone
- target zone
- holopower card instance id
- collab effect summary
- collab gift effect summary
- trigger summary
- trigger resolution order
- pending interaction decision id/type

---

## 三、Transaction 要求

API 入口的 COLLAB 流程應在同一 transaction 內完成：

1. validate context load
2. BACK -> COLLAB mutation
3. deck top -> holopower mutation
4. collab / gift preview
5. pending interaction persistence
6. action log
7. event dispatch result 建立

Publish snapshot / response 可在 transaction 後重建，但不可讓前端觀察到：

- Holomem 已進 COLLAB，但 holopower 未增加
- pending interaction 已存在，但 COLLAB state 尚未更新
- action log 指向不存在的 holopower card

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

`CollabAction` 必須帶 `idempotencyKey`。

第一版 persistence 可暫不建立專用 idempotency table，但必須保留後續落點：

- action payload 保存 key
- 或 `match_actions` 增加 action key 欄位
- 或建立 action submission table

在完整 idempotency persistence 前，once-per-turn / existing `COLLAB` action log 是主要重送保護。

---

## 六、驗證項目

最低測試要求：

1. direct application integration test 可證明 `resolveState` 會移動 Holomem 並移動 top deck card 到 holopower。
2. existing legacy API rejection path 不退步。
3. event factory test 可證明 effect / gift / confirm 事件只在 result 有資訊時建立。
4. effect resolution test 可證明無 effect 時不建 pending，有 effect/gift 時會建立 confirm。

---

## 七、完成標準

本 contract 落地後，應能回答：

1. COLLAB 寫入了哪些 table？
2. action log 何時寫入？
3. pending interaction 何時可見？
4. event 與 action log 是否已分離？
5. `/state` 與 action response 是否能重建一致結果？
