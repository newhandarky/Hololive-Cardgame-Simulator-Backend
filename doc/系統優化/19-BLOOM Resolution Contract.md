# BLOOM Resolution Contract

更新日期：2026-04-27
定位：`BLOOM` pilot resolver 契約
用途：定義 BLOOM 合法後的 state mutation 邊界、輸出結果與 legacy bridge 可接受範圍。

---

## 一、Resolver 目標

`BloomActionResolver` 只回答一件事：

- 合法的 `BloomAction` 應如何修改 state

它應負責：

- hand -> stage card movement
- append holomem stack
- update target holomem top card / level / hp / last bloom turn
- consume extra bloom allowance
- 回傳 resolution result

它不應負責：

- action log
- pending interaction
- response payload
- WebSocket snapshot
- trigger dispatch
- effect preview/confirm 的業務判斷

---

## 二、輸入

Resolver 輸入：

1. `BloomAction`
2. `BloomValidationContext`

前提：

- action 已通過 validator
- context 代表同一個 transaction 內的可用 state

若 resolver 被單獨測試，可用 fake context 驗證 mutation 語意；若接 DB，應由 application 控制 transaction。

---

## 三、必做 mutation

### 1. Source card movement

必須將 source card instance：

- 從 `HAND`
- 移到 `STAGE`

並清理不適用的排序欄位。

### 2. Stack append

必須在 `match_holomem_stack` 中新增 stack 關係：

- `match_holomem_id`
- `card_instance_id`
- 正確的 stack order

新增後 stack depth 應可由 DB state 重建。

### 3. Target holomem update

必須更新 target holomem：

- `current_card_instance_id`
- `current_card_id`
- normalized bloom level
- max hp
- `last_bloom_turn`
- updated timestamp

既有 damage 不應被清除。BLOOM 後的 remaining hp 應由：

- new max hp
- existing damage

推導，而不是另存一份不一致狀態。

### 4. Extra allowance consume

若本次 BLOOM 使用 extra bloom allowance：

- resolver 應消耗該 allowance
- 消耗行為應在同一 transaction 內完成

若沒有使用 allowance，不應修改無關 effect state。

---

## 四、輸出

Resolver 應回傳 `BloomResolutionResult`。

至少包含：

- match id
- actor user id
- source card instance id
- target holomem id
- bloom card id
- bloom level
- stack depth
- 是否使用 level override
- 是否使用 extra bloom allowance
- 是否存在 bloom effect preview summary
- 是否需要 trigger confirm follow-up

第一版可讓 preview / confirm summary 由 legacy glue 在 resolver 後補入，但 result 型別必須容納這些資訊，讓 event factory 不依賴 action log payload。

---

## 五、Transaction 邊界

第一版要求：

- `MatchActionService.bloom(...)` 作為 public API 入口時，整段流程在同一 transaction 內
- `BloomApplicationService.resolveState(...)` 直接被測試呼叫時，也應能取得必要 transaction

因此 application/service method 可標記 `@Transactional`。在外層已有 transaction 時加入外層 transaction；沒有外層時自行開 transaction。

---

## 六、不應做的事

Resolver 不應：

1. 產生 `match_actions`
2. 建立 `match_pending_decisions`
3. 呼叫 `MatchStateService`
4. 呼叫 WebSocket publish
5. 決定 trigger handler 順序
6. 直接拋出主要規則錯誤

若 DB update count 不符合預期，resolver 可拋出狀態衝突錯誤；這屬於 mutation 安全檢查，不是規則 validation。

---

## 七、完成標準

本 contract 落地後，應能回答：

1. BLOOM 的 DB mutation 是否集中在 `BloomActionResolver`？
2. resolver 是否不再產生 pending interaction / action log？
3. source card movement、stack append、target top card 更新是否有 integration test？
4. response / event 需要的結果是否由 `BloomResolutionResult` 承載？
