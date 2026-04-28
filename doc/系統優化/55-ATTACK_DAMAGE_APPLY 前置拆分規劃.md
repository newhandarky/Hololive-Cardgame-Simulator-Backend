# ATTACK_DAMAGE_APPLY 前置拆分規劃

更新日期：2026-04-28
定位：`ATTACK_DAMAGE` summary 驗收完成後、拆 down / Gift follow-up 前的前置規劃
用途：先把 attack art 的實際傷害套用結果切出來，避免下一步直接搬移 down / life loss / Gift follow-up timing。

---

## 一、為什麼先做 damage apply

`ATTACK_ART` 目前已先後拆出：

1. `ATTACK_COST`
2. `ATTACK_TARGET`
3. `ATTACK_DAMAGE`

下一段最適合拆的是 damage application，而不是完整 down / follow-up。

原因：

1. damage application 是 damage summary 後的直接副作用邊界，範圍比 down / Gift follow-up 小。
2. 目前 `attackArt(...)` 已經清楚分出三種結果：
   - 對手有 Holomem 且 damage > 0：呼叫 `matchEffectDamageService.applyArtDamage(...)`
   - 對手有 Holomem 但 damage 被 prevention 抵銷：建立 `ART_DAMAGE_PREVENTED` no-op summary
   - 對手沒有 Holomem：扣 1 點 LIFE 並建立 `ART_DAMAGE_FALLBACK` summary
3. `artSummary` 與 `lostLifeCardInstanceId` 是後續 down / life loss / Gift follow-up 的共同輸入，適合先做成 contract。
4. defender Gift follow-up 與 down trigger timing 較敏感，不應和 damage apply 同一步搬移。

---

## 二、目前位置

主要 production 邏輯位於 `MatchActionService.attackArt(...)`：

- damage prevention Gift：
  - `matchTriggeredCombatEffectService.resolveTriggeredGiftDamagePrevention(...)`
  - `GIFT_TRIGGER` action append
  - `damageAfter` 覆寫 `totalDamage`
- damage application：
  - `matchEffectDamageService.applyArtDamage(matchId, userId, totalDamage, effectiveTargetCardInstanceId, true)`
  - `ART_DAMAGE_PREVENTED` no-op summary
  - `loseLifeOnce(matchId, opponentUserId)`
  - `ART_DAMAGE_FALLBACK` summary
- 後續仍在同一段：
  - official card art extra effects
  - official Oshi art reactive effects
  - attack summary merge
  - post-trigger / downed opponent Gift preview
  - art down triggered effects
  - defender self-downed / ally-downed Gift preview
  - pending interactions
  - attacker rest
  - action payload / finish checks / life loss send cheer interactions

目前 helper：

- `loseLifeOnce(...)`
- `matchEffectDamageService.applyArtDamage(...)`
- `hasHolomemDowned(...)`
- `extractDownEventPreview(...)`
- `mergeEffectSummaryForChecks(...)`

本次只應處理 `artSummary` 與 `lostLifeCardInstanceId` 的產生，不搬後續 trigger 判斷 helper。

---

## 三、第一版目標

第一版只拆出 damage apply result，不搬 down / Gift follow-up。

應覆蓋：

1. 對手有 Holomem 且 final damage > 0 時套用 `applyArtDamage(..., deferDownEvent = true)`。
2. 對手有 Holomem 但 final damage <= 0 時回傳 `ART_DAMAGE_PREVENTED` summary。
3. 對手沒有 Holomem 時扣對手 1 點 LIFE。
4. 對手沒有可失去 LIFE 時保留既有例外。
5. 回傳 `artSummary`。
6. 回傳 `lostLifeCardInstanceId`。
7. 維持 `ART_DAMAGE_PREVENTED` / `ART_DAMAGE_FALLBACK` payload shape。

建議新增：

- `AttackDamageApplicationContext`
- `AttackDamageApplicationResult`
- `AttackDamageApplicationService`
- `AttackDamageApplicationServiceTest`

第一版 `AttackDamageApplicationService` 可以呼叫既有：

- `MatchEffectDamageService`
- `GameActionExecutor`

但不應呼叫：

- `MatchTriggeredCombatEffectService.resolveTriggeredGiftDamagePrevention(...)`
- `MatchGiftTriggerService`
- `applyOfficialCardArtExtraEffects(...)`
- `applyOfficialOshiArtReactiveEffects(...)`
- `applyOfficialOshiSelfDownedEffects(...)`
- `matchTriggeredCombatEffectService.applyArtDownTriggeredEffects(...)`
- finish condition evaluation
- action log append

