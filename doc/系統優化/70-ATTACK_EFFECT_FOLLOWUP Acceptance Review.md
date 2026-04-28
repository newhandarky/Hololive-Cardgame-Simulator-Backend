# ATTACK_EFFECT_FOLLOWUP Acceptance Review

更新日期：2026-04-28
結論：通過，無 blocker

---

## 一、Review 範圍

本次 review 對照：

- `69-ATTACK_EFFECT_FOLLOWUP 前置拆分規劃.md`
- `AttackEffectFollowupContext`
- `AttackEffectFollowupResult`
- `AttackEffectDamagePreventionContext`
- `AttackEffectDamagePreventionResult`
- `AttackEffectPostDamageContext`
- `AttackEffectPostDamageResult`
- `AttackEffectFollowupService`
- `AttackEffectFollowupServiceTest`
- `MatchActionService.attackArt(...)` adapter bridge

目標是確認 `ATTACK_EFFECT_FOLLOWUP` 第一版是否已完成：

- pre-damage follow-up orchestration
- damage prevention follow-up orchestration
- post-damage official follow-up orchestration
- adjusted damage 回傳
- executed effects 回傳
- `attackArt(...)` adapter bridge
- 保留既有卡片規則 helper 與 payload / downstream shape

本次不驗收各單卡效果內部規則重寫，也不驗收完整 `AttackArtApplicationService`。

---

## 二、完成條件檢查

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| pre-damage follow-up service | PASS | `resolvePreDamage(...)` 已控制 Holox -> HBP02-039 -> HBP02-040 順序，focused test 覆蓋。 |
| Holox art bonus 回傳 | PASS | `AttackEffectFollowupResult.artBonus()` 由 Holox result 回傳，adapter bridge 已接回 damage resolve。 |
| HBP02 summaries 回傳 | PASS | HBP02-039 / HBP02-040 summary 原樣回傳並接入 payload / additional effects。 |
| damage prevention follow-up service | PASS | `resolveDamagePrevention(...)` 已包裝 defender damage received Gift prevention。 |
| adjusted damage 回傳 | PASS | service 從 `damageAfter` 產生 adjusted damage，並保留負數 clamp。 |
| `GIFT_TRIGGER` timing 保留 | PASS | action log 仍由 `MatchActionService` 在原位置寫入，service 僅回傳 `actionLogRequired`。 |
| post-damage official follow-up service | PASS | `resolvePostDamage(...)` 已控制 official card art extra -> Oshi reactive 順序。 |
| executed effects 回傳 | PASS | official card / Oshi reactive executed effects 已由 service 抽出並傳回 `AttackDownService`。 |
| adapter bridge | PASS | `MatchActionService.attackArt(...)` 三段 follow-up 均已改呼叫 `AttackEffectFollowupService`。 |

---

## 三、Allow / Block 清單

### 已允許暫留

- `resolveHoloxSlotRevealSummary(...)` 留在 `MatchActionService`，透過 adapter 委派。
- `applyHbp02039HoloxSupportRecovery(...)` 留在 `MatchActionService`，透過 adapter 委派。
- `applyHbp02040HoloxLifeLoss(...)` 留在 `MatchActionService`，透過 adapter 委派。
- `matchTriggeredCombatEffectService.resolveTriggeredGiftDamagePrevention(...)` 仍是 defender damage received Gift 的實際規則入口。
- `GIFT_TRIGGER` action log 寫入仍留在 `MatchActionService.attackArt(...)` 原位置。
- `applyOfficialCardArtExtraEffects(...)` 留在 `MatchActionService`，透過 adapter 委派。
- `applyOfficialOshiArtReactiveEffects(...)` 留在 `MatchActionService`，透過 adapter 委派。
- `AttackDownService` / pending / rest payload / action log / finish check 呼叫點不在本次 scope 內移動。

### 已確認未做

- 未重寫 Holox / HBP02 / HBP01 official effect 的單卡規則。
- 未改 attack cost / target / damage / damage apply 的呼叫順序。
- 未改 defender damage prevention 的 `GIFT_TRIGGER` action log timing。
- 未改 `defenderDamageReceivedGift` 無觸發時的 payload `null` 形狀。
- 未改 official card art extra / Oshi reactive 的 executed effects shape。
- 未改 downstream `AttackDownService` 輸入形狀。
- 未建立完整 `AttackArtApplicationService`。

---

## 四、測試覆蓋

Focused unit：

- `AttackEffectFollowupServiceTest`
  - pre-damage resolver 順序
  - Holox art bonus
  - HBP02 summaries
  - damage prevention skip / adjusted damage / clamp / null context
  - post-damage official card -> Oshi reactive 順序
  - post-damage summaries 與 executed effects
  - non-map executed effects 過濾

Integration baseline：

- `attackArtShouldTriggerOfficialGiftHbp02039WhenHoloxSlotRevealsSupport`
- `attackArtShouldTriggerOfficialGiftHbp02040WhenHoloxSlotRevealsSameBloomLevelMembers`
- `attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention`
- `attackArtShouldNotPreventDamageWithOfficialGiftHbp01027WhenDiceConditionFailed`
- `attackArtShouldTriggerOfficialArtExtraEffectHbp01087AndDealSpecialDamageToAllOpponentBackHolomems`
- `attackArtShouldTriggerOfficialOshiHbp01007WhenBlueHolomemDamagesOpponentBack`
- `attackArtShouldTriggerOfficialOshiHbp01008WhenBlueAbilityArchivesCheer`

驗證命令：

- `./mvnw -q -Dtest=AttackEffectFollowupServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerOfficialGiftHbp02039WhenHoloxSlotRevealsSupport+attackArtShouldTriggerOfficialGiftHbp02040WhenHoloxSlotRevealsSameBloomLevelMembers test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention+attackArtShouldNotPreventDamageWithOfficialGiftHbp01027WhenDiceConditionFailed test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerOfficialArtExtraEffectHbp01087AndDealSpecialDamageToAllOpponentBackHolomems+attackArtShouldTriggerOfficialOshiHbp01007WhenBlueHolomemDamagesOpponentBack+attackArtShouldTriggerOfficialOshiHbp01008WhenBlueAbilityArchivesCheer test`

---

## 五、測試缺口

目前沒有 blocker。

可後續補強：

1. adapter-level payload snapshot：確認 `defenderDamageReceivedGift` 無觸發時仍為 `null`。
2. official card art extra executed effects 的多 target snapshot。
3. Oshi reactive 依賴 official card art extra summary 的 adapter-level direct assertion。
4. 完整 attack happy path 的 broader smoke test，覆蓋 pre / prevention / post follow-up 都不觸發的 vanilla case。

上述缺口不阻擋本階段，因為 service contract 已由 focused tests 鎖定呼叫順序與 result shape，代表性 production path 已由 integration baseline 覆蓋。

---

## 六、結論

`ATTACK_EFFECT_FOLLOWUP` 第一版已完成。

下一步建議進入：

- `AttackArtApplicationService` 第一版前置規劃

目標是盤點目前 `MatchActionService.attackArt(...)` 已完成的 cost / target / damage / damage apply / down / follow-up / pending / rest-payload / action-log / finish-check contract，決定是否可以開始收斂成完整 attack application service，或是否還需要先拆更小的剩餘副作用。
