# COLLAB Validator Contract

更新日期：2026-04-27
定位：`COLLAB` pilot validator 契約
用途：定義 `CollabActionValidator` 的資料輸入、檢查順序、錯誤語意與不可承擔的責任。

---

## 一、Validator 目標

`CollabActionValidator` 只回答一件事：

- 這個 `CollabAction` 在目前 state 下能不能執行

它不應負責：

- 移動 Holomem
- 移動牌庫頂到 holopower
- preview collab effect
- preview gift trigger
- 建立 pending interaction
- 寫 action log
- dispatch trigger

---

## 二、輸入資料

Validator 輸入應拆成：

1. `CollabAction`
2. `CollabValidationContext`

`CollabValidationContext` 應由 bridge / loader 負責載入，不由 validator 自己查 DB。

第一版 context 至少包含：

- match 狀態
- lobby 狀態
- current phase
- current turn player
- current turn number
- actor player 是否存在
- 是否存在 blocking pending interaction
- source holomem snapshot
- source zone
- source rested 狀態
- COLLAB zone occupancy
- 是否本回合已執行 COLLAB
- stage action lock
- deck top 是否存在

---

## 三、檢查順序

建議固定順序如下。

### 1. Match gate

檢查：

- match 是否存在
- match status 是否 active
- lobby status 是否 `STARTED` 或 `ACTIVE`
- actor 是否在 match 中

### 2. Turn / phase gate

檢查：

- `requestedTurnNumber` 是否等於目前 turn
- 是否輪到 actor
- phase 是否允許 COLLAB
- 是否有 blocking pending interaction

第一版允許 phase：

- `MAIN`

### 3. Action input gate

檢查：

- `sourceCardInstanceId` 是否存在
- `targetZone` 是否為 `COLLAB`
- action source / idempotency metadata 是否存在

### 4. Stage action lock gate

檢查：

- 是否有 `ACTION_LOCK` 阻擋 `COLLAB`
- 若 lock 僅阻擋 `MOVE_STAGE`，需確認是否也應阻擋 COLLAB

第一版建議：

- `COLLAB` 應同時受 `MOVE_STAGE` 與 `COLLAB` 類 stage action lock 影響

若既有資料只寫 `MOVE_STAGE`，bridge 可先維持相容判斷。

### 5. Source holomem gate

檢查：

- source holomem 是否存在
- source 是否屬於 actor
- source 是否在 `BACK`
- source 是否不是 rested
- source top card 是否存在

### 6. Target zone gate

檢查：

- actor 的 `COLLAB` zone 是否已被占用

若已有 COLLAB Holomem，應拒絕。

### 7. Once-per-turn gate

檢查：

- actor 本回合是否已執行過 `COLLAB`

第一版可沿用 `match_actions` 中 `action_type = 'COLLAB'` 判斷。

### 8. Deck / holopower gate

檢查：

- 是否需要在 validator 階段確認 deck top 存在

第一版建議：

- validator 可以確認 deck 有卡，避免 resolver 才發現無牌。
- 若既有規則允許 deck empty 時仍 COLLAB，需在此 contract 更新前先做產品判斷。

目前不主動更改既有規則；先以現行 `moveTopDeckCardToHolopower(...)` 行為為準。

---

## 四、輸出結果

Validator 應回傳：

- `CollabValidationResult.allowed(...)`
- `CollabValidationResult.rejected(...)`

Application 層負責把 rejected result 轉成：

- `GameRuleException`

第一版不要求回傳錯誤集合。遇到第一個明確失敗原因即可回傳。

---

## 五、錯誤語意

錯誤應盡量使用 domain error code。

最低要求：

- stale action
- not your turn
- invalid phase
- pending interaction blocked
- source not found
- source not in BACK
- source rested
- COLLAB zone occupied
- collab already used this turn
- stage action locked
- deck top missing

若既有 `GameErrorCode` 尚未有完全對應值，第一版可先沿用最接近的 code，但 message/details 要明確。

---

## 六、Legacy Bridge 責任

`CollabLegacyResolutionBridge` 可暫時負責：

- 從既有 table 載入 validation context
- 用既有 SQL 補足 source holomem snapshot
- 判斷 target zone occupancy
- 判斷本回合是否已 COLLAB
- 判斷 stage action lock
- 判斷 deck top availability

但 bridge 不應：

- 幫 validator 做規則判斷後直接拋錯
- 做 state mutation
- 寫 action log

---

## 七、完成標準

本 contract 落地後，應能回答：

1. COLLAB 的合法性是否集中在 `CollabActionValidator`？
2. validator 是否只讀 context，不直接查 DB 或更新 DB？
3. turn / phase / source / occupancy / once-per-turn / lock gate 是否有 focused tests？
4. `MatchActionService.moveStageHolomem(... targetZone=COLLAB)` 是否不再混寫主要 validation？
