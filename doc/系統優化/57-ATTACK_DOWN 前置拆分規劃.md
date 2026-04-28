# ATTACK_DOWN 前置拆分規劃

更新日期：2026-04-28
定位：`ATTACK_DAMAGE_APPLY` 驗收完成後、拆 defender Gift follow-up 前的前置規劃
用途：先把 attack art 的 down detection / down preview 邊界切出來，避免直接搬 defender self-downed / ally-downed Gift follow-up 而改變 pending interaction timing。

---

## 一、為什麼先做 attack down

`ATTACK_ART` 目前已先後拆出：

1. `ATTACK_COST`
2. `ATTACK_TARGET`
3. `ATTACK_DAMAGE`
4. `ATTACK_DAMAGE_APPLY`

下一段最適合拆的是 down detection / preview，而不是 defender Gift follow-up。

原因：

1. down detection 是 damage application 後、Gift follow-up 前的共同判斷點。
2. attacker side 的 `previewGiftTriggeredEffectsOnDownedOpponent(...)` 與 `applyArtDownTriggeredEffects(...)` 依賴同一個 downed 判斷。
3. `downEventPreview` 是 post-trigger pending interaction 的核心輸入，適合先做成 contract。
4. defender self-downed / ally-downed Gift follow-up 還依賴 snapshot timing，應留到下一條子流程再拆。

---

## 二、目前位置

主要 production 邏輯位於 `MatchActionService.attackArt(...)`：

- `attackSummaryForTriggeredChecks = mergeEffectSummaryForChecks(...)`
- `hasHolomemDowned(attackSummaryForTriggeredChecks)`
- attacker side trigger preview：
  - `matchGiftTriggerService.previewGiftTriggeredEffectsOnArt(...)`
  - `matchGiftTriggerService.previewGiftTriggeredEffectsOnDownedOpponent(...)`
- art down triggered effect：
  - `matchTriggeredCombatEffectService.applyArtDownTriggeredEffects(...)`
  - no-op `ART_DOWNED_OPPONENT` summary
- defender follow-up still in same block：
  - `applyOfficialOshiSelfDownedEffects(...)`
  - `previewGiftTriggeredEffectsOnSelfDowned(...)`
  - `previewGiftTriggeredEffectsOnAllyDowned(...)`
  - `previewHbp01124FanTriggeredEffectsOnSelfDowned(...)`
- post-trigger decision input：
  - `downEventPreview = extractDownEventPreview(artSummary)`
  - `buildAttackArtPostTriggerDeferredSummary(...)`

目前 helper：

- `hasHolomemDowned(...)`
- `mergeEffectSummaryForChecks(...)`
- `extractDownEventPreview(...)`
- `buildAttackArtPostTriggerDeferredSummary(...)`
- `matchGiftTriggerService.previewGiftTriggeredEffectsOnDownedOpponent(...)`
- `matchTriggeredCombatEffectService.applyArtDownTriggeredEffects(...)`

本次不應搬 defender self-downed / ally-downed Gift follow-up。

---

## 三、第一版目標

第一版只拆出 attack down summary，不搬 defender Gift follow-up。

應覆蓋：

1. 合併 `artSummary` 與 official extra / Oshi reactive effect summaries。
2. 判斷是否有 Holomem downed。
3. 建立 attacker side `giftTriggeredEffects`：
   - always include `previewGiftTriggeredEffectsOnArt(...)`
   - downed 時 include `previewGiftTriggeredEffectsOnDownedOpponent(...)`
4. 建立 `artDownTriggeredEffectSummary`。
5. 建立 `downEventPreview`。
6. 回傳 `attackSummaryForTriggeredChecks`，供後續 defender follow-up 與 finish checks 使用。

建議新增：

- `AttackDownContext`
- `AttackDownResult`
- `AttackDownService`
- `AttackDownServiceTest`

第一版 `AttackDownService` 可以呼叫既有：

- `MatchGiftTriggerService`
- `MatchTriggeredCombatEffectService`

但不應呼叫：

- `applyOfficialOshiSelfDownedEffects(...)`
- `previewGiftTriggeredEffectsOnSelfDowned(...)`
- `previewGiftTriggeredEffectsOnAllyDowned(...)`
- `previewHbp01124FanTriggeredEffectsOnSelfDowned(...)`
- pending interaction creation
- attacker rest
- action log append
- finish condition evaluation

---

## 四、責任邊界

### `AttackDownService`

應負責：

- 合併攻擊主效果與附加效果 summary
- 判斷 attack 是否造成 down
- 建立 attacker side post-trigger gift preview
- 建立 art down triggered effect summary
- 擷取 down event preview
- 回傳給 `MatchActionService` 後續使用

