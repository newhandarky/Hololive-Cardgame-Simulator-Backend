# PLAY_CARD Effect Follow-up Contract

更新日期：2026-04-27
定位：`PLAY_CARD` pilot effect follow-up 契約
用途：定義 Holomem 進場後的 enter hook、Gift preview 與 pending confirm 建立責任，避免 follow-up 邏輯繼續混在 legacy `playToStage(...)`。

---

## 一、Follow-up 目標

`PlayCardEffectResolutionService` 或同等服務只負責：

- 根據 `PlayCardResolutionResult` 處理進場後 follow-up

它不應負責：

- 判斷 action 是否可執行
- 移動 card
- 建立 `match_holomems`
- 寫 action log
- dispatch trigger
- 處理 attack cost payment

---

## 二、輸入

Follow-up 輸入應為：

1. `PlayCardAction`
2. `PlayCardResolutionResult`

必要資訊由 resolution result 提供：

- `matchId`
- `actorUserId`
- `cardId`
- `cardInstanceId`
- `targetZone`
- `turnNumber`
- `openingReset`

---

## 三、RESET 行為

若 `openingReset = true`：

- 不立即執行 enter hook
- 不建立 Gift preview
- 不建立 pending confirm
- 回傳 summary：
  - `deferredUntilLiveStart = true`

此行為要保留現有開場設置語意。

---

## 四、MAIN 行為

若 `openingReset = false`：

### 1. Enter hook

呼叫既有：

- `matchEventHookService.onHolomemEnter(...)`

回傳：

- enter hook summary

### 2. Gift preview

呼叫既有：

- `matchGiftTriggerService.previewGiftTriggeredEffectsOnStageEnter(...)`

回傳：

- Gift triggered effects
- Gift effect summary

### 3. Gift confirm pending decision

若 Gift triggered effects 不為空，建立：

- `TRIGGER_EFFECT_CONFIRM`

pending context 至少要保留：

- source card payload
- Gift triggers
- trigger type = `STAGE_ENTER`
- turn number

---

## 五、輸出

建議輸出型別：

- `PlayCardEffectResolution`

至少包含：

- `triggerSummary`
- `giftTriggeredEffects`
- `giftEffectSummary`
- `giftTriggerConfirmDecision`
- `deferredUntilLiveStart`

---

## 六、和 Event / Trigger 的邊界

Follow-up service 負責產生 effect resolution 結果，但不直接 dispatch event。

Event factory 應根據：

- `PlayCardAction`
- `PlayCardResolutionResult`
- `PlayCardEffectResolution`

建立對應 events。

---

## 七、完成標準

本 contract 落地後，應能回答：

1. RESET 開場是否保持 deferred follow-up？
2. MAIN 進場 enter hook 是否離開 legacy method？
3. Gift preview / confirm pending decision 是否離開 legacy method？
4. follow-up 是否有 focused tests 保護？
