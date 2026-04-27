# ATTACH_CHEER Resolution Contract

更新日期：2026-04-27
定位：`ATTACH_CHEER` pilot resolver 契約
用途：定義 ATTACH_CHEER 合法後的 state mutation 邊界、輸出結果與 legacy bridge 可接受範圍。

---

## 一、Resolver 目標

`AttachCheerActionResolver` 只回答一件事：

- 合法的 `AttachCheerAction` 應如何修改 state

它應負責：

- source Cheer card zone `HAND/CHEER_DECK -> STAGE`
- source Cheer card face-up
- insert `match_holomem_cheers`
- 回傳 resolution result

它不應負責：

- action log
- response payload
- WebSocket snapshot
- attack cost payment
- card effect trigger
- pending interaction

---

## 二、輸入

Resolver 輸入：

1. `AttachCheerAction`
2. `AttachCheerValidationContext`

前提：

- action 已通過 validator
- context 代表同一個 transaction 內的可用 state

若 resolver 被單獨測試，可用 fake context 驗證 mutation 語意；若接 DB，應由 application 控制 transaction。

---

## 三、必做 mutation

### 1. Source Cheer card movement

必須將 source Cheer card 更新為：

- `zone = STAGE`
- `order_index = NULL`
- `is_face_down = FALSE`
- updated timestamp

更新條件至少應包含：

- `id`
- `match_id`
- `owner_user_id`
- `zone IN ('HAND','CHEER_DECK')`

若 update count 不是 1，resolver 應拋出狀態衝突錯誤。

### 2. Attachment row

必須新增 `match_holomem_cheers` row：

- `match_holomem_id`
- `match_card_id`
- `cheer_card_id`
- `is_face_down = FALSE`

其中：

- `match_holomem_id` 來自 validation context 的 target holomem snapshot
- `match_card_id` 來自 action 的 `cheerCardInstanceId`
- `cheer_card_id` 來自 source card snapshot

---

## 四、輸出

Resolver 應回傳 `AttachCheerResolutionResult`。

至少包含：

- match entity 或 match id
- actor user id
- turn number
- cheer card instance id
- cheer card id
- source from zone
- target holomem id
- target holomem card instance id
- inserted attachment id 或可重建 attachment 的 key

後續 action log / event 需要的資訊應由 result 提供，不應回頭依賴 request DTO。

---

## 五、Transaction 邊界

第一版要求：

- `MatchActionService.attachCheer(...)` 作為 public API 入口時，整段流程在同一 transaction 內
- `AttachCheerApplicationService.resolveState(...)` 直接被測試呼叫時，也應能取得必要 transaction

因此 application/service method 可標記 `@Transactional`。在外層已有 transaction 時加入外層 transaction；沒有外層時自行開 transaction。

---

## 六、不應做的事

Resolver 不應：

1. 產生 `match_actions`
2. 建立 `match_pending_decisions`
3. 呼叫 WebSocket publish
4. 決定 trigger handler 順序
5. 直接拋出主要規則錯誤
6. 處理 attack art cost

若 DB update count 不符合預期，resolver 可拋出狀態衝突錯誤；這屬於 mutation 安全檢查，不是規則 validation。

---

## 七、完成標準

本 contract 落地後，應能回答：

1. ATTACH_CHEER 的 DB mutation 是否集中在 `AttachCheerActionResolver`？
2. resolver 是否不再產生 pending interaction / action log？
3. `HAND/CHEER_DECK -> STAGE` 與 `match_holomem_cheers` insert 是否有 integration test？
4. response / event 需要的結果是否由 `AttachCheerResolutionResult` 承載？
