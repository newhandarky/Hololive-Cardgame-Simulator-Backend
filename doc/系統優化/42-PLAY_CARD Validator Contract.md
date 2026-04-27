# PLAY_CARD Validator Contract

更新日期：2026-04-27
定位：`PLAY_CARD` pilot validator 契約
用途：定義 `PlayCardActionValidator` 的資料輸入、檢查順序、錯誤語意與不可承擔的責任。

---

## 一、Validator 目標

`PlayCardActionValidator` 只回答一件事：

- 這個 `PlayCardAction` 在目前 state 下能不能執行

它不應負責：

- 更新 `match_cards`
- 寫入 `match_holomems`
- 寫入 `match_holomem_stack_cards`
- 寫 action log
- 建立 Gift pending decision
- dispatch trigger
- publish snapshot
- 處理 attack cost payment

---

## 二、輸入資料

Validator 輸入應拆成：

1. `PlayCardAction`
2. `PlayCardValidationContext`

`PlayCardValidationContext` 應由 bridge / loader 負責載入，不由 validator 自己查 DB。

第一版 context 至少包含：

- match 狀態
- lobby 狀態
- current phase
- current turn player
- current turn number
- actor player 是否存在
- actor mulligan 是否完成
- opening center 是否已放置
- 是否存在 blocking pending interaction
- source card snapshot
- source zone
- source member metadata
- target zone occupancy
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
- phase 是否允許 `PLAY_CARD`
- 是否有 blocking pending interaction

第一版允許 phase：

- `RESET`
- `MAIN`

### 3. Action input gate

檢查：

- `cardInstanceId` 是否為正數
- `targetZone` 是否為 `CENTER` 或 `BACK`
- action source / idempotency metadata 是否存在

### 4. Source card gate

檢查：

- source card instance 是否存在
- source 是否屬於 actor
- source zone 是否為 `HAND`
- source card 是否存在於 `member_cards`

### 5. Opening setup gate

RESET phase 檢查：

- actor 是否已完成 mulligan
- 尚未放置 opening center 時：
  - source level 必須是 `DEBUT`
  - target zone 必須是 `CENTER`
- 已放置 opening center 時：
  - source level 必須是 `DEBUT` 或 `SPOT`
  - target zone 必須是 `BACK`

### 6. Main phase placement gate

MAIN phase 檢查：

- source level 必須是 `DEBUT` 或 `SPOT`
- target zone 必須是 `BACK`
- `FIRST` / `SECOND` / `BUZZ` 應拒絕並提示改用 BLOOM

### 7. Capacity / lock gate

檢查：

- `BACK` 最多 5 張
- 是否有 `ACTION_LOCK` 阻擋放置

若既有資料沒有 `PLAY_CARD` 專用 lock，第一版 bridge 可先支援：

- `PLAY_CARD`
- `PLAY_TO_STAGE`
- `STAGE`

是否要讓其他 movement lock 影響 PLAY_CARD，需要另做產品判斷；本 pilot 不自行擴規則。

---

## 四、輸出結果

Validator 應回傳：

- `PlayCardValidationResult.allowed(...)`
- `PlayCardValidationResult.rejected(...)`

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
- source card is not MEMBER
- target zone not allowed
- level not allowed
- opening setup order invalid
- BACK full
- action locked

既有 `PLAY_TO_STAGE_LEVEL_NOT_ALLOWED` 應保留在 level rejection path。

---

## 六、Legacy Bridge 責任

`PlayCardLegacyResolutionBridge` 可暫時負責：

- 從既有 table 載入 validation context
- 用既有 SQL 補足 source card snapshot
- 用既有 SQL 補足 member metadata
- 判斷 opening setup state
- 判斷 target zone occupancy
- 判斷 pending interaction
- 判斷 action lock
- 判斷 duplicate action

但 bridge 不應：

- 幫 validator 做規則判斷後直接拋錯
- 做 state mutation
- 寫 action log
- 建立 Gift pending decision

---

## 七、完成標準

本 contract 落地後，應能回答：

1. PLAY_CARD 的合法性是否集中在 `PlayCardActionValidator`？
2. validator 是否只讀 context，不直接查 DB 或更新 DB？
3. RESET / MAIN / level / target zone / capacity gate 是否有 focused tests？
4. `MatchActionService.playToStage(...)` 是否不再混寫主要 validation？
