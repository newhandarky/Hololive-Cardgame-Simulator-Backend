# COLLAB 第三條 Pilot 啟動規劃

更新日期：2026-04-27
定位：`BLOOM` 之後的第三條 pilot use case 啟動文件
用途：說明為什麼第三條 pilot 優先選 `COLLAB`，以及這條線要驗證什麼、先做什麼、不做什麼。

---

## 一、為什麼第三條 pilot 選 `COLLAB`

`END_TURN` 已驗證 lifecycle action 模板。
`BLOOM` 已驗證 card action 模板，並證明新架構能承接：

- target validation
- card/state mutation
- effect preview / confirm
- event / trigger dispatch
- legacy action payload compatibility

第三條 pilot 不應直接進 `ATTACK`，因為 attack core 會一次碰到：

- attacker / defender target legality
- cheer cost
- art modifier
- damage / down / life loss
- replacement / prevention
- gift trigger
- multi-step follow-up interaction

`COLLAB` 比 `ATTACK` 小，但比單純 movement 更複雜，適合驗證：

1. 同一 public API 中拆出特定 target zone action 的能力
2. stage action 類型是否也能套用 `Action / Validator / Resolver / EffectResolution / Event / Trigger / Application`
3. gift preview 與 collab triggered effect 是否能被整理成可描述的 follow-up path

一句話講：

- `END_TURN` 驗證 lifecycle
- `BLOOM` 驗證 card action
- `COLLAB` 應驗證 stage action + effect/gift follow-up

---

## 二、目前 `COLLAB` 的問題

目前 `COLLAB` 實作在：

- `MatchActionService.moveStageHolomem(...)`

也就是 `MOVE_STAGE_HOLOMEM` 與 `COLLAB` 共用同一個 public method。

這個方法目前同時承擔：

- request parsing
- phase / turn / pending gate
- target zone validation
- source holomem lookup
- BACK -> CENTER / COLLAB 分支判斷
- rested / once-per-turn / occupied zone validation
- stage mutation
- top deck -> holopower mutation
- collab effect preview
- gift trigger preview
- event hook
- confirm pending interaction 建立
- action payload 組裝
- action log
- 非 deferred effect 的勝負檢查與 life loss send cheer follow-up

問題不是 `COLLAB` 不能跑，而是：

- 一般 move stage 與 collab 混在同一個方法
- validator / resolver / effect follow-up 的責任沒有分層
- gift + collab effect 的 preview / confirm path 仍散在 legacy method

---

## 三、這條 pilot 驗證什麼，不驗證什麼

### 驗證範圍

`COLLAB` pilot 應驗證：

1. stage action 類型的 action contract
2. target zone = `COLLAB` 的 validator contract
3. resolver 如何處理：
   - BACK -> COLLAB movement
   - top deck -> holopower movement
4. effect follow-up 如何處理：
   - collab triggered effect preview
   - collab 觸發 gift preview
   - single confirm interaction 中合併 collab/gift sections
5. event 與 trigger 如何描述：
   - `COLLAB_REQUEST_ACCEPTED`
   - `COLLAB_RESOLVED`
   - `COLLAB_EFFECT_PREVIEW_CREATED`
   - `COLLAB_GIFT_PREVIEW_CREATED`
   - `COLLAB_TRIGGER_CONFIRM_REQUIRED`
6. legacy `moveStageHolomem(... targetZone=COLLAB)` 是否能退化成 adapter

### 不驗證範圍

這條 pilot 暫時不處理：

1. `ATTACK`
2. damage / down / life loss battle chain 的完整重整
3. 一般 `MOVE_STAGE_HOLOMEM` 全面遷移
4. `PLAY_CARD` / support card 遷移
5. 通用 stage action framework

---

## 四、建議的 COLLAB 標準流程

沿用既有標準流程：

1. Load Context
2. Build Action
3. Validate
4. Resolve State
5. Resolve Effect Follow-up
6. Emit Events
7. Dispatch Triggers
8. Persist & Log
9. Publish Snapshot / Response

### 1. Load Context

至少包含：

- match / actor
- turn / phase
- pending interaction 狀態
- source holomem
- source zone
- rested 狀態
- target zone occupancy
- 是否本回合已 collab
- deck top card 是否存在
- stage action lock

### 2. Build Action

建議第一版 action 形狀：

- `actionType = COLLAB`
- `matchId`
- `actorUserId`
- `sourceCardInstanceId`
- `requestedTurnNumber`
- `targetZone = COLLAB`
- `idempotencyKey`
- `traceId`

注意：

- `MoveStageHolomemActionRequest.targetZone = COLLAB` 是 legacy input。
- 新 contract 應把它轉成 `CollabAction`，而不是讓 resolver 持續讀 DTO。

### 3. Validate

至少要回答：

