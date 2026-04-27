# BLOOM Validator Contract

更新日期：2026-04-27
定位：`BLOOM` pilot validator 契約
用途：定義 `BloomActionValidator` 的資料輸入、檢查順序、錯誤語意與不可承擔的責任。

---

## 一、Validator 目標

`BloomActionValidator` 只回答一件事：

- 這個 `BloomAction` 在目前 state 下能不能執行

它不應負責：

- 移動卡片
- 更新 `match_holomems`
- 建立 stack
- 建立 pending interaction
- 寫 action log
- dispatch trigger

---

## 二、輸入資料

Validator 輸入應拆成：

1. `BloomAction`
2. `BloomValidationContext`

`BloomValidationContext` 應由 bridge / loader 負責載入，不由 validator 自己查 DB。

第一版 context 至少包含：

- match 狀態
- lobby 狀態
- current phase
- current turn player
- current turn number
- actor player 是否存在
- 是否存在 blocking pending interaction
- source card snapshot
- target holomem snapshot
- target 是否已有 stage action lock
- extra bloom allowance 狀態
- 是否存在 level override

---

## 三、檢查順序

建議固定順序如下。

### 1. Match gate

檢查：

- match 是否存在
- match status 是否 active
- lobby status 是否 `STARTED` 或 `ACTIVE`
- actor 是否在 match 中

失敗時應回 domain error，不應落入 DB mutation 階段。

### 2. Turn / phase gate

檢查：

- `requestedTurnNumber` 是否等於目前 turn
- 是否輪到 actor
- phase 是否允許 BLOOM
- 是否有 blocking pending interaction

第一版允許 phase：

- `MAIN`

### 3. Source card gate

檢查：

- source card 是否存在
- source card 是否屬於 actor
- source card 是否在 `HAND`
- source card 是否為 MEMBER
- source card 是否不是不可 BLOOM 的類型
- source card hp 是否大於 0

### 4. Target gate

檢查：

- target holomem 是否存在
- target 是否屬於 actor
- target 是否在 stage
- target top card 是否存在
- target 是否不是不可 BLOOM 的類型
- target 本回合是否剛進場
- target 本回合是否已 BLOOM
- stage action lock 是否阻擋本次 BLOOM

### 5. Card compatibility gate

檢查：

- source 與 target 是否同名
- level 遞進是否成立
- 若有 level override，是否明確允許跳級

一般 level 遞進：

- `DEBUT -> FIRST`
- `FIRST -> SECOND`

特殊規則：

- `BUZZ` / `SPOT` / 其他不可 BLOOM 型態應由 validator 明確拒絕，除非未來 contract 更新。

### 6. Damage carry-over gate

檢查：

- target 既有 damage 是否小於 source card hp

若 source hp 無法承接既有 damage，應拒絕，不進入 mutation。

---

## 四、輸出結果

Validator 應回傳：

- `BloomValidationResult.allowed(...)`
- `BloomValidationResult.rejected(...)`

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
- source not in hand
- target invalid
- same name mismatch
- level transition invalid
- bloom already used
- damage exceeds new hp

若既有 `GameErrorCode` 尚未有完全對應值，第一版可先沿用最接近的 code，但 message/details 要明確。

---

## 六、Legacy Bridge 責任

`BloomLegacyResolutionBridge` 可暫時負責：

- 從既有 table 載入 validation context
- 用既有 helper / SQL 補足 source / target snapshot
- 判斷目前 legacy effect state 中的 extra bloom allowance
- 判斷 level override

但 bridge 不應：

- 幫 validator 做規則判斷後直接拋錯
- 做 state mutation
- 寫 action log

---

## 七、完成標準

本 contract 落地後，應能回答：

1. BLOOM 的合法性是否集中在 `BloomActionValidator`？
2. validator 是否只讀 context，不直接查 DB 或更新 DB？
3. source / target / phase / once-per-turn / damage gate 是否有 focused tests？
4. `MatchActionService.bloom(...)` 是否不再混寫主要 validation？
