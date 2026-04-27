# ATTACH_CHEER Validator Contract

更新日期：2026-04-27
定位：`ATTACH_CHEER` pilot validator 契約
用途：定義 `AttachCheerActionValidator` 的資料輸入、檢查順序、錯誤語意與不可承擔的責任。

---

## 一、Validator 目標

`AttachCheerActionValidator` 只回答一件事：

- 這個 `AttachCheerAction` 在目前 state 下能不能執行

它不應負責：

- 更新 `match_cards`
- 寫入 `match_holomem_cheers`
- 寫 action log
- dispatch trigger
- publish snapshot
- 處理 attack cost payment

---

## 二、輸入資料

Validator 輸入應拆成：

1. `AttachCheerAction`
2. `AttachCheerValidationContext`

`AttachCheerValidationContext` 應由 bridge / loader 負責載入，不由 validator 自己查 DB。

第一版 context 至少包含：

- match 狀態
- lobby 狀態
- current phase
- current turn player
- current turn number
- actor player 是否存在
- 是否存在 blocking pending interaction
- source cheer card snapshot
- source zone
- source card type / cheer card existence
- target holomem snapshot
- stage/action lock
- duplicate action metadata

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
- phase 是否允許 `ATTACH_CHEER`
- 是否有 blocking pending interaction

第一版允許 phase：

- `MAIN`

### 3. Action input gate

檢查：

- `cheerCardInstanceId` 是否為正數
- `targetHolomemCardInstanceId` 是否為正數
- action source / idempotency metadata 是否存在

### 4. Source cheer gate

檢查：

- source card instance 是否存在
- source 是否屬於 actor
- source zone 是否為 `HAND` 或 `CHEER_DECK`
- source card 是否存在於 `cheer_cards`

### 5. Target holomem gate

檢查：

- target holomem 是否存在
- target holomem 是否屬於 actor
- target holomem 是否是目前場上有效 Holomem

第一版不額外限制 target zone；沿用既有 `attachCheer(...)` 行為，只要 target 對應到 actor 的 `match_holomems` 即可。

### 6. Action lock gate

檢查：

- 是否有 `ACTION_LOCK` 阻擋附加 Cheer

若既有資料沒有 `ATTACH_CHEER` 專用 lock，第一版 bridge 可先支援：

- `ATTACH_CHEER`
- `ATTACH`
- `CHEER`

是否要讓 `MOVE_STAGE` lock 影響附加 Cheer，需要另做產品判斷；本 pilot 不自行擴規則。

---

## 四、輸出結果

Validator 應回傳：

- `AttachCheerValidationResult.allowed(...)`
- `AttachCheerValidationResult.rejected(...)`

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
- source card not found
- source card invalid zone
- source card is not Cheer
- target holomem not found
- action locked

若既有 `GameErrorCode` 尚未有完全對應值，第一版可先沿用最接近的 code，但 message/details 要明確。

---

## 六、Legacy Bridge 責任

`AttachCheerLegacyResolutionBridge` 可暫時負責：

- 從既有 table 載入 validation context
- 用既有 SQL 補足 source card snapshot
- 用既有 SQL 補足 target holomem snapshot
- 判斷 pending interaction
- 判斷 action lock
- 判斷 duplicate action

但 bridge 不應：

- 幫 validator 做規則判斷後直接拋錯
- 做 state mutation
- 寫 action log

---

## 七、完成標準

本 contract 落地後，應能回答：

1. ATTACH_CHEER 的合法性是否集中在 `AttachCheerActionValidator`？
2. validator 是否只讀 context，不直接查 DB 或更新 DB？
3. turn / phase / source / target / lock gate 是否有 focused tests？
4. `MatchActionService.attachCheer(...)` 是否不再混寫主要 validation？
