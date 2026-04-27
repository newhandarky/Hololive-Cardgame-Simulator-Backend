# PLAY_CARD 第五條 Pilot 啟動規劃

更新日期：2026-04-27
定位：`ATTACH_CHEER` 之後的第五條 pilot use case 啟動文件
用途：說明為什麼第五條 pilot 優先選 `PLAY_CARD`，以及這條線要驗證什麼、先做什麼、不做什麼。

---

## 一、為什麼第五條 pilot 選 `PLAY_CARD`

`END_TURN` 已驗證 lifecycle action 模板。
`BLOOM` 已驗證 card action + effect preview / confirm 模板。
`COLLAB` 已驗證 stage action + follow-up 模板。
`ATTACH_CHEER` 已驗證 resource attachment action 模板。

第五條仍不應直接進 `ATTACK`，因為 attack core 會一次碰到：

- attack declaration
- cheer cost validation / payment
- damage calculation
- down / life loss / victory
- gift trigger chain
- replacement / prevention
- multi-step pending interaction

`PLAY_CARD` 比 `ATTACK` 小，但比 `ATTACH_CHEER` 更能驗證「一般手牌 action」：

1. source 是手牌 member card。
2. target zone 有 `CENTER` / `BACK` 的 phase-dependent 規則。
3. mutation 需要同時更新 `match_cards`、`match_holomems`、`match_holomem_stack_cards`。
4. MAIN 階段進場會觸發 enter hook / Gift preview / pending confirm。
5. RESET 階段開場設置需要保留 face-down 與 phase lifecycle。

一句話講：

- `PLAY_CARD` 應驗證 stage placement action
- 但第一版範圍只收斂 legacy `playToStage(...)` 的 Holomem 放置，不把 support card 或 attack cost 拉進來

---

## 二、目前 `PLAY_CARD` 的問題

目前對應實作在：

- `MatchActionService.playToStage(...)`

這個方法目前同時承擔：

- request parsing
- action context loading
- pending interaction gate
- target zone validation
- source card lookup
- source zone validation
- member card / level validation
- RESET 開場設置規則
- MAIN 階段放置規則
- BACK 容量檢查
- `match_cards` zone mutation
- `match_holomems` insert
- `match_holomem_stack_cards` insert
- match phase touch
- enter hook
- Gift trigger preview
- Gift trigger confirm pending decision
- action payload 組裝
- action log

問題不是 `playToStage(...)` 不能跑，而是：

- validator / resolver / follow-up 責任全部混在 public method
- RESET 開場設置與 MAIN 放置的規則沒有獨立 contract
- enter hook / Gift follow-up 和 state mutation 沒有明確邊界
- 後續要拆 support card 或 attack core 時，會缺少一般 hand card action 範本

---

## 三、這條 pilot 驗證什麼，不驗證什麼

### 驗證範圍

`PLAY_CARD` pilot 第一版應驗證：

1. hand member placement action contract
2. source member legality：
   - source card 存在
   - source 屬於 actor
   - source zone 是 `HAND`
   - source card type 是 MEMBER
3. target zone legality：
   - RESET 開場 first placement：DEBUT -> CENTER
   - RESET 開場後續 placement：DEBUT / SPOT -> BACK
   - MAIN placement：DEBUT / SPOT -> BACK
   - BACK 最多 5 張
4. resolver 如何處理：
   - hand card -> `STAGE`
   - `order_index = NULL`
   - `is_face_down = openingReset`
   - insert `match_holomems`
   - insert `match_holomem_stack_cards`
5. MAIN 階段 follow-up：
   - enter hook summary
   - Gift trigger preview
   - Gift confirm pending decision
6. legacy `MatchActionService.playToStage(...)` 是否能退化成 adapter
7. focused tests 是否保護 direct application path 與 legacy API path

### 不驗證範圍

這條 pilot 暫時不處理：

1. support card 使用流程
2. Bloom / Collab / Attach Cheer
3. attack cost payment
4. attack declaration / damage / down / life loss
5. card effect 自動播放或特殊放置
6. 通用 hand card framework

---

## 四、建議的 PLAY_CARD 標準流程

沿用既有標準流程，但需把 follow-up 明確拆開：

1. Load Context
2. Build Action
3. Validate
4. Resolve State
5. Resolve Follow-up
6. Emit Events
7. Dispatch Triggers
8. Persist & Log
9. Publish Snapshot / Response

### 1. Load Context

至少包含：