不應負責：

- damage summary
- damage application
- defender self-downed / ally-downed Gift follow-up
- pending interaction creation
- action payload / action log
- finish condition evaluation

### `MatchActionService.attackArt(...)`

第一版應只改成：

- 保留 official card art extra / Oshi reactive call site
- 呼叫 `AttackDownService.resolveDown(...)`
- 使用 result 提供的：
  - `attackSummaryForTriggeredChecks`
  - `giftTriggeredEffects`
  - `artDownTriggeredEffectSummary`
  - `downEventPreview`
- 保留 defender Gift follow-up、pending interactions、payload、finish checks

---

## 五、輸入 / 輸出草案

### Input

`AttackDownContext` 至少包含：

- `matchId`
- `attackerUserId`
- `opponentUserId`
- `turnNumber`
- `attackerCardInstanceId`
- `attackerCardId`
- `artName`
- `artEffectJsonText`
- `effectiveTargetCardInstanceId`
- `hasOpponentHolomem`
- `artSummary`
- `officialCardArtExtraEffects`
- `officialOshiArtReactiveEffects`

### Output

`AttackDownResult` 至少包含：

- `attackSummaryForTriggeredChecks`
- `hasDownedHolomem`
- `giftTriggeredEffects`
- `artDownTriggeredEffectSummary`
- `downEventPreview`

---

## 六、現有測試基準

目前已有代表性 integration：

- `attackArtShouldApplyDamageToOpponentHolomemAndRestAttacker`
- `attackArtShouldTriggerDownedHolomemExtraLifeLoss`
- `attackArtShouldTriggerOfficialExtraLifeLossForHbp02041WhenSelfDowned`
- `attackArtShouldTriggerOfficialExtraLifeLossForHbp03022WhenSelfDowned`
- `attackArtShouldTriggerOfficialGiftHsd08005WhenAllyDownedAndLifeIsNotHigher`
- `attackArtShouldNotTriggerOfficialGiftHsd08005WhenOwnerLifeIsHigherThanOpponent`
- `attackArtShouldTriggerOfficialGiftHsd09007WhenSelfDownedInCollabAndLifeIsLower`
- `attackArtShouldTriggerOfficialExtraLifeLossForHbp03039WhenSelfDowned`
- `attackArtShouldTriggerOfficialExtraLifeLossForHbp03083WhenSelfDowned`
- `attackArtShouldTriggerOfficialGiftHbp05028WhenStageBotanDealsSpecialDamageThirtyOrMore`

第一版新增 focused tests 應覆蓋：

1. no down 時只保留 art trigger preview，art down summary 回傳 no-op。
2. downed 時附加 downed opponent trigger preview。
3. downed 時呼叫 `applyArtDownTriggeredEffects(...)`。
4. `downEventPreview` 可從 nested art summary 擷取。
5. `attackSummaryForTriggeredChecks` 正確合併主效果與附加效果。

---

## 七、允許暫留

第一版允許：

- `attackArt(...)` 仍是 `ATTACK_ART` 主流程入口。
- official card art extra / Oshi reactive call site 仍留在 `MatchActionService`。
- defender self-downed / ally-downed Gift follow-up 仍留在 `MatchActionService`。
- post-trigger pending interaction creation 仍留在 `MatchActionService`。
- finish condition evaluation 仍留在 `MatchActionService`。

第一版不允許：

- 順手改 defender Gift follow-up timing。
- 順手改 down event pending interaction timing。
- 順手改 art down triggered effect payload key。
- 順手改 finish condition evaluation。
- 順手改 damage summary / damage application。

---

## 八、建議施工順序

### Step ADOWN-1：contract / service skeleton

- 新增 down context / result 型別
- 新增 `AttackDownService`
- 搬出 merge / has down / attacker side down preview / art down summary / down event preview
- 補 focused tests
- 不改 `attackArt(...)`

### Step ADOWN-2：adapter bridge

- `MatchActionService.attackArt(...)` 改呼叫 `AttackDownService`
- 保留 defender Gift follow-up 與 pending interaction 原流程
- 跑 down / self-downed / ally-downed integration baseline

### Step ADOWN-3：acceptance review

- 檢查 attack down 子流程是否可視為 defender Gift follow-up 前置拆分完成
- 盤點剩餘 allow / block 清單
- 再決定是否進 `ATTACK_DEFENDER_GIFT_FOLLOWUP`

---

## 九、下一步

建議先進 `ADOWN-1`：

- `AttackDownContext`
- `AttackDownResult`
- `AttackDownService`
- focused tests

完成後再接 `attackArt(...)` adapter。