---

## 四、責任邊界

### `AttackDamageApplicationService`

應負責：

- 根據 `hasOpponentHolomem` 與 final damage 決定實際 damage application 分支
- 呼叫 `MatchEffectDamageService.applyArtDamage(..., true)`
- 建立 prevented no-op summary
- 執行 no opponent Holomem 的 life fallback
- 回傳 `artSummary` 與 `lostLifeCardInstanceId`

不應負責：

- damage summary
- damage prevention Gift trigger
- attack target
- official art extra effects
- Oshi reactive effects
- down / self-downed / ally-downed Gift preview
- pending interaction creation
- attacker rest
- action payload / action log
- finish condition evaluation

### `MatchActionService.attackArt(...)`

第一版應只改成：

- 保留 damage prevention Gift 呼叫與 `totalDamage` 覆寫
- 呼叫 `AttackDamageApplicationService.applyDamage(...)`
- 使用 result 提供的：
  - `artSummary`
  - `lostLifeCardInstanceId`
- 保留 official extra / Oshi reactive / down / Gift follow-up / payload / finish checks

---

## 五、輸入 / 輸出草案

### Input

`AttackDamageApplicationContext` 至少包含：

- `matchId`
- `attackerUserId`
- `opponentUserId`
- `finalDamage`
- `effectiveTargetCardInstanceId`
- `hasOpponentHolomem`

可選：

- `deferDownEvent`
  - 預設應為 `true`，保留 attack art 目前語意。

### Output

`AttackDamageApplicationResult` 至少包含：

- `artSummary`
- `lostLifeCardInstanceId`

可提供：

- `hasLifeLoss()`
- `isPrevented()`
- `isFallbackLifeLoss()`

---

## 六、現有測試基準

目前已有代表性 integration：

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

第一版新增 focused tests 應覆蓋：

1. has opponent Holomem 且 damage > 0 時呼叫 `applyArtDamage(..., true)`。
2. has opponent Holomem 且 damage <= 0 時回傳 `ART_DAMAGE_PREVENTED` no-op summary。
3. no opponent Holomem 時透過 `GameActionExecutor` 扣 1 點 LIFE 並回傳 `ART_DAMAGE_FALLBACK`。
4. no opponent Holomem 且沒有 LIFE 可扣時拋出既有錯誤。
5. result 正確帶出 `lostLifeCardInstanceId`。

---

## 七、允許暫留

第一版允許：

- `attackArt(...)` 仍是 `ATTACK_ART` 主流程入口。
- damage prevention Gift 仍留在 `MatchActionService`。
- `totalDamage` 仍由 `MatchActionService` 在 prevention 後覆寫。
- official card art extra / Oshi reactive 仍留在 `MatchActionService`。
- down / life loss / Gift follow-up 仍留在 `MatchActionService`。
- finish condition evaluation 仍留在 `MatchActionService`。
- `loseLifeOnce(...)` 可先複製到新 service；後續再評估是否抽 shared life loss helper。

第一版不允許：

- 順手改 damage prevention Gift trigger timing。
- 順手改 down event timing。
- 順手改 defender Gift follow-up timing。
- 順手改 `ART_DAMAGE_PREVENTED` / `ART_DAMAGE_FALLBACK` payload key。
- 順手改 finish condition evaluation。
- 順手改 attack cost / target / damage summary。

---

## 八、建議施工順序

### Step ADA-1：contract / service skeleton

- 新增 damage application context / result 型別
- 新增 `AttackDamageApplicationService`
- 搬出 apply damage / prevented no-op / fallback life loss
- 補 focused unit tests
- 不改 `attackArt(...)`

### Step ADA-2：adapter bridge

- `MatchActionService.attackArt(...)` 改呼叫 `AttackDamageApplicationService`
- 保留 damage prevention Gift 與後續 down / Gift follow-up 原流程
- 移除 `MatchActionService` 中 attack art damage apply 分支
- 跑 damage apply / prevention / fallback integration baseline

### Step ADA-3：acceptance review

- 檢查 damage application 子流程是否可視為 down / follow-up 前置拆分完成
- 盤點剩餘 allow / block 清單
- 再決定是否進 `ATTACK_DOWN` 或 `ATTACK_GIFT_FOLLOWUP`

---

## 九、下一步

建議先進 `ADA-1`：

- `AttackDamageApplicationContext`
- `AttackDamageApplicationResult`
- `AttackDamageApplicationService`
- focused tests

完成後再接 `attackArt(...)` adapter。
