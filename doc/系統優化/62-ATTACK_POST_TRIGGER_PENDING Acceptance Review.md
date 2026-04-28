# ATTACK_POST_TRIGGER_PENDING Acceptance Review

更新日期：2026-04-28
結論：通過，無 blocker

---

## 一、Review 範圍

本次 review 對照：

- `61-ATTACK_POST_TRIGGER_PENDING 前置拆分規劃.md`
- `AttackPendingDecision`
- `AttackPostTriggerPendingContext`
- `AttackPostTriggerPendingResult`
- `AttackPostTriggerPendingService`
- `AttackPostTriggerPendingServiceTest`
- `MatchActionService.attackArt(...)` adapter bridge

目標是確認 `ATTACK_POST_TRIGGER_PENDING` 第一版是否已完成：

- attacker side post-trigger summary
- attacker side `ATTACK_ART_POST_TRIGGER` pending decision
- defender side Gift deferred summary
- defender side `GIFT` pending decision
- attacker pending 先於 defender pending 的建立順序
- `attackArt(...)` payload 所需欄位回填

本次不驗收 attacker rest、action log、finish condition evaluation 或 pending interaction resolution 拆分。

---

## 二、完成條件檢查

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| 建立 attacker side `postTriggerEffectSummary` | PASS | `AttackPostTriggerPendingService` 已建立 `sourceActionType=ATTACK_ART_POST_TRIGGER`、`deferred`、`triggeredGifts`、`downEvent`、`triggerSections`、`requestedEffects` 等 payload 欄位。 |
| Gift / down event 存在時建立 attacker pending | PASS | `resolvePending(...)` 在 attacker Gift 或 down event 存在時呼叫 `createAttackPostTriggerPending(...)`。 |
| 建立 defender side `defenderGiftEffectSummary` | PASS | service 已建立 `sourceActionType=GIFT`、`deferred`、`triggeredGifts`、`requestedEffects` 等欄位。 |
| defender Gift 存在時建立 defender pending | PASS | `resolvePending(...)` 在 defender Gift 不為空時呼叫 `createDefenderGiftPending(...)`。 |
| 保留 attacker pending 先於 defender pending | PASS | focused test 覆蓋呼叫順序，production bridge 也依 result 順序回填。 |
| 回傳 payload 所需 decision id / type | PASS | `AttackPendingDecision` 承接 id / type，`MatchActionService` 轉回 `FollowupInteractionDecision` 後寫入既有 payload key。 |
| adapter bridge | PASS | `MatchActionService.attackArt(...)` 已改呼叫 `AttackPostTriggerPendingService.resolvePending(...)`。 |

---

## 三、Allow / Block 清單

### 已允許暫留

- pending interaction payload 實際寫入仍由 `MatchActionService` 既有 private helper 處理。
- `buildGiftTriggerInteractionCards(...)`、`createAttackArtPostTriggerConfirmPendingInteraction(...)`、`createGiftTriggeredEffectConfirmPendingInteraction(...)` 仍留在 `MatchActionService`。
- attacker rest 仍留在 `MatchActionService.attackArt(...)`。
- action payload 完整組裝仍留在 `MatchActionService.attackArt(...)`。
- action log append 與 finish condition evaluation 仍留在 `MatchActionService`。
- `AttackPostTriggerPendingService` 目前由 `MatchActionService` 直接建立，透過 adapter 委派既有 helper；等 pending payload helper 再拆出後再評估 Spring bean 化。

### 已確認未做

- 未改 attacker pending 與 defender pending 的建立順序。
- 未改 `postTriggerEffects` / `defenderGiftEffects` payload key。
- 未改 `pendingInteractionDecisionId` / `defenderPendingInteractionDecisionId` payload key。
- 未改 decision type：
  - attacker side 仍為 `ATTACK_ART_POST_TRIGGER`
  - defender side 仍為 `GIFT`
- 未改 attacker rest timing。
- 未改 pending resolution 行為。
- 未改 damage / down / Gift preview 規則判斷。

---

## 四、測試覆蓋

Focused unit：

- `AttackPostTriggerPendingServiceTest`
  - no attacker Gift / no down event 時建立 non-deferred summaries
  - attacker Gift 存在時建立 attacker pending
  - down event 存在時建立 attacker pending
  - defender Gift 存在時建立 defender pending
  - attacker pending 先於 defender pending 建立
  - null context guard

Integration baseline：

- `attackArtShouldTriggerDownedHolomemExtraLifeLoss`
- `attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention`
- `attackArtShouldTriggerOfficialGiftHsd08005WhenAllyDownedAndLifeIsNotHigher`
- `attackArtShouldTriggerOfficialGiftHsd09007WhenSelfDownedInCollabAndLifeIsLower`
- `attackArtShouldTriggerOfficialGiftHbp05028WhenStageBotanDealsSpecialDamageThirtyOrMore`
- `attackArtShouldTriggerOfficialGiftHbp06014AndSwapHolopowerPickWithHandCard`
- `attackArtShouldTriggerOfficialGiftHbp06027AndGrantExtraBloomAllowance`

驗證命令：

- `./mvnw -q -Dtest=AttackPostTriggerPendingServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerDownedHolomemExtraLifeLoss+attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention+attackArtShouldTriggerOfficialGiftHsd08005WhenAllyDownedAndLifeIsNotHigher+attackArtShouldTriggerOfficialGiftHsd09007WhenSelfDownedInCollabAndLifeIsLower+attackArtShouldTriggerOfficialGiftHbp05028WhenStageBotanDealsSpecialDamageThirtyOrMore+attackArtShouldTriggerOfficialGiftHbp06014AndSwapHolopowerPickWithHandCard+attackArtShouldTriggerOfficialGiftHbp06027AndGrantExtraBloomAllowance test`

---

## 五、測試缺口

目前沒有 blocker。

可後續補強：

1. adapter-level assertion：確認 `postTriggerEffects.triggerSections` 在 production payload 內維持既有形狀。
2. defender Gift pending interaction source cards 的專用 integration assertion。
3. no Gift / no down event 時不建立 attacker pending 的 production-path assertion。

上述缺口不阻擋本階段，因為 service contract 已有 focused tests，代表性 attack post-trigger / defender Gift pending production path 已由 integration baseline 覆蓋。

---

## 六、結論

`ATTACK_POST_TRIGGER_PENDING` 第一版已完成。

下一步建議進入：

- `ATTACK_REST_AND_PAYLOAD` 前置拆分規劃

目標是把 `attackArt(...)` 中剩餘的 attacker rest、payload decision 欄位、action log 與 finish check 邊界收斂，為後續 `ATTACK` 主流程拆分做最後一段前置整理。
