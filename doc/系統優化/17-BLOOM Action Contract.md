# BLOOM Action Contract

更新日期：2026-04-27
定位：`BLOOM` pilot action 契約
用途：定義 `BLOOM` 在橋接式新架構中的輸入欄位、建構責任、防重語意與 adapter 邊界。

---

## 一、適用範圍

本文件只適用於第二條 pilot use case：

- `BLOOM`

不直接涵蓋：

- `PLAY_CARD`
- `ATTACH_CHEER`
- `COLLAB`
- `ATTACK`
- `RESOLVE_DECISION`

`BLOOM` 的目標是驗證 card action 模板，而不是一次抽出所有卡片操作的共用框架。

---

## 二、Action 目標

`BloomAction` 代表：

- 玩家嘗試用手牌中的 Holomem 對場上一個既有 Holomem 進行 BLOOM

它代表的是意圖，不是結果。它不保證：

- 一定合法
- 一定會成功移動卡片
- 一定會產生 bloom effect preview
- 一定需要 confirm interaction

---

## 三、Action 型別

建議型別：

- `BloomAction`

目前第一版歸屬：

- `com.hololive.cardgame.service.BloomAction`

後續若 package 重整，再搬到 action 專屬 package；第一版不為了包名重整擴大 diff。

---

## 四、必要欄位

`BloomAction` 至少應包含以下欄位。

### 1. `actionType`

- 固定值：`BLOOM`

用途：

- routing
- action log 對齊
- validator / resolver / telemetry 對齊

### 2. `matchId`

- 型別：`Long`

指定作用中的對戰。

### 3. `actorUserId`

- 型別：`Long`

指定執行 BLOOM 的玩家。

### 4. `sourceCardInstanceId`

- 型別：`Long`

指定手牌中用來 BLOOM 的卡片 instance。

要求：

- 必須為正數
- validator 應確認它屬於 actor
- validator 應確認它目前在 `HAND`

### 5. `targetHolomemId`

- 型別：`Long`

指定場上要被 BLOOM 的 `match_holomems.id`。

要求：

- 必須為正數
- validator 應確認它屬於 actor
- validator 應確認它是可 BLOOM 的 stage Holomem

### 6. `requestedTurnNumber`

- 型別：`int`

宣告此 action 以哪個 turn number 為基礎。

要求：

- adapter 建構 action 時即填入
- validator 應拒絕 stale turn action

### 7. `idempotencyKey`

- 型別：`String`

用途：

- 避免前端重送或連點造成重複 BLOOM

第一版可先使用 deterministic fallback：

- `BLOOM:{matchId}:{actorUserId}:{requestedTurnNumber}:{sourceCardInstanceId}:{targetHolomemId}`

後續若 controller/API 開始傳入外部 key，application 層再優先採用外部 key。

### 8. `traceId`

- 型別：`String`

用途：

- 串接 log / telemetry / error report

第一版若外部沒有提供，可由 adapter 生成。

---

## 五、Action 建構責任

`BloomAction` 應由舊入口 adapter 或 application facade 建構。

目前允許：

- `MatchActionService.bloom(...)` 從 `BloomActionRequest` 建構 `BloomAction`
- 測試直接建構 `BloomAction` 驗證 validator / resolver

禁止：

- validator 回頭補必要欄位
- resolver 從 request DTO 讀規則資訊
- effect handler 修改 action 基本欄位

---

## 六、Adapter 邊界

`MatchActionService.bloom(...)` 在 pilot 第一版可保留：

- request DTO 解析
- action 建構
- 呼叫 `BloomApplicationService`
- legacy action log payload 組裝
- response / snapshot 相容 glue

不應再主要承擔：

- target validation 規則
- hand -> stage mutation
- holomem top card / stack mutation
- bloom event 建立
- trigger dispatch 分類

若 legacy glue 必須暫留，應可以清楚指出它屬於哪一個 contract 的暫時橋接點。

---

## 七、防重與重送語意

第一版最低要求：

1. `requestedTurnNumber` 不符合目前 match turn 時，應視為 stale action。
2. 同一個 target 在同一回合已 BLOOM 時，應拒絕，除非 extra bloom allowance 明確成立。
3. `idempotencyKey` 必須存在於 action 物件中，即使 persistence 層第一版尚未完整儲存 action key。

後續補強方向：

- 在 `match_actions` payload 或專用欄位中保存 idempotency key
- 相同 key 已成功時回傳可重建結果
- 相同 key 處理中時回傳 duplicate/in-progress domain error

---

## 八、完成標準

本 contract 落地後，應能回答：

1. BLOOM action 由哪裡建構？
2. source card 與 target holomem 的必要欄位是否在 validator 前就存在？
3. stale / duplicate / once-per-turn 的檢查責任在哪裡？
4. 舊入口是否已退化成 adapter，而不是繼續承擔核心規則？
