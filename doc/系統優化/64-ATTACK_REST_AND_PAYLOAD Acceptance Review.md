# ATTACK_REST_AND_PAYLOAD Acceptance Review

更新日期：2026-04-28
結論：通過，無 blocker

---

## 一、Review 範圍

本次 review 對照：

- `63-ATTACK_REST_AND_PAYLOAD 前置拆分規劃.md`
- `AttackRestAndPayloadContext`
- `AttackRestAndPayloadResult`
- `AttackRestAndPayloadService`
- `AttackRestAndPayloadServiceTest`
- `MatchActionService.attackArt(...)` adapter bridge

目標是確認 `ATTACK_REST_AND_PAYLOAD` 第一版是否已完成：

- attack art action payload 組裝
- attacker pending / defender pending decision 欄位回填
- finish check 用 `effectSummaryForChecks` 建立
- `attackArt(...)` adapter bridge
- 保留 attacker rest、phase save、action log、finish evaluator 與 enqueue interaction 的原呼叫點

本次不驗收 attacker rest DB update、match phase transition、action log writer、finish evaluator 或 life loss send cheer interaction 拆分。

---

## 二、完成條件檢查

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| 建立 attack art payload | PASS | `AttackRestAndPayloadService` 組裝 attacker / target / art / cost / damage / optional summaries / trigger summaries 等既有欄位。 |
| 回填 attacker pending decision 欄位 | PASS | service 寫入 `pendingInteractionDecisionId` / `pendingInteractionDecisionType`，並保留 `pendingLookTopDeckDecisionId` 相容欄位。 |
| 回填 defender pending decision 欄位 | PASS | service 寫入 `defenderPendingInteractionDecisionId` / `defenderPendingInteractionDecisionType`。 |
| 建立 finish check summary | PASS | service 回傳 `effectSummaryForChecks`，合併 art summary 與 additional effect summaries。 |
| adapter bridge | PASS | `MatchActionService.attackArt(...)` 已改呼叫 `AttackRestAndPayloadService.resolve(...)`，使用 result payload 寫 action log，使用 result summary 做 finish check。 |
| 保留副作用呼叫點 | PASS | attacker rest、phase transition / save、`appendAction(...)`、finish evaluator、enqueue interaction 仍留在 `attackArt(...)`。 |

---

## 三、Allow / Block 清單

### 已允許暫留

- attacker rest DB update 留在 `MatchActionService.attackArt(...)`。
- phase transition / repository save 留在 `MatchActionService.attackArt(...)`。
- `appendAction(...)` 留在 `MatchActionService`。
- `evaluateCardEffectMatchFinish(...)`、`evaluateLifeDefeat(...)`、`evaluateNoHolomemDefeat(...)` 留在 `MatchActionService`。
- `enqueueLifeLossSendCheerInteractions(...)` 留在 `MatchActionService`。
- `AttackRestAndPayloadService` 目前由 `MatchActionService` 直接建立；後續若整理 attack 子流程 registry，再評估 Spring bean 化。

### 已確認未做

- 未改 attacker rest timing。
- 未改 `ATTACK_ART` action type。
- 未改既有 payload key。
- 未改 pending decision payload key。
- 未改 finish check evaluation order：
  - card effect match finish
  - life defeat
  - no holomem defeat
- 未改 life loss send cheer enqueue timing。
- 未改 damage / down / Gift / pending decision 規則判斷。

---

## 四、測試覆蓋

Focused unit：

- `AttackRestAndPayloadServiceTest`
  - payload 保留 attacker / target / art / cost 基本欄位
  - damage payload fields 合併
  - optional summary 空值時略過非必要 key
  - optional summary 存在時寫入既有 key
  - attacker / defender pending decision key
  - `pendingLookTopDeckDecisionId` 相容欄位
  - finish check summary 合併
  - null context guard

Integration baseline：

- `attackArtShouldTriggerDownedHolomemExtraLifeLoss`
- `attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention`
- `attackArtShouldTriggerOfficialGiftHbp05028WhenStageBotanDealsSpecialDamageThirtyOrMore`
- `attackArtShouldTriggerOfficialGiftHbp06014AndSwapHolopowerPickWithHandCard`
- `attackArtShouldTriggerOfficialGiftHbp06027AndGrantExtraBloomAllowance`
- `attackArtShouldApplyOfficialPassiveGiftHbp04078ArtCostReductionOnCenterHolderSelf`

驗證命令：

- `./mvnw -q -Dtest=AttackRestAndPayloadServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerDownedHolomemExtraLifeLoss+attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention+attackArtShouldTriggerOfficialGiftHbp05028WhenStageBotanDealsSpecialDamageThirtyOrMore+attackArtShouldTriggerOfficialGiftHbp06014AndSwapHolopowerPickWithHandCard+attackArtShouldTriggerOfficialGiftHbp06027AndGrantExtraBloomAllowance+attackArtShouldApplyOfficialPassiveGiftHbp04078ArtCostReductionOnCenterHolderSelf test`

---

## 五、測試缺口

目前沒有 blocker。

可後續補強：

1. production payload snapshot assertion：確認 `ATTACK_ART` action payload 的 optional keys 在整合測試中完整保留。
2. finish check summary 對 life defeat / no holomem defeat 的 adapter-level assertion。
3. no optional summary 時 production payload 不帶非必要 key 的 assertion。

上述缺口不阻擋本階段，因為 service contract 已有 focused tests，代表性 attack art payload / pending / finish production path 已由 integration baseline 覆蓋。

---

## 六、結論

`ATTACK_REST_AND_PAYLOAD` 第一版已完成。

下一步建議進入：

- `ATTACK` pilot 啟動規劃，或先拆 `ATTACK_FINISH_CHECK` / `ATTACK_ACTION_LOG` 的更小前置段

以目前 `attackArt(...)` 剩餘內容來看，建議先建立完整 `ATTACK` pilot 規劃文件，盤點是否還需要把 finish checker / action log writer 作為 pilot 內的第一階段，而不是直接開始搬完整 attack main flow。
