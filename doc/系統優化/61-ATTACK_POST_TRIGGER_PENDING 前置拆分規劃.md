# ATTACK_POST_TRIGGER_PENDING 前置拆分規劃

更新日期：2026-04-28
定位：`ATTACK_DEFENDER_GIFT_FOLLOWUP` 驗收完成後，拆 attack art 後續 pending interaction 的前置規劃
用途：把 attacker side post-trigger pending 與 defender Gift pending 的 summary / source cards / decision 建立切出獨立 contract，同時保護目前兩段 pending 的建立順序。

---

## 一、為什麼接著拆 post-trigger pending

`ATTACK_ART` 目前已拆出：

1. `ATTACK_COST`
2. `ATTACK_TARGET`
3. `ATTACK_DAMAGE`
4. `ATTACK_DAMAGE_APPLY`
5. `ATTACK_DOWN`
6. `ATTACK_DEFENDER_GIFT_FOLLOWUP`

下一段可拆的是 pending interaction 建立：

- attacker side post-trigger pending
  - attacker Gift trigger preview
  - down event preview
  - `ATTACK_ART_POST_TRIGGER` pending decision
- defender side Gift pending
  - defender self-downed / ally-downed Gift previews
  - `GIFT_TRIGGER` pending decision

這段目前仍在 `MatchActionService.attackArt(...)`，而且直接影響前端 modal 串接與 pending decision order。拆分時最重要的是不要改變兩段 pending 的建立順序與 payload key。

---

## 二、目前位置

`MatchActionService.attackArt(...)` 目前順序：

1. `downEventPreview = attackDownResult.downEventPreview()`
2. `postTriggerEffectSummary = buildAttackArtPostTriggerDeferredSummary(...)`
3. 若 attacker side 有 Gift 或 down event：
   - `buildGiftTriggerInteractionCards(...)`
   - `createAttackArtPostTriggerConfirmPendingInteraction(...)`
4. `defenderGiftEffectSummary = buildGiftTriggeredEffectDeferredSummary(...)`
5. 若 defender side Gift 不為空：
   - `buildGiftTriggerInteractionCards(...)`
   - `createGiftTriggeredEffectConfirmPendingInteraction(...)`
6. attacker rest
7. payload：
   - `postTriggerEffects`
   - `defenderGiftEffects`
   - `pendingInteractionDecisionId`
   - `pendingInteractionDecisionType`
   - `defenderPendingInteractionDecisionId`
   - `defenderPendingInteractionDecisionType`

目前相關 helper：

- `buildAttackArtPostTriggerDeferredSummary(...)`
- `createAttackArtPostTriggerConfirmPendingInteraction(...)`
- `buildAttackArtPostTriggerConfirmMessage(...)`
- `buildAttackArtPostTriggerSections(...)`
- `buildGiftTriggeredEffectDeferredSummary(...)`
- `createGiftTriggeredEffectConfirmPendingInteraction(...)`
- `buildGiftTriggerPayloads(...)`
- `appendGiftSelectionPendingContext(...)`
- `buildGiftTriggerInteractionCards(...)`

其中多個 Gift helper 也被其他 use case 使用，因此第一版不應直接搬走共用 helper，而是以 adapter service 包裝 attack art 呼叫點。

---

## 三、第一版目標

第一版只拆出 attack art post-trigger pending orchestration，不搬共用 Gift helper 的所有呼叫點。

應覆蓋：

1. 建立 attacker side `postTriggerEffectSummary`。
2. 視 Gift / down event 建立 attacker side `postTriggerConfirmDecision`。
3. 建立 defender side `defenderGiftEffectSummary`。
4. 視 defender Gift 建立 `defenderGiftConfirmDecision`。
5. 保留 attacker pending 先於 defender pending 的建立順序。
6. 回傳 payload 所需欄位。

建議新增：

- `AttackPostTriggerPendingContext`
- `AttackPostTriggerPendingResult`
- `AttackPostTriggerPendingService`
- `AttackPostTriggerPendingServiceTest`

第一版 service 可以委派既有 helper：

- `buildAttackArtPostTriggerDeferredSummary(...)`
- `createAttackArtPostTriggerConfirmPendingInteraction(...)`
- `buildGiftTriggeredEffectDeferredSummary(...)`
- `createGiftTriggeredEffectConfirmPendingInteraction(...)`
- `buildGiftTriggerInteractionCards(...)`

若 helper 因 private 無法直接委派，建議先搬出 attack art 專用的最小 helper 到 service，其他 use case 使用的 Gift helper 暫留。

---

## 四、責任邊界

