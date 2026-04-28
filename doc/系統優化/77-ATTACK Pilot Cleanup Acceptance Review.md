# ATTACK Pilot Cleanup Acceptance Review

更新日期：2026-04-28
結論：通過，無 blocker

---

## 一、Review 範圍

本次 review 對照：

- `65-ATTACK Pilot 啟動規劃.md`
- `73-AttackArtApplicationService Acceptance Review.md`
- `75-AttackArtApplicationAdapter Acceptance Review.md`
- `76-AttackArtApplicationAdapter Cleanup Acceptance Review.md`
- `AttackActionLogService`
- `AttackActionWriterAdapter`
- `AttackArtApplicationAdapterFactory`
- `MatchActionService.attackArt(...)`
- `MatchActionServiceIntegrationTest`

目標是確認 AAA-18 到 AAA-22 的 attack pilot cleanup 是否已完成：

- 移除 adapter dependencies port
- 鎖定 defender damage prevention `GIFT_TRIGGER` / `ATTACK_ART` action order
- 重跑 broad attack integration baseline
- 搬出 attack action writer adapter
- 確認沒有新的 production blocker

---

## 二、完成條件檢查

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| dependencies port 移除 | PASS | `AttackArtApplicationAdapterDependencies` 已刪除，factory constructor 不再接 bridge。 |
| `GIFT_TRIGGER` writer 收斂 | PASS | defender damage prevention 已改走 `AttackActionLogService.appendGiftTrigger(...)`。 |
| action order regression | PASS | HBP01-027 integration test 已確認 `GIFT_TRIGGER` action order 早於對應 `ATTACK_ART`。 |
| broad baseline | PASS | Holox / official art extra / Oshi reactive / pending follow-up 代表性 integration path 已重跑通過。 |
| writer adapter | PASS | `AttackActionWriterAdapter` 已取代 `MatchActionService.AttackArtActionWriter`。 |
| `MatchActionService` 邊界 | PASS | `attackArt(...)` 仍保留 transaction / loading / legality / life loss send cheer enqueue。 |
| application contract | PASS | 未改 `AttackArtApplicationService` public contract。 |

---

## 三、Allow / Block 清單

### 已允許暫留

- `MatchActionService` 保留 transaction boundary。
- `MatchActionService` 保留 ActionContext loading / pending guard。
- `MatchActionService` 保留 attack legality / attacker loading / art metadata loading。
- `MatchActionService` 保留 life loss send cheer enqueue。
- `MatchActionService.appendAction(...)` 仍保留給其他 use case。
- `AttackActionWriterAdapter` 直接使用 `MatchActionRepository` 寫 attack action log。
- `AttackArtApplicationAdapterFactory` 仍直接使用 `JdbcTemplate` rest SQL。
- `AttackArtApplicationAdapterFactory` 仍直接使用 `MatchRepository` 做 phase save。
- `AttackApplicationRestPayloadStage` 仍以 nested record 暴露給 `MatchActionService.attackArt(...)` 讀取 finish summary。

### 已確認未做

- 未改 action order 計算公式。
- 未改 `ATTACK_ART` / `GIFT_TRIGGER` action type。
- 未改 payload key / payload shape。
- 未改 pending interaction timing。
- 未改 finish check timing。
- 未改 rest SQL 條件。
- 未改 phase transition timing。
- 未重寫單卡效果規則。

---

## 四、測試覆蓋

Focused unit：

- `AttackActionLogServiceTest`
- `AttackActionWriterAdapterTest`
- `AttackArtApplicationAdapterFactoryTest`

Regression integration：

- `attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention`

Broad attack baseline：

- `attackArtShouldTriggerOfficialGiftHbp02039WhenHoloxSlotRevealsSupport`
- `attackArtShouldTriggerOfficialGiftHbp02040WhenHoloxSlotRevealsSameBloomLevelMembers`
- `attackArtShouldTriggerOfficialArtExtraEffectHbp01087AndDealSpecialDamageToAllOpponentBackHolomems`
- `attackArtShouldTriggerOfficialOshiHbp01007WhenBlueHolomemDamagesOpponentBack`
- `attackArtShouldTriggerOfficialOshiHbp01008WhenBlueAbilityArchivesCheer`
- `attackArtShouldQueueTriggerConfirmForOfficialGiftHbp06014OnArtUsed`
- `attackArtShouldTriggerOfficialGiftHbp06027AndGrantExtraBloomAllowance`

Compile：

- `./mvnw -q -DskipTests compile`

---

## 五、測試缺口

目前沒有 blocker。

可後續補強：

1. 把 attack broad baseline 拆成較小、命名明確的 regression suite。
2. 若後續要讓所有 action log 共用 writer adapter，需要先建立跨 use case action order baseline。
3. 若要繼續收斂 `MatchActionService.appendAction(...)`，需先盤點 BLOOM / COLLAB / PLAY_CARD / support / turn lifecycle 的 action writer 差異。

---

## 六、結論

ATTACK pilot cleanup 已完成。

下一步建議：

- 先做 code review / commit checkpoint。
- 後續不要直接擴大改全域 action writer。
- 建議回到下一條 legacy boundary cleanup，優先評估 `MatchActionService` 中仍大量使用的共用 helper：
  - non-attack `toJson(...)`
  - non-attack `touchUpdatedAt(...)`
  - non-attack `appendGiftTriggerActionsIfPresent(...)`
