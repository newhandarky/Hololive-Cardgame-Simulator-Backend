# ATTACK_DAMAGE 前置拆分規劃

更新日期：2026-04-28
定位：`ATTACK_TARGET` 前置拆分完成後、拆 down / life loss / Gift follow-up 之前的前置規劃
用途：先把 attack art 的 damage modifier / reduction summary 邊界切出來，避免下一步直接搬移傷害套用與 down 後副作用。

---

## 一、為什麼先做 attack damage summary

`ATTACK_ART` 目前已先後拆出：

1. `ATTACK_COST`
2. `ATTACK_TARGET`

下一段最適合拆的是 damage summary，而不是完整 damage application。

原因：

1. damage summary 是純計算與查詢協調，邊界比 apply damage / down follow-up 小。
2. action payload 已有清楚的 damage 欄位，可作為相容 contract。
3. 既有 integration tests 大量檢查 `artTotalDamage` 與各種 bonus / reduction 欄位。
4. `matchEffectDamageService.applyArtDamage(...)` 後面會牽涉 down / life loss / Gift follow-up，不應和 modifier summary 同一步拆。

---

## 二、目前位置

主要 production 邏輯位於 `MatchActionService.attackArt(...)`：

- `baseDamage = resolveArtDamage(...)`
- `attachedSupportArtBonus = matchEffectCombatModifierService.resolveAttachedSupportArtBonus(...)`
- `artTextDamageBonus = matchEffectCombatModifierService.resolveArtTextDamageBonus(...)`
- `holoxSlotRevealSummary = resolveHoloxSlotRevealSummary(...)`
- `turnArtDamageModifier = resolveTurnArtDamageModifier(...)`
- `artCritical = resolveArtCritical(...)`
- `turnIncomingDamageReduction = resolveIncomingDamageReduction(...)`
- `passiveGiftIncomingDamageReduction = matchEffectCombatModifierService.resolvePassiveGiftIncomingDamageReduction(...)`
- `attachedSupportIncomingDamageReduction = matchEffectCombatModifierService.resolveAttachedSupportIncomingDamageReduction(...)`
- `passiveGiftArtBonus = matchEffectCombatModifierService.resolvePassiveGiftArtBonus(...)`
- `incomingDamageReduction = turn + passiveGift + attachedSupport`
- `totalDamage = max(base + bonuses - reductions, 0)`

目前 helper：

- `resolveTurnArtDamageModifier(...)`
- `resolveIncomingDamageReduction(...)`
- `resolveArtCritical(...)`
- `mapJapaneseColorToken(...)`
- `resolveArtDamage(...)`
- `extractFirstNumber(...)`

目前仍留在同一段、但第一版不應搬的內容：

- `matchTriggeredCombatEffectService.resolveTriggeredGiftDamagePrevention(...)`
- `matchEffectDamageService.applyArtDamage(...)`
- 對手無 Holomem 時扣 LIFE
- down / life loss
- post-trigger / defender Gift follow-up

---

## 三、第一版目標

第一版只拆出 damage summary，不搬 damage application。

應覆蓋：

1. base damage parse。
2. attached support art bonus。
3. art text damage bonus。
4. Holox slot reveal art bonus 的數值接入。
5. passive Gift art bonus。
6. turn art damage modifier。
7. critical color / bonus / applied。
8. turn incoming damage reduction。
9. passive Gift incoming damage reduction。
10. attached support incoming damage reduction。
11. incoming damage reduction total。
12. total damage。
13. 既有 payload 欄位相容。

建議新增：

- `AttackDamageContext`
- `AttackDamageResult`
- `AttackDamageService`
- `AttackDamageServiceTest`

第一版 `AttackDamageService` 可以呼叫既有：

- `MatchEffectCombatModifierService`
- `JdbcTemplate`
- `ObjectMapper`

但不應呼叫：

- `MatchEffectDamageService.applyArtDamage(...)`
- `MatchTriggeredCombatEffectService.resolveTriggeredGiftDamagePrevention(...)`

