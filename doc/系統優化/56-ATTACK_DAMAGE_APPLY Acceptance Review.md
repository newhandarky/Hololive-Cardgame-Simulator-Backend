# ATTACK_DAMAGE_APPLY Acceptance Review

更新日期：2026-04-28
定位：`ATTACK_DAMAGE_APPLY` 前置拆分驗收 review
用途：對照 `55-ATTACK_DAMAGE_APPLY 前置拆分規劃.md`，確認 attack art damage application 子流程是否已可視為 down / Gift follow-up 前的穩定基準。

---

## 一、結論

`ATTACK_DAMAGE_APPLY` 前置拆分目前可視為已通過階段性驗收。

理由：

1. damage application context / result / service contract 已存在。
2. final damage apply、prevented no-op 與 no opponent life fallback 已集中到 `AttackDamageApplicationService`。
3. `MatchActionService.attackArt(...)` 已改成 adapter 呼叫 service，並保留 `artSummary` / `lostLifeCardInstanceId` 語意。
4. damage prevention Gift 與後續 down / Gift follow-up 順序沒有移動。
5. focused tests 與 damage apply / prevention / down integration baseline 已覆蓋主要行為。
6. 本步沒有改 attack cost、target、damage summary、damage prevention trigger timing、down event timing 或 finish checks。

---

## 二、完成條件對照

### 1. Contract / service skeleton

已完成：

- `AttackDamageApplicationContext`
- `AttackDamageApplicationResult`
- `AttackDamageApplicationService`
- `AttackDamageApplicationServiceTest`

目前 service 負責：

- 對手有 Holomem 且 final damage > 0 時呼叫 `MatchEffectDamageService.applyArtDamage(..., true)`
- 對手有 Holomem 且 final damage <= 0 時建立 `ART_DAMAGE_PREVENTED` no-op summary
- 對手沒有 Holomem 時透過 `GameActionExecutor + ReduceLifeAction` 扣 1 點 LIFE
- 建立 `ART_DAMAGE_FALLBACK` summary
- 對手沒有可失去 LIFE 時保留既有錯誤
- 回傳 `artSummary` 與 `lostLifeCardInstanceId`

判定：通過。

### 2. Adapter bridge

`MatchActionService.attackArt(...)` 目前保留：

- damage summary result
- damage prevention Gift trigger
- `damageAfter` 覆寫 `totalDamage`
- official card art extra effects
- official Oshi art reactive effects
- attack summary merge
- downed opponent / self-downed / ally-downed Gift preview
- art down triggered effects
- pending interaction creation
- attacker rest
- action payload / finish checks / life loss send cheer interactions

已移出：

- `applyArtDamage(..., true)` call site
- `ART_DAMAGE_PREVENTED` no-op summary 組裝
- no opponent Holomem 的 `loseLifeOnce(...)` call site
- `ART_DAMAGE_FALLBACK` summary 組裝
- direct `MatchEffectDamageService` dependency

目前 payload 仍保留：

- `effect`
- `lostLifeCardInstanceId`
- `artTotalDamage`
- `defenderDamageReceivedGift`

判定：通過。

---

## 三、舊入口 allow / block 清單對照

### 允許保留項

目前仍留在舊入口，且符合 `55` 文件允許範圍：

- `attackArt(...)` 仍是 `ATTACK_ART` 主流程入口。
- damage prevention Gift 仍留在 `MatchActionService`。
- `totalDamage` 仍由 `MatchActionService` 在 prevention 後覆寫。
- official card art extra / Oshi reactive 仍留在 `MatchActionService`。
- down / life loss / Gift follow-up 仍留在 `MatchActionService`。
- finish condition evaluation 仍留在 `MatchActionService`。
- `loseLifeOnce(...)` helper 仍留在 `MatchActionService`，供其他既有效果使用。

判定：通過。

### 不允許保留項

以下項目已不再位於 `attackArt(...)` 主流程：

