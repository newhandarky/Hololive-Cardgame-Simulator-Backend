# PLAY_CARD Resolution Contract

更新日期：2026-04-27
定位：`PLAY_CARD` pilot resolution 契約
用途：定義 `PlayCardActionResolver` 應負責的 state mutation、輸出結果與不可承擔的責任。

---

## 一、Resolver 目標

`PlayCardActionResolver` 只負責一件事：

- 將已通過 validation 的 `PlayCardAction` 實際套用到 board state

它不應負責：

- 判斷 action 是否合法
- 建立 action log
- 建立 Gift pending decision
- dispatch trigger
- publish snapshot
- 處理 attack cost payment

---

## 二、輸入

Resolver 輸入應為：

1. `PlayCardAction`
2. `PlayCardValidationContext`

Resolver 可依賴 validation context 中已載入的：

- match entity
- source card snapshot
- member metadata
- target zone
- current turn number
- opening reset flag

Resolver 不應再自行重新查 source card 是否合法。

---

## 三、必須執行的 mutation

### 1. Move source card to stage

更新 `match_cards`：

- `zone = 'STAGE'`
- `order_index = NULL`
- `is_face_down = openingReset`
- `updated_at = CURRENT_TIMESTAMP`

條件至少包含：

- `id = cardInstanceId`
- `match_id = matchId`
- `owner_user_id = actorUserId`
- `zone = 'HAND'`

若 update count 不是 1，應視為狀態衝突。

### 2. Create match_holomems row

插入 `match_holomems`：

- `match_id`
- `owner_user_id`
- `match_card_id`
- `card_id`
- `zone = targetZone`
- `is_rested = FALSE`
- `is_face_down = openingReset`
- `damage_taken = 0`
- `current_level = normalized source level`
- `entered_turn_number = currentTurnNumber`

應回傳新建立的 `matchHolomemId`。

### 3. Create stack relation

插入 `match_holomem_stack_cards`：

- `match_holomem_id`
- `match_card_id`
- `stack_order = 1`

---

## 四、輸出

建議輸出型別：

- `PlayCardResolutionResult`

至少包含：

- `match`
- `actorUserId`
- `turnNumber`
- `cardInstanceId`
- `cardId`
- `sourceZone`
- `targetZone`
- `matchHolomemId`
- `enteredTurnNumber`
- `faceDown`
- `currentLevel`
- `openingReset`

---

## 五、錯誤語意

Resolver 只應處理執行期狀態衝突：

- source card 已不在 `HAND`
- insert `match_holomems` 沒有回傳 id
- stack relation insert 失敗

這些錯誤代表 validation 後 state 已改變，應轉成 conflict / retry 語意。

Resolver 不應用來處理：

- phase 不允許
- level 不允許
- target zone 不允許
- BACK full
- source card 不是 MEMBER

---

## 六、和 follow-up 的邊界

Resolver 完成後，才可進入 follow-up。

Resolver 不應：

- 呼叫 `matchEventHookService.onHolomemEnter(...)`
- 呼叫 `matchGiftTriggerService.previewGiftTriggeredEffectsOnStageEnter(...)`
- 建立 `TRIGGER_EFFECT_CONFIRM` pending decision

這些應集中在 `PlayCardEffectResolutionService` 或同等 follow-up service。

---

## 七、完成標準

本 contract 落地後，應能回答：

1. PLAY_CARD 的 board mutation 是否集中在 resolver？
2. `match_cards` / `match_holomems` / `match_holomem_stack_cards` 是否有 direct application tests？
3. resolver 是否不再建立 action log 或 pending interaction？
4. legacy `playToStage(...)` 是否不再混寫主要 mutation？
