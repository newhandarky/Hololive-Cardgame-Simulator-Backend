# ATTACK_DAMAGE Acceptance Review

更新日期：2026-04-28
定位：`ATTACK_DAMAGE` 前置拆分驗收 review
用途：對照 `53-ATTACK_DAMAGE 前置拆分規劃.md`，確認 attack art damage summary 子流程是否已可視為 down / life loss / Gift follow-up 前的穩定基準。

---

## 一、結論

`ATTACK_DAMAGE` 前置拆分目前可視為已通過階段性驗收。

理由：

1. damage context / result / service contract 已存在。
2. base damage parse、damage bonus、incoming reduction、critical 與 total damage summary 已集中到 `AttackDamageService`。
3. `MatchActionService.attackArt(...)` 已改成 adapter 呼叫 service，並保留既有 damage payload shape。
4. `artTotalDamage` 仍在 damage prevention Gift 後覆寫，保留舊 action payload 語意。
5. focused tests 與 damage modifier / reduction integration baseline 已覆蓋主要行為。
6. 本步沒有改 attack cost、target、damage prevention timing、apply damage、down / life loss 或 Gift follow-up。

---

## 二、完成條件對照

### 1. Contract / service skeleton

已完成：

- `AttackDamageContext`
- `AttackDamageResult`
- `AttackDamageService`
- `AttackDamageServiceTest`

目前 service 負責：

- parse base art damage
- resolve attached support art bonus
- resolve art text damage bonus
- 接收 Holox slot reveal art bonus
- resolve passive Gift art bonus
- resolve turn art damage modifier
- resolve critical color / bonus / applied
- resolve turn incoming damage reduction
- resolve passive Gift incoming damage reduction
- resolve attached support incoming damage reduction
- calculate incoming damage reduction total
- calculate total damage
- return payload-ready damage summary fields

判定：通過。

### 2. Adapter bridge

`MatchActionService.attackArt(...)` 目前保留：

- attack cost service 呼叫
- attack target service 呼叫
- Holox reveal / recovery / life loss 特殊處理
- damage prevention Gift trigger
- apply art damage
- 對手無 Holomem 時扣 LIFE fallback
- down / life loss follow-up
- post-trigger / defender Gift follow-up
- attacker rest
- legacy action log payload

已移出：

- base damage parse
- attached support art bonus summary
- art text damage bonus summary
- passive Gift art bonus summary
- turn art damage modifier summary
- critical color / bonus / applied
- turn incoming damage reduction summary
- passive Gift incoming damage reduction summary
- attached support incoming damage reduction summary
- incoming reduction total
- pre-prevention total damage
- damage payload field map

目前 payload 仍保留：

- `artBaseDamage`
- `attachedSupportArtBonus`
- `artTextDamageBonus`
- `holoxRevealArtBonus`
- `passiveGiftArtBonus`
- `turnArtDamageModifier`
- `criticalColor`
- `criticalBonus`
- `criticalApplied`
- `turnIncomingDamageReduction`
- `passiveGiftIncomingDamageReduction`
- `attachedSupportIncomingDamageReduction`
- `incomingDamageReduction`
- `artTotalDamage`

判定：通過。

---

## 三、舊入口 allow / block 清單對照

### 允許保留項

目前仍留在舊入口，且符合 `53` 文件允許範圍：

- `attackArt(...)` 仍是 `ATTACK_ART` 主流程入口。
- Holox slot reveal 的 reveal / recovery / life loss 特殊處理仍留在 `MatchActionService`，只把 `artBonus` 傳入 damage service。
- damage prevention Gift 仍留在 `MatchActionService`。
- `applyArtDamage(...)` 仍留在 `MatchActionService`。
- 對手無 Holomem 扣 LIFE fallback 仍留在 `MatchActionService`。
- down / life loss / Gift follow-up 仍留在 `MatchActionService`。
- payload 組裝仍由 `MatchActionService` 維持主體，damage 欄位透過 `AttackDamageResult.toPayloadFields()` 接入。

判定：通過。

### 不允許保留項

以下項目已不再位於舊入口主流程：