---

## 四、責任邊界

### `AttackDamageService`

應負責：

- parse base art damage
- resolve all additive art bonuses
- resolve all incoming reductions
- resolve critical text and target color match
- calculate total damage
- return payload-ready damage summary

不應負責：

- attack cost
- attack target
- damage prevention Gift trigger
- apply damage mutation
- life loss fallback
- down event
- post-trigger follow-up
- rest attacker
- write action log

### `MatchActionService.attackArt(...)`

第一版應只改成：

- 保留 cost service 呼叫
- 保留 target service 呼叫
- 呼叫 `AttackDamageService.resolveDamage(...)`
- 使用 result 提供的：
  - total damage
  - damage payload fields
  - critical payload fields
- 保留 damage prevention / apply damage / down / Gift follow-up

---

## 五、輸入 / 輸出草案

### Input

`AttackDamageContext` 至少包含：

- `matchId`
- `attackerUserId`
- `opponentUserId`
- `turnNumber`
- `attackerHolomemId`
- `attackerLevel`
- `target`
- `hasOpponentHolomem`
- `artEffectJsonText`
- `holoxRevealArtBonus`

可選：

- `artName`
  - 若未來 bonus 判斷需要 art name，可先保留

### Output

`AttackDamageResult` 至少包含：

- `baseDamage`
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
- `totalDamage`

可提供：

- `toPayloadFields()`
  - 回傳上述 payload 欄位 map
  - `MatchActionService` 可直接 putAll，降低欄位漏接風險

---

## 六、現有測試基準

目前已有代表性 integration：

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

第一版新增 focused tests 應覆蓋：

1. base damage parse fallback。
2. malformed effect JSON 的 damage parse fallback。
3. critical color match applies bonus。
4. critical color mismatch does not apply.
5. total damage 不低於 0。
6. incoming damage reduction total 正確相加。
7. no opponent Holomem 時不查 target-based reductions。
8. payload field map 保持既有 key。

---

## 七、允許暫留

第一版允許：

- `attackArt(...)` 仍是 `ATTACK_ART` 主流程入口。
- Holox slot reveal 的 reveal / recovery / life loss 特殊處理先留在 `MatchActionService`，只把 `artBonus` 傳入 damage service。
- damage prevention Gift 仍留在 `MatchActionService`。
- `applyArtDamage(...)` 仍留在 `MatchActionService`。
- 對手無 Holomem 扣 LIFE fallback 仍留在 `MatchActionService`。
- down / life loss / Gift follow-up 仍留在 `MatchActionService`。
- payload 組裝可先由 `MatchActionService` `putAll(result.toPayloadFields())` 完成。

第一版不允許：

- 順手改 target 規則。
- 順手改 attack cost consume。
- 順手改 damage prevention Gift trigger timing。
- 順手改 apply damage / down / life loss。
- 順手改 damage payload key。

---

## 八、建議施工順序

### Step AD-1：contract / service skeleton

- 新增 damage context / result 型別
- 新增 `AttackDamageService`
- 搬出 base damage / critical / modifier / reduction summary
- 補 focused unit tests
- 不改 `attackArt(...)`

### Step AD-2：adapter bridge

- `MatchActionService.attackArt(...)` 改呼叫 `AttackDamageService`
- 保留原 payload shape
- damage prevention / apply damage / down / Gift follow-up 留在原流程
- 跑 damage modifier / reduction integration baseline

### Step AD-3：acceptance review

- 檢查 damage summary 子流程是否可視為 down / follow-up 前置拆分完成
- 盤點剩餘 allow / block 清單
- 再決定是否進 `ATTACK_DOWN` 或 `ATTACK_DAMAGE_APPLY`

---

## 九、下一步

建議先進 `AD-1`：

- `AttackDamageContext`
- `AttackDamageResult`
- `AttackDamageService`
- focused tests

完成後再接 `attackArt(...)` adapter。
