# ATTACK_TARGET 前置拆分規劃

更新日期：2026-04-28
定位：`ATTACK_COST` 前置拆分完成後、拆 `ATTACK_DAMAGE` 或完整 `ATTACK` 主流程之前的前置規劃
用途：先把 attack art 的目標選擇、限制與 redirect 邊界切出來，避免下一步直接改 damage / down / Gift follow-up。

---

## 一、為什麼先做 attack target

`ATTACK_ART` 主流程中，target resolve 目前卡在 cost 與 damage 之間：

1. cost 已可由 `AttackCostService` 處理。
2. damage 計算需要已決定的目標站位、主色、Holomem id。
3. defender Gift snapshot 與 damage redirect 也依賴 target。
4. target payload 目前影響前端與測試：
   - `targetCardInstanceId`
   - `passiveGiftTargetRestrictionToCollab`
   - `passiveGiftTargetRestrictionApplied`
   - `damageRedirectApplied`
   - `targetMainColor`

因此 `ATTACK_TARGET` 是 `ATTACK_DAMAGE` 前合理的小邊界。

---

## 二、目前位置

主要 production 邏輯位於：

- `MatchActionService.attackArt(...)`
  - 計算對手是否有 Holomem
  - `resolveOpponentTargetHolomem(...)`
  - `hasPassiveGiftTargetRestrictionToCollab(...)`
  - explicit target 被限制時拒絕
  - 未指定 target 且有限制時 auto target 對手 COLLAB
  - `loadOpponentCollabTargetHolomem(...)`
  - 目標決定後載入 defender self-downed snapshots
  - `resolveDamageRedirectTarget(...)`
  - 建立 `effectiveTargetCardInstanceId`
  - 寫入 target 相關 payload
- target helper：
  - `resolveOpponentTargetHolomem(...)`
  - `loadOpponentCollabTargetHolomem(...)`
  - `hasPassiveGiftTargetRestrictionToCollab(...)`
  - `extractRequiredCenterTagForPassiveTargetRestriction(...)`
  - `hasCenterHolomemWithTag(...)`
  - `resolveDamageRedirectTarget(...)`
  - `loadTargetHolomemById(...)`
- 目前 private records：
  - `TargetHolomem`
  - `DamageRedirectTarget`

---

## 三、第一版目標

第一版不重寫 damage / down / Gift follow-up。

只拆出 target resolution 的可測邊界：

1. 判斷對手是否存在可攻擊 Holomem。
2. 指定 target validation。
3. 未指定 target 的預設 target priority。
4. passive Gift target restriction to COLLAB。
5. restriction 成立時的 explicit target reject / auto target COLLAB。
6. damage redirect target consume。
7. target payload 所需 flags。

建議新增：

- `AttackTargetContext`
- `AttackTargetResult`
- `AttackTargetService`
- `AttackTargetServiceTest`

若需要保留既有 record name，也可把 `TargetHolomem` 提升成 package-private 或 public record：

- `AttackTargetHolomem`
- `AttackDamageRedirectTarget`

第一版建議命名偏 attack target，不直接叫 damage target，避免和後續 damage prevention / reduction 混淆。

---

## 四、責任邊界

### `AttackTargetService`

應負責：

- query opponent Holomem count
- resolve requested target
- resolve default target priority
- resolve passive Gift target restriction to COLLAB
- resolve restriction auto target / explicit reject
- resolve one-shot damage redirect target
- return effective target and payload flags

不應負責：

- 判斷 attacker 是否可 attack
- 判斷 phase / turn action 是否完成
- 解析或支付 attack cost
- 計算 base damage / bonus / reduction
- apply damage
- down / life loss
- Gift trigger preview / confirm
- rest attacker
- 寫 action log

### `MatchActionService.attackArt(...)`

第一版應只改成：

- 保留 cost service 呼叫
- 呼叫 `AttackTargetService.resolveTarget(...)`
- 使用 result 提供的：
  - target Holomem
  - effective target card instance id
  - passive target restriction flags
  - damage redirect flag
- 保留原 action log payload keys

---

## 五、輸入 / 輸出草案

### Input

`AttackTargetContext` 至少包含：