- damage application 分支直接呼叫 `MatchEffectDamageService.applyArtDamage(...)`。
- `ART_DAMAGE_PREVENTED` payload 在 `attackArt(...)` 內手動組裝。
- no opponent Holomem fallback 在 `attackArt(...)` 內直接呼叫 `loseLifeOnce(...)`。
- `ART_DAMAGE_FALLBACK` payload 在 `attackArt(...)` 內手動組裝。
- 本前置拆分順手改 damage prevention Gift trigger timing。
- 本前置拆分順手改 down event timing。
- 本前置拆分順手改 defender Gift follow-up timing。
- 本前置拆分改 `ART_DAMAGE_PREVENTED` / `ART_DAMAGE_FALLBACK` payload key。
- 本前置拆分改 finish condition evaluation。
- 本前置拆分改 attack cost / target / damage summary。

判定：通過。

---

## 四、測試覆蓋對照

### Focused unit tests

`AttackDamageApplicationServiceTest` 已覆蓋：

- has opponent Holomem 且 damage > 0 時呼叫 `applyArtDamage(..., true)`
- has opponent Holomem 且 damage <= 0 時回傳 `ART_DAMAGE_PREVENTED`
- no opponent Holomem 時回傳 `ART_DAMAGE_FALLBACK`
- no opponent Holomem 時帶出 `lostLifeCardInstanceId`
- `GameActionExecutor` 回傳字串型 card instance id 時仍可解析
- no opponent Holomem 且沒有 LIFE 可扣時拋出既有錯誤

判定：通過。

### Integration baseline

已跑過的 damage apply / prevention / down integration baseline：

- `attackArtShouldApplyDamageToOpponentHolomemAndRestAttacker`
- `attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention`
- `attackArtShouldNotPreventDamageWithOfficialGiftHbp01027WhenDiceConditionFailed`
- `attackArtShouldTriggerOfficialGiftHbp05069PreventDamageWhenHolderIsBack`
- `attackArtShouldNotTriggerOfficialGiftHbp05069PreventDamageWhenHolderIsNotBack`
- `attackArtShouldPreventDamageByOfficialGiftHbp06039WhenOwnCollabExistsAndOpponentCollabMissing`
- `attackArtShouldNotPreventDamageByOfficialGiftHbp06039WhenOpponentHasCollab`
- `attackArtShouldTriggerDownedHolomemExtraLifeLoss`
- `attackArtShouldTriggerOfficialExtraLifeLossForHbp02041WhenSelfDowned`
- `attackArtShouldTriggerOfficialExtraLifeLossForHbp03022WhenSelfDowned`

判定：通過。

---

## 五、測試缺口

目前沒有 blocker。

仍可後續補強：

1. no opponent Holomem fallback 的 full integration test，直接檢查 `ART_DAMAGE_FALLBACK` action payload。
2. prevention 後 `ART_DAMAGE_PREVENTED` payload 的 full integration test。
3. `AttackDamageApplicationService` 對 `EffectContext` / `ReduceLifeAction` 參數的 captor test。

這些缺口已有 focused tests 或 prevention / down baseline 保護，不阻塞本次前置拆分驗收。

---

## 六、風險與暫留技術債

1. `ATTACK_ART` 主流程仍保留 down / life loss / Gift follow-up。
   - 本次只拆 damage application result，不改 trigger timing。
2. `AttackDamageApplicationService` 目前直接使用 `GameActionExecutor` 執行 life fallback。
   - 這是沿用舊 `loseLifeOnce(...)` 語意；後續若有多處共用，可再抽 life loss helper。
3. `lostLifeCardInstanceId` 仍是後續 payload 與 finish checks 的橋接欄位。
   - 若未來改成多 life loss summary，需另行調整 contract。

判定：可接受。

---

## 七、下一步建議

下一步建議進 `ATTACK_DOWN` 前置拆分規劃。

建議順序：

1. 先盤點 `attackSummaryForTriggeredChecks`、`hasHolomemDowned(...)` 與 down event preview 區塊。
2. 先拆 down detection / down event preview / art down triggered effect summary，不直接搬 defender Gift follow-up。
3. defender self-downed / ally-downed Gift follow-up 另外成下一條子流程，避免改變 pending interaction timing。
