# PLAY_CARD Action Contract

更新日期：2026-04-27
定位：`PLAY_CARD` pilot action 契約
用途：定義 `PLAY_CARD` 在橋接式新架構中的輸入欄位、建構責任、防重語意與 legacy adapter 邊界。

---

## 一、適用範圍

本文件只適用於第五條 pilot use case：

- `PLAY_CARD`

第一版實作範圍限定為：

- legacy `MatchActionService.playToStage(...)`
- hand MEMBER card 放置到 stage

不直接涵蓋：

- SUPPORT 使用
- BLOOM
- COLLAB
- ATTACH_CHEER
- ATTACK
- RESOLVE_DECISION
- card effect 自動放置 Holomem

---

## 二、Action 目標

`PlayCardAction` 代表：

- 玩家嘗試從手牌放置一張 MEMBER card 到 stage

它代表的是意圖，不是結果。它不保證：

- 一定合法
- source 一定在手牌
- source 一定是 MEMBER
- target zone 一定可放置
- 一定會建立 Holomem
- 一定會產生 Gift follow-up

---

## 三、Action 型別

建議型別：

- `PlayCardAction`

第一版歸屬建議：

- `com.hololive.cardgame.service.PlayCardAction`

後續若 package 重整，再搬到 action 專屬 package；第一版不為了包名重整擴大 diff。

---

## 四、必要欄位

`PlayCardAction` 至少應包含以下欄位。

### 1. `actionType`

- 固定值：`PLAY_CARD`

用途：

- routing
- validator / resolver / telemetry 對齊
- event contract 對齊

注意：

- legacy action log 第一版仍可寫 `OPENING_SET_CENTER` / `OPENING_SET_BACK` / `PLAY_TO_STAGE`。
- 新 action contract 不應被 legacy action log type 綁死。

### 2. `matchId`

- 型別：`Long`

指定作用中的對戰。

### 3. `actorUserId`

- 型別：`Long`

指定執行放置的玩家。

### 4. `cardInstanceId`

- 型別：`Long`

指定要從手牌放置的 card instance。

要求：

- 必須為正數
- validator 應確認它屬於 actor
- validator 應確認它目前在 `HAND`
- validator 應確認它是 MEMBER card

### 5. `targetZone`

- 型別：`String`

指定目標 stage zone。

第一版允許：

- `CENTER`
- `BACK`

但合法性依 phase 與 opening setup state 決定。

### 6. `requestedTurnNumber`

- 型別：`int`

宣告此 action 以哪個 turn number 為基礎。

要求：

- adapter 建構 action 時即填入
- validator 應拒絕 stale turn action

### 7. `openingReset`

- 型別：`boolean`

用途：

- 區分 RESET 開場設置與 MAIN 正常放置
- 影響 `is_face_down`
- 影響 follow-up 是否 deferred

### 8. `idempotencyKey`

- 型別：`String`

用途：

- 避免前端重送或連點造成重複放置

第一版可先使用 deterministic fallback：

- `PLAY_CARD:{matchId}:{actorUserId}:{requestedTurnNumber}:{cardInstanceId}:{targetZone}`

### 9. `traceId`

- 型別：`String`

用途：

- 串接 log / telemetry / error report

第一版若外部沒有提供，可由 adapter 生成。

---

## 五、Action 建構責任

`PlayCardAction` 應由舊入口 adapter 或 application facade 建構。

目前允許：

- `MatchActionService.playToStage(...)` 從 `PlayToStageActionRequest` 建構 `PlayCardAction`
- 測試直接建構 `PlayCardAction` 驗證 validator / resolver

禁止：

- validator 回頭補必要欄位
- resolver 從 `PlayToStageActionRequest` 讀規則資訊
- trigger handler 修改 action 基本欄位

---

## 六、Legacy Adapter 邊界

第一版 `MatchActionService.playToStage(...)` 可以保留：

- request DTO 解析
- action 建構
- 呼叫 `PlayCardApplicationService`
- legacy action log payload 組裝
- response / snapshot 相容 glue

不應再主要承擔：

- source card validation
- target zone validation
- opening setup rule validation
- `match_cards` zone mutation
- `match_holomems` insert
- `match_holomem_stack_cards` insert
- enter hook / Gift follow-up 建立
- play card event 建立
- play card trigger dispatch 分類

---

## 七、防重與重送語意

第一版最低要求：

1. `requestedTurnNumber` 不符合目前 match turn 時，應視為 stale action。
2. 同一張 `cardInstanceId` 若已不在 `HAND`，應拒絕或由 resolver update count 失敗轉成狀態衝突。
3. `idempotencyKey` 必須存在於 action 物件中，即使 persistence 層第一版尚未完整儲存 action key。

後續補強方向：

- 在 `match_actions` payload 或專用欄位中保存 idempotency key
- 相同 key 已成功時回傳可重建結果
- 相同 key 處理中時回傳 duplicate/in-progress domain error

---

## 八、完成標準

本 contract 落地後，應能回答：

1. PLAY_CARD action 由哪裡建構？
2. source card 與 target zone 的必要欄位是否在 validator 前就存在？
3. RESET / MAIN 的分支是否由 action + context 清楚表達？
4. legacy `playToStage(...)` 是否已退化成 adapter？
