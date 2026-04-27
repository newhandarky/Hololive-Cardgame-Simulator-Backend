# ATTACH_CHEER Action Contract

更新日期：2026-04-27
定位：`ATTACH_CHEER` pilot action 契約
用途：定義 `ATTACH_CHEER` 在橋接式新架構中的輸入欄位、建構責任、防重語意與 legacy adapter 邊界。

---

## 一、適用範圍

本文件只適用於第四條 pilot use case：

- `ATTACH_CHEER`

不直接涵蓋：

- `SEND_CHEER`
- `BLOOM`
- `COLLAB`
- `PLAY_CARD`
- `ATTACK`
- `RESOLVE_DECISION`
- card effect 自動附加 Cheer

`ATTACH_CHEER` 的目標是驗證 resource attachment action，而不是一次抽出所有 Cheer 移動規則。

---

## 二、Action 目標

`AttachCheerAction` 代表：

- 玩家嘗試將自己持有的一張 Cheer card 附加到自己場上一個 Holomem

它代表的是意圖，不是結果。它不保證：

- 一定合法
- source 一定是 Cheer
- source 一定在允許 zone
- target Holomem 一定存在
- 一定能寫入 attachment row

---

## 三、Action 型別

建議型別：

- `AttachCheerAction`

第一版歸屬建議：

- `com.hololive.cardgame.service.AttachCheerAction`

後續若 package 重整，再搬到 action 專屬 package；第一版不為了包名重整擴大 diff。

---

## 四、必要欄位

`AttachCheerAction` 至少應包含以下欄位。

### 1. `actionType`

- 固定值：`ATTACH_CHEER`

用途：

- routing
- action log 對齊
- validator / resolver / telemetry 對齊

### 2. `matchId`

- 型別：`Long`

指定作用中的對戰。

### 3. `actorUserId`

- 型別：`Long`

指定執行附加 Cheer 的玩家。

### 4. `cheerCardInstanceId`

- 型別：`Long`

指定要被附加的 Cheer card instance。

要求：

- 必須為正數
- validator 應確認它屬於 actor
- validator 應確認它目前在 `HAND` 或 `CHEER_DECK`
- validator 應確認它是 Cheer card

### 5. `targetHolomemCardInstanceId`

- 型別：`Long`

指定要被附加 Cheer 的 Holomem top card instance。

要求：

- 必須為正數
- validator 應確認它屬於 actor
- validator 應確認它對應到一個場上的 `match_holomems`

### 6. `requestedTurnNumber`

- 型別：`int`

宣告此 action 以哪個 turn number 為基礎。

要求：

- adapter 建構 action 時即填入
- validator 應拒絕 stale turn action

### 7. `idempotencyKey`

- 型別：`String`

用途：

- 避免前端重送或連點造成重複附加 Cheer

第一版可先使用 deterministic fallback：

- `ATTACH_CHEER:{matchId}:{actorUserId}:{requestedTurnNumber}:{cheerCardInstanceId}:{targetHolomemCardInstanceId}`

### 8. `traceId`

- 型別：`String`

用途：

- 串接 log / telemetry / error report

第一版若外部沒有提供，可由 adapter 生成。

---

## 五、Action 建構責任

`AttachCheerAction` 應由舊入口 adapter 或 application facade 建構。

目前允許：

- `MatchActionService.attachCheer(...)` 從 `AttachCheerActionRequest` 建構 `AttachCheerAction`
- 測試直接建構 `AttachCheerAction` 驗證 validator / resolver

禁止：

- validator 回頭補必要欄位
- resolver 從 `AttachCheerActionRequest` 讀規則資訊
- trigger handler 修改 action 基本欄位

---

## 六、Legacy Adapter 邊界

第一版 `MatchActionService.attachCheer(...)` 可以保留：

- request DTO 解析
- action 建構
- 呼叫 `AttachCheerApplicationService`
- legacy action log payload 組裝
- response / snapshot 相容 glue

不應再主要承擔：

- source cheer card validation
- target Holomem validation
- `match_cards` zone mutation
- `match_holomem_cheers` insert
- attach cheer event 建立
- attach cheer trigger dispatch 分類

---

## 七、防重與重送語意

第一版最低要求：

1. `requestedTurnNumber` 不符合目前 match turn 時，應視為 stale action。
2. 同一張 `cheerCardInstanceId` 若已不在 `HAND` / `CHEER_DECK`，應拒絕或由 resolver update count 失敗轉成狀態衝突。
3. `idempotencyKey` 必須存在於 action 物件中，即使 persistence 層第一版尚未完整儲存 action key。

後續補強方向：

- 在 `match_actions` payload 或專用欄位中保存 idempotency key
- 相同 key 已成功時回傳可重建結果
- 相同 key 處理中時回傳 duplicate/in-progress domain error

---

## 八、完成標準

本 contract 落地後，應能回答：

1. ATTACH_CHEER action 由哪裡建構？
2. source cheer card 與 target Holomem 的必要欄位是否在 validator 前就存在？
3. stale / duplicate / source zone 的檢查責任在哪裡？
4. legacy `attachCheer(...)` 是否已退化成 adapter？