### `AttackPostTriggerPendingService`

應負責：

- 組裝 attacker side post-trigger summary
- 建立 attacker side post-trigger pending decision
- 組裝 defender side Gift deferred summary
- 建立 defender side Gift pending decision
- 回傳兩個 summary 與兩個 decision

不應負責：

- damage / down 判斷
- Gift / Oshi / Fan trigger preview
- attacker rest
- action payload 完整組裝
- action log append
- finish condition evaluation
- pending interaction resolution

### `MatchActionService.attackArt(...)`

第一版應只改成：

- 呼叫 `AttackPostTriggerPendingService.resolvePending(...)`
- 使用 result 提供的：
  - `postTriggerEffectSummary`
  - `postTriggerConfirmDecision`
  - `defenderGiftEffectSummary`
  - `defenderGiftConfirmDecision`
- 保留 attacker rest、payload、action log、finish checks

---

## 五、輸入 / 輸出草案

### Input

`AttackPostTriggerPendingContext` 至少包含：

- `matchId`
- `attackerUserId`
- `defenderUserId`
- `turnNumber`
- `attackerCardInstanceId`
- `attackerCardId`
- `downedTargetCardInstanceId`
- `downedTargetCardId`
- `giftTriggeredEffects`
- `downEventPreview`
- `defenderGiftTriggeredEffects`

### Output

`AttackPostTriggerPendingResult` 至少包含：

- `postTriggerEffectSummary`
- `postTriggerConfirmDecision`
- `defenderGiftEffectSummary`
- `defenderGiftConfirmDecision`

可加 helper：

- `hasPostTriggerPendingInteraction()`
- `hasDefenderGiftPendingInteraction()`

---

## 六、現有測試基準

代表性 integration：

- `attackArtShouldTriggerDownedHolomemExtraLifeLoss`
- `attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention`
- `attackArtShouldTriggerOfficialGiftHsd08005WhenAllyDownedAndLifeIsNotHigher`
- `attackArtShouldTriggerOfficialGiftHsd09007WhenSelfDownedInCollabAndLifeIsLower`
- `attackArtShouldTriggerOfficialGiftHbp05028WhenStageBotanDealsSpecialDamageThirtyOrMore`
- `attackArtShouldTriggerOfficialGiftHbp06014AndSwapHolopowerPickWithHandCard`
- `attackArtShouldTriggerOfficialGiftHbp06027AndGrantExtraBloomAllowance`

第一版 focused tests 應覆蓋：

1. no attacker Gift / no down event 時不建立 attacker pending，但 summary 為 non-deferred。
2. attacker Gift 存在時建立 `ATTACK_ART_POST_TRIGGER` pending。
3. down event 存在時建立 `ATTACK_ART_POST_TRIGGER` pending，additional context 含 down event。
4. defender Gift 存在時建立 `GIFT_TRIGGER` pending。
5. attacker pending 先建立、defender pending 後建立。
6. result 保留 payload 所需 decision id / type。

---

## 七、允許暫留

第一版允許：

- common Gift helper 仍留在 `MatchActionService` 或以 package helper 暫存。
- payload 完整組裝仍留在 `MatchActionService`。
- attacker rest 仍留在 `MatchActionService`。
- finish condition evaluation 仍留在 `MatchActionService`。

第一版不允許：

- 改變 attacker pending 與 defender pending 的建立順序。
- 改變 `postTriggerEffects` / `defenderGiftEffects` payload key。
- 改變 `pendingInteractionDecisionId` / `defenderPendingInteractionDecisionId` payload key。
- 改變 decision type：
  - attacker side 仍為 `ATTACK_ART_POST_TRIGGER`
  - defender side 仍為 `GIFT`
- 改變 attacker rest timing。
- 順手改 pending resolution 行為。

---

## 八、建議施工順序

### Step APTP-1：contract / service skeleton

- 新增 context / result 型別
- 新增 service
- 搬出 attack art pending orchestration
- 補 focused tests
- 不改 `attackArt(...)`

### Step APTP-2：adapter bridge

- `MatchActionService.attackArt(...)` 改呼叫 service
- 保留 attacker rest / payload / finish checks
- 跑 post-trigger / defender Gift pending integration baseline

### Step APTP-3：acceptance review

- 檢查完成條件
- 檢查 allow / block 清單
- 盤點測試缺口
- 決定下一個 attack 子流程

---

## 九、下一步

建議先進 `APTP-1`：

- `AttackPostTriggerPendingContext`
- `AttackPostTriggerPendingResult`
- `AttackPostTriggerPendingService`
- focused tests