- 是否 actor turn
- phase 是否 MAIN
- 是否有 blocking pending interaction
- source holomem 是否存在且屬於 actor
- source zone 是否 BACK
- source 是否 rested
- COLLAB zone 是否已被占用
- 本回合是否已使用過 COLLAB
- stage action lock 是否阻擋 COLLAB

### 4. Resolve State

resolver 應集中處理：

- source holomem zone `BACK -> COLLAB`
- top deck card 移到 holopower
- 回傳 moved holomem / holopower card / source card 資訊

resolver 不應直接處理：

- collab effect preview
- gift preview
- pending interaction
- action log
- WebSocket snapshot

### 5. Resolve Effect Follow-up

建議新增類似 BLOOM 的：

- `CollabEffectResolutionService`

責任：

- `previewCollabTriggeredEffect(...)`
- `previewGiftTriggeredEffectsOnCollab(...)`
- `onHolomemCollab(...)`
- 建立 `COLLAB_TRIGGER` confirm pending interaction
- 回傳 `CollabEffectResolution`

### 6. Emit Events

至少可定義：

- `COLLAB_REQUEST_ACCEPTED`
- `COLLAB_RESOLVED`
- `COLLAB_EFFECT_PREVIEW_CREATED`
- `COLLAB_GIFT_PREVIEW_CREATED`
- `COLLAB_TRIGGER_CONFIRM_REQUIRED`

事件順序應固定，且 event factory 不依賴 action log payload。

### 7. Dispatch Triggers

第一版可沿用 BLOOM 的 handler 形狀，但不急著抽共用泛型。

分類建議：

- sync：
  - request accepted
  - resolved
  - collab effect preview created
  - gift preview created
- deferred：
  - trigger confirm required

---

## 五、和 BLOOM 的差異

`COLLAB` 不只是 BLOOM 的複製，差異包括：

1. source 已在 stage，不是從 hand 進場
2. state mutation 是 zone movement，不是 card stack update
3. 一次 action 會固定產生 top deck -> holopower
4. follow-up 同時可能包含：
   - collab triggered effect
   - gift triggered effect
5. public API 目前和一般 `MOVE_STAGE_HOLOMEM` 共用入口

所以 `COLLAB` pilot 的重點是：

- 從混合入口中拆出 target-zone-specific action
- 驗證 stage action 能否沿用 BLOOM 的 bridge 模板

---

## 六、不應直接做成什麼

不要一開始就做：

1. 把所有 `MOVE_STAGE_HOLOMEM` 一起搬走
2. 把 `COLLAB`、`CENTER` movement、opening setup movement 一次統一
3. 抽全域 `StageActionApplicationService`
4. 把 gift trigger engine 全面重寫
5. 直接進 `ATTACK`

正確順序：

1. 先讓 `COLLAB` 自己跑通
2. 讓它和 `BLOOM` 形成兩條 card/stage action 範本
3. 再比較是否需要抽共用 `EffectResolutionService` 或 follow-up pending writer

---

## 七、建議文件包

正式啟動 `COLLAB` pilot 前，建議補：

1. `COLLAB Action Contract`
2. `COLLAB Validator Contract`
3. `COLLAB Resolution Contract`
4. `COLLAB Effect Follow-up Contract`
5. `COLLAB Event / Trigger Contract`
6. `COLLAB Persistence Contract`
7. `COLLAB Pilot Acceptance`

若希望降低文件量，也可以先把 effect follow-up 與 trigger contract 合併，但 acceptance 必須獨立。

---

## 八、建議啟動順序

### 階段 1：文件定義

先補 contract 包，確認：

- action 欄位
- validator gate 順序
- BACK -> COLLAB mutation 責任
- holopower movement 責任
- collab effect / gift preview / confirm 邊界
- action log / snapshot 不可退步項目

### 階段 2：第一刀 bridge

目標：

- 建立 `CollabAction`
- 建立 `CollabValidationContext`
- 建立 `CollabActionValidator`
- 建立 `CollabActionResolver`
- 建立 `CollabApplicationService`
- 建立 `CollabLegacyResolutionBridge`
- 讓 `MatchActionService.moveStageHolomem(... targetZone=COLLAB)` 退化成 adapter

### 階段 3：effect follow-up path

目標：

- 建立 `CollabEffectResolutionService`
- 把 collab effect / gift preview / confirm pending 建立從主方法移出
- 建立 collab event / trigger dispatcher path

### 階段 4：驗收 review

目標：

- 判斷 `COLLAB` 是否成為第三條可複製模板
- 再決定下一條是否進 `ATTACH_CHEER`、`PLAY_CARD` 或 `ATTACK`

---

## 九、目前決策

目前決策如下：

1. `BLOOM` pilot 視為已通過階段性驗收
2. 第三條 pilot 優先選定 `COLLAB`
3. 下一步先補 `COLLAB` contract 包，不直接先改 code
4. contract 包完成後，再開始第一刀 bridge
