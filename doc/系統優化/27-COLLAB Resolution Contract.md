# COLLAB Resolution Contract

更新日期：2026-04-27
定位：`COLLAB` pilot resolver 契約
用途：定義 COLLAB 合法後的 state mutation 邊界、輸出結果與 legacy bridge 可接受範圍。

---

## 一、Resolver 目標

`CollabActionResolver` 只回答一件事：

- 合法的 `CollabAction` 應如何修改 state

它應負責：

- source Holomem zone `BACK -> COLLAB`
- top deck card -> holopower
- 回傳 resolution result

它不應負責：

- collab effect preview
- gift trigger preview
- pending interaction
- action log
- response payload
- WebSocket snapshot
- trigger dispatch

---

## 二、輸入

Resolver 輸入：

1. `CollabAction`
2. `CollabValidationContext`

前提：

- action 已通過 validator
- context 代表同一個 transaction 內的可用 state

若 resolver 被單獨測試，可用 fake context 驗證 mutation 語意；若接 DB，應由 application 控制 transaction。

---

## 三、必做 mutation

### 1. Source Holomem movement

必須將 source Holomem：

- 從 `BACK`
- 移到 `COLLAB`

並更新 timestamp。

更新條件至少應包含：

- `match_id`
- `owner_user_id`
- `match_card_id`
- `zone = 'BACK'`

若 update count 不是 1，resolver 應拋出狀態衝突錯誤。

### 2. Top deck to holopower

COLLAB 會將牌庫頂一張卡移到 holopower。

resolver 應集中處理：

- 找出 actor deck top card
- 將該卡 zone 更新為 `HOLOPOWER`
- 清理 deck order 或設定 holopower order
- 回傳 `holopowerCardInstanceId`

若 deck empty 行為目前不明確，第一版應維持既有 `moveTopDeckCardToHolopower(...)` 行為，不在 resolver contract 內自行改規則。

---

## 四、輸出

Resolver 應回傳 `CollabResolutionResult`。

至少包含：

- match entity 或 match id
- actor user id
- turn number
- source card instance id
- source card id
- source from zone
- target zone
- source holomem id
- holopower card instance id

後續 effect follow-up 需要的資訊應由 result 提供，不應回頭依賴 action log payload。

---

## 五、Transaction 邊界

第一版要求：

- `MatchActionService.moveStageHolomem(... targetZone=COLLAB)` 作為 public API 入口時，整段流程在同一 transaction 內
- `CollabApplicationService.resolveState(...)` 直接被測試呼叫時，也應能取得必要 transaction

因此 application/service method 可標記 `@Transactional`。在外層已有 transaction 時加入外層 transaction；沒有外層時自行開 transaction。

---

## 六、不應做的事

Resolver 不應：

1. 產生 `match_actions`
2. 建立 `match_pending_decisions`
3. preview collab effect
4. preview gift trigger
5. 呼叫 WebSocket publish
6. 決定 trigger handler 順序
7. 直接拋出主要規則錯誤

若 DB update count 不符合預期，resolver 可拋出狀態衝突錯誤；這屬於 mutation 安全檢查，不是規則 validation。

---

## 七、完成標準

本 contract 落地後，應能回答：

1. COLLAB 的 DB mutation 是否集中在 `CollabActionResolver`？
2. resolver 是否不再產生 pending interaction / action log？
3. BACK -> COLLAB movement 與 top deck -> holopower 是否有 integration test？
4. response / event 需要的結果是否由 `CollabResolutionResult` 承載？