- base damage parse 留在 `MatchActionService.attackArt(...)`。
- critical color / bonus / applied 判斷留在 `MatchActionService.attackArt(...)`。
- turn art damage modifier 查詢留在 `MatchActionService`。
- incoming damage reduction 查詢留在 `MatchActionService`。
- damage summary payload key 由分散條件逐一手動組裝。
- 本前置拆分順手改 target 規則。
- 本前置拆分順手改 attack cost consume。
- 本前置拆分順手改 damage prevention Gift trigger timing。
- 本前置拆分順手改 apply damage / down / life loss。
- 本前置拆分改 damage payload key。

判定：通過。

---

## 四、測試覆蓋對照

### Focused unit tests

`AttackDamageServiceTest` 已覆蓋：

- critical color match applies bonus
- critical color mismatch does not apply
- attached support / art text / passive Gift bonus 與 incoming reduction 加總
- total damage 不低於 0
- malformed effect JSON damage parse fallback
- `baseDamage` JSON field parse
- no opponent Holomem 時不走 target-based reduction
- payload field map 保持既有 key

判定：通過。

### Integration baseline

已跑過的 damage modifier / reduction integration baseline：

- `attackArtShouldIncludeAttachedToolArtBonus`
- `attackArtShouldApplyIncomingDamageReductionFromTurnEffects`
- `attackArtShouldApplyOfficialPassiveGiftHsd08004ToTaggedDebutCollabHolomem`
- `attackArtShouldApplyOfficialPassiveGiftHbp05013WhenCollabHolderBuffsCenterHolomem`
- `attackArtShouldApplyOfficialPassiveGiftHbp02009ArtBonusWhenTargetHasMascotAttached`
- `attackArtShouldApplyOfficialPassiveGiftHsd07009DamageReductionOnCenter`
- `attackArtShouldApplyOfficialPassiveGiftHbp05065DamageReductionFortyWhenDiceOdd`
- `attackArtShouldApplyOfficialPassiveGiftHbp04068DamageReductionOnCenterAgainstOpponentFirst`
- `attackArtShouldApplyOfficialPassiveGiftHbp06082DamageReductionToAncientWeaponCenterWhenGuestOshiIsAnya`
- `attackArtShouldApplyOfficialArtBonusHsd07009WhenLifeIsThreeOrLess`
- `attackArtShouldApplyOfficialArtBonusHbp05050WhenMococoArtUsedAndReferencedOshiSkillUsedThisTurn`
- `attackArtShouldApplyOfficialSpecialDamageBonusHbp05045WhenAttackerIsOkayuAndTargetIsCenter`

判定：通過。

---

## 五、測試缺口

目前沒有 blocker。

仍可後續補強：

1. turn art damage modifier 的 focused JDBC mock test。
2. turn incoming damage reduction 的 focused JDBC mock test。
3. critical text 從 `rawHeader` / `rawEffect` 解析的 focused test。
4. damage prevention Gift 後 `artTotalDamage` 覆寫語意的專門 integration test。

這些缺口已有 integration baseline 或既有 payload 行為保護，不阻塞本次前置拆分驗收。

---

## 六、風險與暫留技術債

1. `ATTACK_ART` 主流程仍然很大。
   - 本次只拆 damage summary，apply damage、down / life loss 與 Gift follow-up 尚未拆。
2. `AttackDamageService` 目前仍直接使用 JDBC 查詢 turn effects。
   - 這符合前置拆分階段；後續可再抽 query bridge 或 repository。
3. `loadPrimaryArt(...)` 仍在 `MatchActionService`，但已委派 `AttackDamageService.resolveArtDamage(...)` 判斷可造成傷害的藝能。
   - 若後續拆 `ATTACK_ART` action resolution，可再把 art selection 一併收斂。
4. `artTotalDamage` 同時出現在 damage summary 與 prevention 後覆寫。
   - 這是為了保留既有 payload contract；若未來需要同時曝光 pre-prevention / post-prevention damage，需另行設計 contract。

判定：可接受。

---

## 七、下一步建議

下一步建議進 `ATTACK_DAMAGE_APPLY` 或 `ATTACK_DOWN` 前置拆分規劃。

建議順序：

1. 先盤點 `matchEffectDamageService.applyArtDamage(...)` 之後的 down / life loss / Gift follow-up 區塊。
2. 若目標是繼續降低 `attackArt(...)`，優先規劃 `ATTACK_DAMAGE_APPLY` adapter bridge。
3. 不建議同一步同時搬 defender Gift follow-up，避免改變 down trigger timing。
