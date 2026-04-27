# ATTACH_CHEER 第四條 Pilot 啟動規劃

更新日期：2026-04-27
定位：`COLLAB` 之後的第四條 pilot use case 啟動文件
用途：說明為什麼第四條 pilot 優先選 `ATTACH_CHEER`，以及這條線要驗證什麼、先做什麼、不做什麼。

---

## 一、為什麼第四條 pilot 選 `ATTACH_CHEER`

`END_TURN` 已驗證 lifecycle action 模板。
`BLOOM` 已驗證 card action 模板。
`COLLAB` 已驗證 stage action + effect/gift follow-up 模板。

第四條不應直接進 `ATTACK`，因為 attack core 會一次碰到：

- cheer cost selection / payment
- damage / down / life loss
- gift trigger chain
- replacement / prevention
- multi-step pending interaction
- attacker / defender 狀態更新

`ATTACH_CHEER` 的範圍比 `ATTACK` 小，但可以補上目前缺少的資源操作型 action 範例：

1. source 是 resource card，不是 Holomem。
2. mutation 同時跨 `match_cards` 與 `match_holomem_cheers`。
3. 來源 zone 允許 `HAND` / `CHEER_DECK`。
4. target 是場上我方 Holomem。
5. 可以先不引入 effect preview / trigger confirm，適合作為 resource operation pilot。

一句話講：

- `END_TURN` 驗證 lifecycle
- `BLOOM` 驗證 card action
- `COLLAB` 驗證 stage action + follow-up
- `ATTACH_CHEER` 應驗證 resource attachment action

---

## 二、目前 `ATTACH_CHEER` 的問題

目前 `ATTACH_CHEER` 實作在：

- `MatchActionService.attachCheer(...)`

這個方法目前同時承擔：

- request parsing
- action context loading
- phase / turn / pending gate
- target Holomem lookup
- source card lookup
- source zone validation
- cheer card type validation
- `match_cards` zone mutation
- `match_holomem_cheers` insert
- match phase touch
- action payload 組裝
- action log

問題不是 `ATTACH_CHEER` 不能跑，而是：

- validator / resolver 責任混在 public method
- resource attachment mutation 沒有獨立 contract
- 後續 `ATTACK` 的 cheer cost payment 容易被誤混進同一段邏輯

---

## 三、這條 pilot 驗證什麼，不驗證什麼

### 驗證範圍

`ATTACH_CHEER` pilot 應驗證：

1. resource attachment action contract
2. source cheer card legality：
   - source card 存在
   - source 屬於 actor
   - source zone 是 `HAND` 或 `CHEER_DECK`
   - source card type 是 Cheer
3. target Holomem legality：
   - target 存在
   - target 屬於 actor
   - target 是場上 Holomem
4. resolver 如何處理：
   - cheer card zone -> `STAGE`
   - clear order / face-down state
   - insert `match_holomem_cheers`
5. legacy `MatchActionService.attachCheer(...)` 是否能退化成 adapter
6. focused tests 是否保護 direct application path 與 legacy API path

### 不驗證範圍

這條 pilot 暫時不處理：

1. `SEND_CHEER` pending decision resolution
2. attack art cheer cost payment
3. card effect 自動附加 Cheer
4. cheer 從 archive / holopower / life 等其他來源附加
5. 一次附加多張 Cheer
6. `ATTACK`

---

## 四、建議的 ATTACH_CHEER 標準流程

沿用既有標準流程，但第一版可不建立 effect follow-up：

1. Load Context
2. Build Action
3. Validate
4. Resolve State
5. Emit Events
6. Dispatch Triggers
7. Persist & Log
8. Publish Snapshot / Response

### 1. Load Context

至少包含：

- match / actor
- turn / phase
- pending interaction 狀態
- source cheer card snapshot
- source zone
- source card type
- target holomem snapshot
- stage action lock
- duplicate action / idempotency metadata

### 2. Build Action

建議第一版 action 形狀：

- `actionType = ATTACH_CHEER`
- `matchId`
- `actorUserId`
- `cheerCardInstanceId`
- `targetHolomemCardInstanceId`
- `requestedTurnNumber`
- `idempotencyKey`
- `traceId`

注意：

- `AttachCheerActionRequest` 是 legacy input。
- 新 contract 應把它轉成 `AttachCheerAction`，resolver 不應持續讀 DTO。

### 3. Validate

至少要回答：

- 是否 actor turn
- phase 是否允許
- 是否有 blocking pending interaction
- source cheer card 是否存在且屬於 actor
- source zone 是否為 `HAND` 或 `CHEER_DECK`
- source card 是否為 Cheer
- target Holomem 是否存在且屬於 actor
- stage/action lock 是否阻擋附加 Cheer

第一版允許 phase：

- `MAIN`

### 4. Resolve State

resolver 應集中處理：

- source cheer card `HAND/CHEER_DECK -> STAGE`
- `order_index = NULL`
- `is_face_down = FALSE`
- insert `match_holomem_cheers`
- 回傳 attached cheer / target holomem 資訊

resolver 不應直接處理：

- action log
- response payload
- WebSocket snapshot
- attack cost payment
- card effect trigger

### 5. Emit Events

第一版至少可定義：

- `ATTACH_CHEER_REQUEST_ACCEPTED`
- `ATTACH_CHEER_RESOLVED`

若第一版沒有 effect follow-up，event / trigger path 可以保持 thin/no-op，但仍固定 ordering 與責任邊界。

---

## 五、和前三條 pilot 的差異

`ATTACH_CHEER` 不只是 BLOOM / COLLAB 的複製，差異包括：

1. source 是 Cheer resource card。
2. target 是 Holomem，但 mutation 不是替換 Holomem top card 或 zone movement。
3. state mutation 需要同時更新 `match_cards` 與 attachment join table。
4. 第一版沒有必然的 effect preview / confirm follow-up。
5. 它能為後續 attack cost / resource operation 抽出共用語彙，但不應在本 pilot 直接重寫 attack cost。

---

## 六、不應直接做成什麼

不要一開始就做：

1. 把 `SEND_CHEER` resolve decision 一起搬走。
2. 把 attack art cost payment 一起搬走。
3. 把所有 card effect attach cheer helper 一起統一。
4. 抽全域 resource movement framework。
5. 修改 Cheer 來源規則。
6. 直接進 `ATTACK`。

---

## 七、建議執行切法

### 第一刀：contract 包

- `AttachCheerAction`
- `AttachCheerValidationContext`
- `AttachCheerValidationResult`
- `AttachCheerResolutionResult`
- `AttachCheerActionValidator`
- focused validator tests

### 第二刀：resolver / application

- `AttachCheerLegacyResolutionBridge`
- `AttachCheerActionResolver`
- `AttachCheerApplicationService`
- direct application integration test

### 第三刀：adapter

- `MatchActionService.attachCheer(...)` 改成 adapter
- 保留 legacy action log / response compatibility
- legacy API smoke test 不退步

### 第四刀：event / trigger / acceptance

- `AttachCheerEventFactory`
- `AttachCheerTriggerDispatcher`
- thin handler contract
- `ATTACH_CHEER Pilot Acceptance`
- acceptance review
