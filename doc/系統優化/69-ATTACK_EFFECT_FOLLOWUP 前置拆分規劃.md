# ATTACK_EFFECT_FOLLOWUP 前置拆分規劃

更新日期：2026-04-28
定位：`ATTACK_FINISH_CHECK` 驗收完成後，拆 `attackArt(...)` 內剩餘特殊效果段的前置規劃
用途：盤點 Holox / HBP02 / defender damage received Gift / official art extra / Oshi reactive 等攻擊後續效果，先定義邊界再拆最小 service。

---

## 一、為什麼接著拆 effect follow-up

`attackArt(...)` 的 cost / target / damage / damage apply / down / pending / payload / action log / finish check 都已拆出 contract。剩餘最難直接搬入完整 `AttackArtApplicationService` 的部分，是散落在主流程中的特殊效果：

- Holox slot reveal
- HBP02-039 support recovery
- HBP02-040 life loss
- defender damage received Gift prevention
- official card art extra effects
- official Oshi art reactive effects

這些效果牽涉卡片規則與副作用，不能直接大搬移。下一步應先做 wrapper / result contract，讓完整 attack pilot 能取得穩定輸出。

---

## 二、目前位置

### attack cost / damage 前

目前在 art metadata loading 後：

1. `resolveHoloxSlotRevealSummary(...)`
2. `applyHbp02039HoloxSupportRecovery(...)`
3. `applyHbp02040HoloxLifeLoss(...)`
4. `holoxRevealArtBonus = holoxSlotRevealSummary.artBonus()`

這段會影響：

- attack damage bonus
- action payload optional key：
  - `holoxReveal`
  - `hbp02039SupportRecovery`
  - `hbp02040LifeLoss`
- finish check additional effects

### damage apply 前

目前在 damage resolve 後、damage apply 前：

1. `matchTriggeredCombatEffectService.resolveTriggeredGiftDamagePrevention(...)`
2. 若 summary 不空：
   - append `GIFT_TRIGGER` action
   - 用 `damageAfter` 覆蓋 `totalDamage`

這段會影響：

- actual damage apply amount
- action log sequence
- `defenderDamageReceivedGift` payload key

### damage apply 後

目前在 `AttackDamageApplicationService.applyDamage(...)` 後：

1. `applyOfficialCardArtExtraEffects(...)`
   - HBP01-087
   - HBP01-088
2. `extractExecutedEffectSummaries(officialCardArtExtraSummary)`
3. `applyOfficialOshiArtReactiveEffects(...)`
4. `extractExecutedEffectSummaries(officialOshiArtReactiveSummary)`

這段會影響：

- additional effect summaries
- downstream `AttackDownService.resolveDown(...)`
- action payload optional keys：
  - `officialCardArtExtra`
  - `officialOshiArtReactive`

---

## 三、第一版目標

第一版不重寫任何卡片效果規則。

只拆出 attack effect follow-up orchestration：

1. pre-damage follow-up：
   - Holox reveal
   - HBP02-039
   - HBP02-040
   - art bonus
2. damage prevention follow-up：
   - defender damage received Gift summary
   - adjusted damage
   - optional action log decision
3. post-damage follow-up：
   - official card art extra summary
   - official card art extra executed effects
   - official Oshi art reactive summary
   - official Oshi art reactive executed effects

建議新增：

- `AttackEffectFollowupContext`
- `AttackEffectFollowupResult`
- `AttackEffectFollowupService`
- `AttackEffectFollowupServiceTest`

第一版 service 可透過 adapter 委派既有 private helper：

- `HoloxFollowupResolver`
- `Hbp02039Resolver`
- `Hbp02040Resolver`
- `DefenderDamagePreventionResolver`
- `OfficialCardArtExtraResolver`
- `OfficialOshiArtReactiveResolver`

---

## 四、責任邊界

### `AttackEffectFollowupService`

應負責：

- 控制特殊效果 follow-up 的呼叫順序
- 回傳 pre-damage / damage-prevention / post-damage 所需 summary
- 回傳 adjusted damage
- 回傳 additional effect summaries
- 回傳 payload 所需 optional summaries

不應負責：

- parse art cost
- resolve target
- apply base damage
- apply damage / life loss
- down event
- pending decision
- action payload 完整組裝
- finish check

### `MatchActionService.attackArt(...)`

第一版應保留：

- 實際卡片效果 helper 的內部實作
- `GIFT_TRIGGER` action log append 是否仍由 helper / adapter 執行
- `AttackDamageService` / `AttackDamageApplicationService` 呼叫點

但改成：

- 呼叫 follow-up service 取得 Holox / HBP02 summaries 與 art bonus
- 呼叫 follow-up service 取得 defender damage prevention summary 與 adjusted damage
- 呼叫 follow-up service 取得 official card / Oshi reactive summaries

---

## 五、分段施工建議

這段較大，建議不要一次拆完。

### Step AEF-1：pre-damage follow-up skeleton

- 先處理 Holox / HBP02-039 / HBP02-040
- 回傳：
  - `holoxSlotRevealSummary`
  - `hbp02039SupportRecovery`
  - `hbp02040LifeLoss`
  - `artBonus`
- 不改 `attackArt(...)`

### Step AEF-2：pre-damage adapter bridge

- `attackArt(...)` 改呼叫 service 取得 pre-damage follow-up
- 跑 Holox / HBP02 baseline

### Step AEF-3：damage prevention follow-up

- 包裝 defender damage received Gift prevention
- 保留 `GIFT_TRIGGER` action log timing
- 回傳 adjusted damage

### Step AEF-4：post-damage official follow-up

- 包裝 official card art extra / Oshi reactive
- 回傳 executed effects
- 保留 downstream `AttackDownService` 輸入形狀

### Step AEF-5：acceptance review

- 檢查完成條件
- 檢查 allow / block 清單
- 盤點測試缺口
- 決定是否進 `AttackArtApplicationService` 第一版

---

## 六、第一階段建議：先做 pre-damage follow-up

建議先做 Holox / HBP02 pre-damage follow-up，原因：

1. 位置在 cost / damage 前，資料依賴相對清楚。
2. 主要輸出是 summary 與 art bonus。
3. 不直接碰 damage apply / down / pending。
4. focused tests 可以先鎖住 call order 與 result shape。

---

## 七、測試基準

Focused tests 應覆蓋：

1. pre-damage resolver 依序呼叫 Holox、HBP02-039、HBP02-040。
2. `artBonus` 來自 Holox reveal summary。
3. HBP02-039 / HBP02-040 summaries 原樣回傳。
4. null context guard。

Integration baseline 可沿用：

- `attackArtShouldApplyOfficialPassiveGiftHbp04078ArtCostReductionOnCenterHolderSelf`
- `attackArtShouldTriggerDownedHolomemExtraLifeLoss`
- HBP02-039 / HBP02-040 相關測試若現有清單中存在，adapter bridge 時應優先加入。

---

## 八、下一步

建議進入 `AEF-1`：

- 新增 `AttackEffectFollowupContext`
- 新增 `AttackEffectFollowupResult`
- 新增 `AttackEffectFollowupService`
- 新增 `AttackEffectFollowupServiceTest`
- 第一版只覆蓋 pre-damage follow-up
- 不改 `attackArt(...)`