- match / actor
- turn / phase
- pending interaction 狀態
- source card snapshot
- source zone
- source member metadata
- actor opening setup state
- target zone occupancy
- stage action lock
- duplicate action / idempotency metadata

### 2. Build Action

建議第一版 action 形狀：

- `actionType = PLAY_CARD`
- `matchId`
- `actorUserId`
- `cardInstanceId`
- `targetZone`
- `requestedTurnNumber`
- `openingReset`
- `idempotencyKey`
- `traceId`

注意：

- `PlayToStageActionRequest` 是 legacy input。
- 新 contract 應把它轉成 `PlayCardAction`，resolver 不應持續讀 DTO。
- action name 用 `PLAY_CARD`，legacy action log 第一版可繼續寫 `OPENING_SET_CENTER` / `OPENING_SET_BACK` / `PLAY_TO_STAGE` 以維持相容。

### 3. Validate

至少要回答：

- 是否 actor turn
- phase 是否允許
- 是否有 blocking pending interaction
- source card 是否存在且屬於 actor
- source zone 是否為 `HAND`
- source 是否為 MEMBER
- source level 是否允許
- target zone 是否允許
- BACK 是否已滿
- opening setup 順序是否合法
- stage/action lock 是否阻擋放置

第一版允許 phase：

- `RESET`
- `MAIN`

### 4. Resolve State

resolver 應集中處理：

- source card `HAND -> STAGE`
- `order_index = NULL`
- `is_face_down = openingReset`
- insert `match_holomems`
- insert `match_holomem_stack_cards`
- 回傳 played card / holomem / target zone / entered turn

resolver 不應直接處理：

- action log
- response payload
- WebSocket snapshot
- Gift pending interaction
- attack cost payment

### 5. Resolve Follow-up

`PlayCardEffectResolutionService` 或同等 follow-up service 應集中處理：

- RESET：不立即觸發 enter hook / Gift，回傳 deferred until live start summary
- MAIN：執行 enter hook
- MAIN：preview Gift triggered effects
- MAIN：必要時建立 Gift trigger confirm pending decision
- 回傳 follow-up summary 與 pending decision metadata

### 6. Emit Events

第一版至少可定義：

- `PLAY_CARD_REQUEST_ACCEPTED`
- `PLAY_CARD_RESOLVED`
- `PLAY_CARD_ENTER_HOOK_RESOLVED`
- `PLAY_CARD_GIFT_PREVIEW_CREATED`
- `PLAY_CARD_GIFT_CONFIRM_REQUIRED`

若 RESET 開場沒有 immediate follow-up，仍應用 event 明確標示 deferred summary。

---

## 五、和前四條 pilot 的差異

`PLAY_CARD` 不只是 BLOOM / COLLAB / ATTACH_CHEER 的複製，差異包括：

1. 同一 legacy method 同時支援 RESET 與 MAIN。
2. target zone 合法性依 phase 與 opening setup 狀態改變。
3. state mutation 需要建立新的 Holomem entity 與 stack relation。
4. MAIN placement 會接 enter hook / Gift follow-up。
5. legacy action log action type 需保留 opening setup 與 normal play 的相容語意。

---

## 六、不應直接做成什麼

不要一開始就做：

1. 把 support card 使用流程一起搬走。
2. 把 attack art cost payment 一起搬走。
3. 把所有手牌 action 抽成通用 framework。
4. 重寫 opening setup lifecycle。
5. 修改 BACK 容量或可放置 level 規則。
6. 直接進 `ATTACK`。

---

## 七、建議執行切法

### 第一刀：contract 包

- `PlayCardAction`
- `PlayCardValidationContext`
- `PlayCardValidationResult`
- `PlayCardResolutionResult`
- `PlayCardActionValidator`
- focused validator tests

### 第二刀：resolver / application

- `PlayCardLegacyResolutionBridge`
- `PlayCardActionResolver`
- `PlayCardApplicationService`
- direct application integration test

### 第三刀：follow-up path

- `PlayCardEffectResolutionService`
- enter hook summary
- Gift preview / confirm pending decision handoff
- focused follow-up tests

### 第四刀：adapter

- `MatchActionService.playToStage(...)` 改成 adapter
- 保留 legacy action log / response compatibility
- legacy API smoke test 不退步

### 第五刀：event / trigger / acceptance

- `PlayCardEventFactory`
- `PlayCardTriggerDispatcher`
- thin handler contract
- `PLAY_CARD Pilot Acceptance`
- acceptance review