- `matchId`
- `attackerUserId`
- `opponentUserId`
- `turnNumber`
- `requestedTargetCardInstanceId`

可選：

- `resolveDamageRedirect`
  - 第一版固定 `true`
  - 若未來要把 redirect 移到 damage prevention 階段，可用這個欄位做過渡

### Output

`AttackTargetResult` 至少包含：

- `hasOpponentHolomem`
- `target`
- `effectiveTargetCardInstanceId`
- `passiveGiftTargetRestrictionToCollab`
- `passiveGiftTargetRestrictionApplied`
- `damageRedirectApplied`
- `damageRedirectEffectId`

`target` 至少包含：

- `holomemId`
- `matchCardInstanceId`
- `cardId`
- `zone`
- `mainColor`

---

## 六、現有測試基準

目前已有代表性 integration：

- `attackArtShouldRedirectDamageToPreparedReplacementTarget`
- `attackArtShouldAutoTargetOpponentCollabWhenOfficialGiftHbp01050RestrictsArtTarget`
- `attackArtShouldRejectCenterTargetWhenOfficialGiftHbp01050RestrictsArtTarget`
- `attackArtShouldRejectCenterTargetWhenOfficialGiftHbp05010ConditionIsMet`
- `attackArtShouldAllowCenterTargetWhenOfficialGiftHbp05010CenterTagConditionNotMet`
- `attackArtShouldRejectCenterTargetWhenOfficialGiftHbp05043ConditionIsMet`
- `attackArtShouldAllowCenterTargetWhenOfficialGiftHbp05043CenterTagConditionNotMet`
- `attackArtShouldApplyOfficialSpecialDamageBonusHbp05045WhenAttackerIsOkayuAndTargetIsCenter`

第一版新增 focused tests 應覆蓋：

1. requested target 可解析時回傳該 target。
2. 未指定 target 時依 `CENTER`、`COLLAB`、`BACK`、id 排序。
3. 對手沒有 Holomem 時回傳 `hasOpponentHolomem = false`，不丟 target error。
4. 對手有 Holomem 但 requested target 不存在時拒絕。
5. passive restriction 成立且 requested target 非 COLLAB 時拒絕。
6. passive restriction 成立且未指定 target 時 auto target COLLAB。
7. passive restriction 條件 tag 不成立時允許原 target。
8. damage redirect 套用後 effective target 改變，且 one-shot turn effect 被消耗。

---

## 七、允許暫留

第一版允許：

- `attackArt(...)` 仍是 `ATTACK_ART` 主流程入口。
- defender self-downed snapshots 仍留在 `MatchActionService`，因為它們屬於 down / Gift follow-up。
- damage bonus / reduction 仍留在 `MatchActionService`。
- critical target color 判斷仍留在 damage 計算區塊，只讀 target result。
- payload 組裝仍留在 `MatchActionService`。
- damage redirect 仍在 target resolve 階段消耗，維持既有語意。

第一版不允許：

- 順手改 attack cost consume。
- 順手改 damage / down / life loss。
- 順手改 Gift trigger timing。
- 順手改 target payload key。
- 把完整 `ATTACK_ART` 搬成 action pipeline。

---

## 八、建議施工順序

### Step AT-1：contract / service skeleton

- 新增 target context / result / target 型別
- 新增 `AttackTargetService`
- 搬出 target loading / target restriction / redirect helper
- 補 focused unit tests 或 JDBC mock tests
- 不改 `attackArt(...)`

### Step AT-2：adapter bridge

- `MatchActionService.attackArt(...)` 改呼叫 `AttackTargetService`
- 保留原 payload shape
- defender snapshots 仍在 adapter 後處理
- 跑 target / redirect integration baseline

### Step AT-3：acceptance review

- 檢查 target 子流程是否可視為 `ATTACK_DAMAGE` 前置拆分完成
- 盤點剩餘 allow / block 清單
- 再決定是否進 `ATTACK_DAMAGE`

---

## 九、下一步

建議先進 `AT-1`：

- `AttackTargetContext`
- `AttackTargetResult`
- `AttackTargetService`
- focused tests

完成後再接 `attackArt(...)` adapter。
