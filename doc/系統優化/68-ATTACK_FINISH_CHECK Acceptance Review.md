# ATTACK_FINISH_CHECK Acceptance Review

更新日期：2026-04-28
結論：通過，無 blocker

---

## 一、Review 範圍

本次 review 對照：

- `67-ATTACK_FINISH_CHECK 前置拆分規劃.md`
- `AttackFinishCheckContext`
- `AttackFinishCheckResult`
- `AttackFinishCheckService`
- `AttackFinishCheckServiceTest`
- `MatchActionService.attackArt(...)` adapter bridge

目標是確認 `ATTACK_FINISH_CHECK` 第一版是否已完成：

- finish evaluator 呼叫順序
- life reduced / holomem downed predicate gate
- finish 後 save adapter
- `attackArt(...)` adapter bridge
- 保留 life loss send cheer enqueue timing

本次不驗收 card effect finish、life defeat、no holomem defeat 的內部 SQL / 規則拆分。

---

## 二、完成條件檢查

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| 先呼叫 card effect finish evaluator | PASS | `AttackFinishCheckService.resolve(...)` 第一段呼叫 card effect evaluator，focused test 覆蓋順序。 |
| card effect finish 後停止後續 evaluator | PASS | focused test 驗證 life / no holomem evaluator 不會被呼叫。 |
| life reduced 為 true 才呼叫 life evaluator | PASS | `lifeReducedPredicate` gate 已落地，focused test 覆蓋 skip path。 |
| life defeat finish 後停止 no holomem evaluator | PASS | focused test 驗證 life finish 後不呼叫 holomem predicate / evaluator。 |
| holomem downed 為 true 才呼叫 no holomem evaluator | PASS | `holomemDownedPredicate` gate 已落地，focused test 覆蓋 skip path。 |
| finish 後呼叫 saver | PASS | service 透過 `MatchSaver` adapter 呼叫 save，focused test 覆蓋。 |
| adapter bridge | PASS | `MatchActionService.attackArt(...)` 已改呼叫 `AttackFinishCheckService.resolve(...)`。 |

---

## 三、Allow / Block 清單

### 已允許暫留

- `evaluateCardEffectMatchFinish(...)` 留在 `MatchActionService`。
- `evaluateLifeDefeat(...)` 留在 `MatchActionService`。
- `evaluateNoHolomemDefeat(...)` 留在 `MatchActionService`。
- `hasLifeReduced(...)` / `hasHolomemDowned(...)` 留在 `MatchActionService`。
- `touchUpdatedAt(...)` / `matchRepository.saveAndFlush(...)` 透過 `saveFinishedMatch(...)` adapter 委派。
- `enqueueLifeLossSendCheerInteractions(...)` 留在 finish check 後。

### 已確認未做

- 未改 finish check evaluation order。
- 未改 life defeat predicate gate。
- 未改 no holomem defeat predicate gate。
- 未改 finish 後 save timing。
- 未改 life loss send cheer enqueue timing。
- 未改 card effect / life / no holomem 的實際勝負規則。

---

## 四、測試覆蓋

Focused unit：

- `AttackFinishCheckServiceTest`
  - card effect finish 時停止後續 evaluator
  - no life reduced 時不呼叫 life evaluator
  - life defeat finish 時停止 no holomem evaluator
  - no holomem downed 時不呼叫 no holomem evaluator
  - no holomem defeat finish result
  - evaluator 呼叫順序
  - null context guard

Integration baseline：

- `attackArtShouldTriggerDownedHolomemExtraLifeLoss`
- `attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention`
- `attackArtShouldTriggerOfficialGiftHbp05028WhenStageBotanDealsSpecialDamageThirtyOrMore`

驗證命令：

- `./mvnw -q -Dtest=AttackFinishCheckServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerDownedHolomemExtraLifeLoss+attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention+attackArtShouldTriggerOfficialGiftHbp05028WhenStageBotanDealsSpecialDamageThirtyOrMore test`

---

## 五、測試缺口

目前沒有 blocker。

可後續補強：

1. production-path assertion：finish 後只 save 一次。
2. card effect match result direct finish 的 attack art integration case。
3. life reduced false 時不觸發 life defeat evaluator 的 adapter-level assertion。

上述缺口不阻擋本階段，因為 service contract 已由 focused tests 鎖定順序，代表性 attack finish production path 已由 integration baseline 覆蓋。

---

## 六、結論

`ATTACK_FINISH_CHECK` 第一版已完成。

下一步建議進入：

- `ATTACK_EFFECT_FOLLOWUP` 前置拆分

目標是盤點 Holox / HBP02-039 / HBP02-040 / defender damage received Gift / official card art extra / official Oshi art reactive 等尚留在 `attackArt(...)` 的 attack special effect follow-up，先做規劃再拆最小 service。
