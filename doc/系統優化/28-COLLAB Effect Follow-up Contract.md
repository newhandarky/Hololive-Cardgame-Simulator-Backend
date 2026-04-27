# COLLAB Effect Follow-up Contract

更新日期：2026-04-27
定位：`COLLAB` pilot effect follow-up 契約
用途：定義 COLLAB state mutation 後，collab triggered effect、gift trigger preview 與 confirm pending interaction 的責任邊界。

---

## 一、Effect Follow-up 目標

`CollabEffectResolutionService` 應負責：

- collab triggered effect preview
- collab 造成的 gift trigger preview
- `onHolomemCollab(...)` event hook summary
- 合併 collab / gift sections
- 建立 `COLLAB_TRIGGER` confirm pending interaction
- 回傳結構化 result 供 action payload 與 event factory 使用

它不應負責：

- source Holomem movement
- top deck -> holopower
- action log
- snapshot publish
- battle damage/down/life loss chain

---

## 二、輸入

輸入應包含：

1. `CollabAction`
2. `CollabResolutionResult`

至少需要：

- match id
- actor user id
- turn number
- source card instance id
- source card id
- source holomem id
- target zone

---

## 三、Collab Effect Preview

第一版應沿用既有能力：

- `MatchTriggeredCardEffectService.previewCollabTriggeredEffect(...)`

輸出應轉成 `collabEffectSummary`：

- `hasCollabEffect`
- `deferred`
- `requestedEffects`
- `executedEffects`
- `unsupportedEffects`
- `rawText`
- `diceRoll`

若無 collab effect：

- `hasCollabEffect = false`
- `deferred = false`

---

## 四、Gift Trigger Preview

第一版應沿用既有能力：

- `MatchGiftTriggerService.previewGiftTriggeredEffectsOnCollab(...)`

若有 gift trigger：

- 建立 `collabGiftEffectSummary`
- summary 應標示 deferred
- 保留 `triggerType = COLLAB`
- 保留 gift holder / target / attached cheer / stack 等 context，讓 confirm 後能接續既有 resolution path

若無 gift trigger：

- 不建立或回傳空 summary

---

## 五、Event Hook Summary

第一版應沿用：

- `MatchEventHookService.onHolomemCollab(...)`

輸出應回填：

- `triggerSummary`

這屬於 legacy event hook 相容摘要，不等同新架構 `CollabEvent`。

---

## 六、Confirm Pending Interaction

若以下任一條件成立，應建立 confirm pending interaction：

- `collabPreview.hasEffect()`
- gift trigger preview 不為空

pending interaction 應符合既有 `TRIGGER_EFFECT_CONFIRM` contract：

- `sourceActionType = COLLAB`
- `effectType = COLLAB_TRIGGER`
- `sourceCardInstanceId`
- `sourceCardId`
- context 中包含：
  - `hasCollabEffect`
  - `giftTriggers`
  - `triggerSections`
  - selection metadata

第一版仍可寫入 legacy `match_pending_decisions` table，但寫入責任必須集中在 `CollabEffectResolutionService` 或其 helper，不應留在 `MatchActionService.moveStageHolomem(...)` 主方法。

---

## 七、輸出

建議 result：

- `CollabEffectResolution`

至少包含：

- `collabEffectSummary`
- `collabGiftEffectSummary`
- `triggerSummary`
- `pendingInteractionDecisionId`
- `pendingInteractionDecisionType`
- `triggerResolutionOrder`
- `deferredEffect`

`deferredEffect` 應代表是否已建立或需要 confirm follow-up。

---

## 八、非 deferred 後續檢查

若沒有 deferred collab effect，legacy adapter 可暫時保留：

- match finish check
- life defeat check
- no Holomem defeat check
- life loss send cheer interaction enqueue

但這些邏輯不應混入 resolver。

後續若要抽通用 post-effect finalization，應在 BLOOM / COLLAB 兩條 use case 都穩定後再做。

---

## 九、完成標準

本 contract 落地後，應能回答：

1. collab effect preview 在哪裡發生？
2. gift trigger preview 在哪裡發生？
3. confirm pending interaction 由哪個 service 建立？
4. `MatchActionService.moveStageHolomem(... targetZone=COLLAB)` 是否不再混寫 effect preview / confirm 建立？
