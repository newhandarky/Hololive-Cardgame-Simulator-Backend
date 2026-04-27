# COLLAB Action Contract

更新日期：2026-04-27
定位：`COLLAB` pilot action 契約
用途：定義 `COLLAB` 在橋接式新架構中的輸入欄位、建構責任、防重語意與 legacy adapter 邊界。

---

## 一、適用範圍

本文件只適用於第三條 pilot use case：

- `COLLAB`

不直接涵蓋：

- 一般 `MOVE_STAGE_HOLOMEM`
- `PLAY_CARD`
- `ATTACH_CHEER`
- `BLOOM`
- `ATTACK`
- `RESOLVE_DECISION`

`COLLAB` 的目標是驗證 stage action 模板，而不是一次抽出所有 stage movement。

---

## 二、Action 目標

`CollabAction` 代表：

- 玩家嘗試將自己場上 BACK 的 Holomem 移到 COLLAB zone，並執行 COLLAB 伴隨的 holopower 與效果 follow-up

它代表的是意圖，不是結果。它不保證：

- 一定合法
- 一定能移動到 COLLAB
- 一定有牌庫頂可送入 holopower
- 一定會產生 collab effect
- 一定會產生 gift trigger
- 一定需要 confirm interaction

---

## 三、Action 型別

建議型別：

- `CollabAction`

第一版歸屬建議：

- `com.hololive.cardgame.service.CollabAction`

後續若 package 重整，再搬到 action 專屬 package；第一版不為了包名重整擴大 diff。

---

## 四、必要欄位

`CollabAction` 至少應包含以下欄位。

### 1. `actionType`

- 固定值：`COLLAB`

用途：

- routing
- action log 對齊
- validator / resolver / telemetry 對齊

### 2. `matchId`

- 型別：`Long`

指定作用中的對戰。

### 3. `actorUserId`

- 型別：`Long`

指定執行 COLLAB 的玩家。

### 4. `sourceCardInstanceId`

- 型別：`Long`

指定要從 BACK 移到 COLLAB 的 Holomem top card instance。

要求：

- 必須為正數
- validator 應確認它屬於 actor
- validator 應確認它目前在 stage 的 `BACK`

### 5. `targetZone`

- 型別：`String`
- 固定值：`COLLAB`

第一版保留欄位是為了橋接 legacy `MoveStageHolomemActionRequest.targetZone`。

要求：

- adapter 建構 action 時即固定為 `COLLAB`
- validator 應拒絕非 `COLLAB` target zone

### 6. `requestedTurnNumber`

- 型別：`int`

宣告此 action 以哪個 turn number 為基礎。

要求：

- adapter 建構 action 時即填入
- validator 應拒絕 stale turn action

### 7. `idempotencyKey`

- 型別：`String`

用途：

- 避免前端重送或連點造成重複 COLLAB

第一版可先使用 deterministic fallback：

- `COLLAB:{matchId}:{actorUserId}:{requestedTurnNumber}:{sourceCardInstanceId}`

後續若 controller/API 開始傳入外部 key，application 層再優先採用外部 key。

### 8. `traceId`

- 型別：`String`

用途：

- 串接 log / telemetry / error report

第一版若外部沒有提供，可由 adapter 生成。

---

## 五、Action 建構責任

`CollabAction` 應由舊入口 adapter 或 application facade 建構。

目前允許：

- `MatchActionService.moveStageHolomem(...)` 在 `targetZone = COLLAB` 時建構 `CollabAction`
- 測試直接建構 `CollabAction` 驗證 validator / resolver

禁止：

- validator 回頭補必要欄位
- resolver 從 `MoveStageHolomemActionRequest` 讀規則資訊
- effect handler 修改 action 基本欄位

---

## 六、Legacy Adapter 邊界

第一版 `MatchActionService.moveStageHolomem(...)` 可以保留：

- request DTO 解析
- 判斷 target zone 是否為 `COLLAB`
- 非 COLLAB movement 繼續走 legacy branch
- COLLAB branch 轉成 `CollabAction`
- 呼叫 `CollabApplicationService`
- legacy action log payload 組裝
- response / snapshot 相容 glue

不應再主要承擔：

- COLLAB-specific validation
- BACK -> COLLAB mutation
- top deck -> holopower mutation
- collab / gift effect preview
- confirm pending interaction 建立
- collab event 建立
- collab trigger dispatch 分類

---

## 七、防重與重送語意

第一版最低要求：

1. `requestedTurnNumber` 不符合目前 match turn 時，應視為 stale action。
2. 同一玩家同一回合已經執行過 `COLLAB` 時，應拒絕。
3. `idempotencyKey` 必須存在於 action 物件中，即使 persistence 層第一版尚未完整儲存 action key。

後續補強方向：

- 在 `match_actions` payload 或專用欄位中保存 idempotency key
- 相同 key 已成功時回傳可重建結果
- 相同 key 處理中時回傳 duplicate/in-progress domain error

---

## 八、完成標準

本 contract 落地後，應能回答：

1. COLLAB action 由哪裡建構？
2. source card instance 與 target zone 是否在 validator 前就存在？
3. stale / duplicate / once-per-turn 的檢查責任在哪裡？
4. legacy `moveStageHolomem(... targetZone=COLLAB)` 是否已退化成 adapter？
