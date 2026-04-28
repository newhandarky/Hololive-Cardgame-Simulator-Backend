# ATTACK_DEFENDER_GIFT_FOLLOWUP Acceptance Review

更新日期：2026-04-28
結論：通過，無 blocker

---

## 一、Review 範圍

本次 review 對照：

- `59-ATTACK_DEFENDER_GIFT_FOLLOWUP 前置拆分規劃.md`
- `AttackDefenderGiftFollowupContext`
- `AttackDefenderGiftFollowupResult`
- `AttackDefenderGiftFollowupService`
- `AttackOfficialOshiSelfDownedEffectService`
- `AttackDefenderGiftFollowupServiceTest`
- `MatchActionService.attackArt(...)` adapter bridge

目標是確認 `ATTACK_DEFENDER_GIFT_FOLLOWUP` 第一版是否已完成：

- defender self-downed / ally-downed follow-up resolution
- official Oshi self-downed summary
- self-downed Gift preview
- ally-downed Gift preview
- HBP01-124 Fan self-downed preview
- defender pending interaction source metadata

本次不驗收 defender Gift pending interaction creation 拆分。

---

## 二、完成條件檢查

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| 接收 downed target / holder snapshot / fan support snapshots | PASS | `AttackDefenderGiftFollowupContext` 已包含 target metadata、holder snapshot、fan support snapshots、art summary。 |
| 只有 attack downed 時才執行 defender follow-up | PASS | `resolveFollowup(...)` 在 `hasDownedHolomem=false` 時回傳空 result，且不呼叫 Oshi resolver / Gift preview。 |
| 建立 `officialOshiSelfDownedSummary` | PASS | `AttackOfficialOshiSelfDownedEffectService` 實作 HBP01-004 / HBP01-006 self-downed Oshi resolver。 |
| 建立 self-downed Gift preview | PASS | `AttackDefenderGiftFollowupService` 呼叫 `previewGiftTriggeredEffectsOnSelfDowned(...)`。 |
| 建立 ally-downed Gift preview | PASS | `AttackDefenderGiftFollowupService` 呼叫 `previewGiftTriggeredEffectsOnAllyDowned(...)`。 |
| 建立 HBP01-124 Fan self-downed preview | PASS | 已搬入 service，並由 focused test 覆蓋 holder cheer / stack snapshot payload。 |
| 回傳 `downedTargetCardId` / `downedTargetZone` | PASS | `AttackDefenderGiftFollowupResult` 回傳，`MatchActionService` 用於 defender Gift pending source card。 |
| adapter bridge | PASS | `MatchActionService.attackArt(...)` 已改呼叫 `AttackDefenderGiftFollowupService.resolveFollowup(...)`。 |

---

## 三、Allow / Block 清單

### 已允許暫留

- damage 前 snapshot 擷取仍留在 `MatchActionService`。
- pending interaction creation 仍留在 `MatchActionService`。
- `buildGiftTriggeredEffectDeferredSummary(...)` 仍留在 `MatchActionService`。
- `buildGiftTriggerInteractionCards(...)` 仍留在 `MatchActionService`。
- payload / finish checks 仍留在 `MatchActionService`。

### 已確認未做

- 未改 holder snapshot / fan support snapshot 擷取時間點。
- 未改 attacker side post-trigger pending 與 defender Gift pending 的建立順序。
- 未改 defender Gift confirm payload key。
- 未改 attacker rest timing。
- 未改 finish condition evaluation。
- 未改 Oshi / Gift / Fan 規則判斷。

---

## 四、測試覆蓋

Focused unit：

- `AttackDefenderGiftFollowupServiceTest`
  - no down 不觸發 follow-up
  - self-downed Gift preview
  - ally-downed Gift preview
  - HBP01-124 Fan snapshot payload
  - downed target metadata 保留
  - null context guard

Integration baseline：

- `attackArtShouldTriggerOfficialExtraLifeLossForHbp02041WhenSelfDowned`
- `attackArtShouldTriggerOfficialExtraLifeLossForHbp03022WhenSelfDowned`
- `attackArtShouldTriggerOfficialGiftHsd08005WhenAllyDownedAndLifeIsNotHigher`
- `attackArtShouldNotTriggerOfficialGiftHsd08005WhenOwnerLifeIsHigherThanOpponent`
- `attackArtShouldTriggerOfficialGiftHsd09007WhenSelfDownedInCollabAndLifeIsLower`
- `attackArtShouldTriggerOfficialExtraLifeLossForHbp03039WhenSelfDowned`
- `attackArtShouldTriggerOfficialExtraLifeLossForHbp03083WhenSelfDowned`
- `attackArtShouldTriggerOfficialGiftHbp05028WhenStageBotanDealsSpecialDamageThirtyOrMore`

驗證命令：

- `./mvnw -q -Dtest=AttackDefenderGiftFollowupServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerOfficialExtraLifeLossForHbp02041WhenSelfDowned+attackArtShouldTriggerOfficialExtraLifeLossForHbp03022WhenSelfDowned+attackArtShouldTriggerOfficialGiftHsd08005WhenAllyDownedAndLifeIsNotHigher+attackArtShouldNotTriggerOfficialGiftHsd08005WhenOwnerLifeIsHigherThanOpponent+attackArtShouldTriggerOfficialGiftHsd09007WhenSelfDownedInCollabAndLifeIsLower+attackArtShouldTriggerOfficialExtraLifeLossForHbp03039WhenSelfDowned+attackArtShouldTriggerOfficialExtraLifeLossForHbp03083WhenSelfDowned+attackArtShouldTriggerOfficialGiftHbp05028WhenStageBotanDealsSpecialDamageThirtyOrMore test`

---

## 五、測試缺口

目前沒有 blocker。

可後續補強：

1. `AttackOfficialOshiSelfDownedEffectService` 的 HBP01-004 / HBP01-006 focused database tests。
2. defender Gift pending interaction payload 的專用 integration assertion。
3. no down 時 defender Gift pending interaction 不建立的 adapter-level test。

上述缺口不阻擋本階段，因為 service contract 已有 focused tests，主要 self-downed / ally-downed production path 已由 integration baseline 覆蓋。

---

## 六、結論

`ATTACK_DEFENDER_GIFT_FOLLOWUP` 第一版已完成。

下一步建議進入：

- `ATTACK_POST_TRIGGER_PENDING` 前置拆分規劃

目標是把 attacker side post-trigger pending 與 defender Gift pending 的 summary / source card / pending decision 建立切出 contract，同時保留目前兩段 pending 的建立順序。
