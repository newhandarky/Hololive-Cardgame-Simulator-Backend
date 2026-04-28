# ATTACK_DOWN Acceptance Review

更新日期：2026-04-28
結論：通過，無 blocker

---

## 一、Review 範圍

本次 review 對照：

- `57-ATTACK_DOWN 前置拆分規劃.md`
- `AttackDownContext`
- `AttackDownResult`
- `AttackDownService`
- `AttackDownServiceTest`
- `MatchActionService.attackArt(...)` adapter bridge

目標是確認 `ATTACK_DOWN` 第一版是否已完成：

- down detection
- attacker side down preview
- art down triggered effect summary
- down event preview
- 回傳 `attackSummaryForTriggeredChecks`

本次不驗收 defender self-downed / ally-downed Gift follow-up 拆分。

---

## 二、完成條件檢查

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| 合併 `artSummary` 與 official extra / Oshi reactive summaries | PASS | `AttackDownService.resolveDown(...)` 先合併兩組 additional effects，再建立 `attackSummaryForTriggeredChecks`。 |
| 判斷 Holomem downed | PASS | `hasHolomemDowned(...)` 已搬入 `AttackDownService`，支援 top-level 與 nested `executedEffects`。 |
| 建立 attacker side `giftTriggeredEffects` | PASS | always preview `ART_USED`，downed 時額外 preview `OPPONENT_DOWNED`。 |
| 建立 `artDownTriggeredEffectSummary` | PASS | downed 時呼叫 `applyArtDownTriggeredEffects(...)`，no down 時回傳既有 no-op `ART_DOWNED_OPPONENT` summary。 |
| 建立 `downEventPreview` | PASS | 支援 top-level 與 nested `executedEffects` 的 deferred `downEvent`。 |
| 回傳 `attackSummaryForTriggeredChecks` | PASS | `AttackDownResult` 回傳給 `MatchActionService`，後續 defender follow-up / finish checks 沿用。 |
| adapter bridge | PASS | `MatchActionService.attackArt(...)` 已改呼叫 `AttackDownService.resolveDown(...)`。 |

---

## 三、Allow / Block 清單

### 已允許暫留

- `attackArt(...)` 仍是 `ATTACK_ART` 主流程入口。
- official card art extra / Oshi reactive call site 仍留在 `MatchActionService`。
- defender self-downed / ally-downed Gift follow-up 仍留在 `MatchActionService`。
- post-trigger pending interaction creation 仍留在 `MatchActionService`。
- finish condition evaluation 仍留在 `MatchActionService`。
- `extractDownEventPreview(...)` 與 `mergeEffectSummaryForChecks(...)` 因其他路徑仍使用，暫時保留在 `MatchActionService`。

### 已確認未做

- 未改 defender Gift follow-up timing。
- 未改 down event pending interaction timing。
- 未改 art down triggered effect payload key。
- 未改 finish condition evaluation。
- 未改 damage summary / damage application。
- 未搬 attacker rest / action log append。

---

## 四、測試覆蓋

Focused unit：

- `AttackDownServiceTest`
  - no down 時回傳 no-op art down summary
  - downed 時建立 downed opponent Gift preview
  - downed 時呼叫 art down triggered effect
  - nested downed summary detection
  - nested deferred down event preview extraction
  - null context guard

Integration baseline：

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

驗證命令：

- `./mvnw -q -Dtest=AttackDownServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldApplyDamageToOpponentHolomemAndRestAttacker+attackArtShouldTriggerDownedHolomemExtraLifeLoss+attackArtShouldTriggerOfficialExtraLifeLossForHbp02041WhenSelfDowned+attackArtShouldTriggerOfficialExtraLifeLossForHbp03022WhenSelfDowned+attackArtShouldTriggerOfficialGiftHsd08005WhenAllyDownedAndLifeIsNotHigher+attackArtShouldNotTriggerOfficialGiftHsd08005WhenOwnerLifeIsHigherThanOpponent+attackArtShouldTriggerOfficialGiftHsd09007WhenSelfDownedInCollabAndLifeIsLower+attackArtShouldTriggerOfficialExtraLifeLossForHbp03039WhenSelfDowned+attackArtShouldTriggerOfficialExtraLifeLossForHbp03083WhenSelfDowned+attackArtShouldTriggerOfficialGiftHbp05028WhenStageBotanDealsSpecialDamageThirtyOrMore test`

---

## 五、測試缺口

目前沒有 blocker。

可後續補強：

1. adapter bridge 的專用 integration assertion：確認 pending interaction payload 的 `downEventPreview` 與原行為一致。
2. official extra / Oshi reactive summary 同時造成 downed 的整合測試。
3. no opponent Holomem fallback life loss 情境下，確認 `AttackDownService` 不建立 downed opponent trigger。

上述缺口不阻擋本階段，因為現有 focused tests 已覆蓋 service contract，integration baseline 已覆蓋主要 down / self-downed / ally-downed 行為。

---

## 六、結論

`ATTACK_DOWN` 第一版已完成，可視為 defender Gift follow-up 前置拆分完成。

下一步建議進入：

- `ATTACK_DEFENDER_GIFT_FOLLOWUP` 前置拆分規劃

目標是把 defender self-downed / ally-downed Gift follow-up 從 `attackArt(...)` 再切出獨立 contract，並特別保護 snapshot timing 與 pending interaction timing。
