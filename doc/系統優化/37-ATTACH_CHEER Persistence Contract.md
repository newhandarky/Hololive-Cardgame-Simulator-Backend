# ATTACH_CHEER Persistence Contract

更新日期：2026-04-27
定位：`ATTACH_CHEER` pilot persistence 契約
用途：定義 ATTACH_CHEER 流程中 DB mutation、action log、event visibility 與 response snapshot 的一致性要求。

---

## 一、Persistence 目標

ATTACH_CHEER persistence 必須讓外部觀察到一致狀態：

- Cheer card 已在 `match_cards` 移到 `STAGE`
- Cheer card 已在 `match_holomem_cheers` 附到 target Holomem
- action log 可追蹤
- API response 與 `/state` 不互相矛盾

---

## 二、主要寫入項目

### 1. `match_cards`

source Cheer card 應更新為：

- `zone = STAGE`
- `order_index = NULL`
- `is_face_down = FALSE`
- updated timestamp

### 2. `match_holomem_cheers`

應新增 attachment row：

- `match_holomem_id`
- `match_card_id`
- `cheer_card_id`
- `is_face_down = FALSE`

### 3. `matches`

第一版沿用既有行為：

- `current_phase = MAIN`
- touch `updated_at`

是否需要保留這個 phase touch，可在 adapter 落地時再評估；若改變會影響前端觀察，需另做產品判斷。

### 4. `match_actions`

仍需寫入既有 action log，保持前端與 replay/debug 相容。

action type：

- `ATTACH_CHEER`

payload 至少保留：

- `cheerCardInstanceId`
- `cheerCardId`
- `targetHolomemCardInstanceId`
- `targetHolomemId`
- `sourceFromZone`
- `idempotencyKey`

---

## 三、Transaction 要求

API 入口的 ATTACH_CHEER 流程應在同一 transaction 內完成：

1. validate context load
2. source cheer movement
3. attachment row insert
4. match phase touch
5. action log
6. event dispatch result 建立

Publish snapshot / response 可在 transaction 後重建，但不可讓前端觀察到：

- Cheer card 已到 `STAGE`，但沒有 attachment row
- attachment row 已存在，但 source card 仍在 `HAND` / `CHEER_DECK`
- action log 顯示成功，但 `/state` 看不到附加結果

---

## 四、Action Log 與 Event 的關係

action log 是對外可讀紀錄。event 是內部 orchestration 事實。

第一版允許：

- event 不另存資料表
- action log payload 維持既有相容格式
- trigger handler 是 thin/no-op

但不允許：

- event factory 依賴 action log payload 才能知道發生什麼事
- resolver 直接組 action log payload

---

## 五、Idempotency 第一版限制

`AttachCheerAction` 必須帶 `idempotencyKey`。

第一版 persistence 可暫不建立專用 idempotency table，但必須保留後續落點：

- action payload 保存 key
- 或 `match_actions` 增加 action key 欄位
- 或建立 action submission table

在完整 idempotency persistence 前，source card zone update condition 是主要重送保護。

---

## 六、驗證項目

最低測試要求：

1. direct application integration test 可證明 `resolveState` 會移動 Cheer card 並插入 attachment row。
2. validator unit tests 覆蓋 source zone / non-cheer / missing target。
3. legacy API smoke test 不退步。
4. event factory test 可證明 request accepted / resolved ordering。

---

## 七、完成標準

本 contract 落地後，應能回答：

1. ATTACH_CHEER 寫入了哪些 table？
2. action log 何時寫入？
3. event 與 action log 是否已分離？
4. `/state` 與 action response 是否能重建一致結果？
