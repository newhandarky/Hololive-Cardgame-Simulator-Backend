# ATTACK_DEFENDER_GIFT_FOLLOWUP 前置拆分規劃

更新日期：2026-04-28
定位：`ATTACK_DOWN` 驗收完成後，拆 defender self-downed / ally-downed Gift follow-up 的前置規劃
用途：把攻擊造成 down 後，由防守方觸發的 Oshi / Gift / Fan follow-up 切出獨立 contract，同時保護 snapshot timing 與 pending interaction timing。

---

## 一、為什麼接著拆 defender Gift follow-up

`ATTACK_ART` 目前已拆出：

1. `ATTACK_COST`
2. `ATTACK_TARGET`
3. `ATTACK_DAMAGE`
4. `ATTACK_DAMAGE_APPLY`
5. `ATTACK_DOWN`

下一段可拆的是防守方 follow-up：

- official Oshi self-downed effects
- self-downed Gift preview
- ally-downed Gift preview
- HBP01-124 Fan self-downed preview
- defender Gift confirm pending interaction 的前置資料

這段目前仍在 `MatchActionService.attackArt(...)`，而且依賴 damage 前預先擷取的 holder / fan snapshot。拆分時最重要的是不要改變 snapshot timing。

---

## 二、目前位置

### snapshot timing

目前在 damage application 前，若存在對手 Holomem 且 `targetBeforeRedirect` 存在，先抓：

- `defenderSelfDownedHolderSnapshot = matchGiftTriggerService.loadGiftHolderSnapshot(...)`
- `defenderSelfDownedFanSupportSnapshots = loadSelfDownedFanSupportSnapshots(...)`

這是本流程最重要的保護線，不能移到 damage 後才抓。

### down 後 follow-up

`AttackDownService.resolveDown(...)` 回傳 `hasDownedHolomem()` 後，`attackArt(...)` 目前執行：

- `applyOfficialOshiSelfDownedEffects(...)`
- `matchGiftTriggerService.previewGiftTriggeredEffectsOnSelfDowned(...)`
- `matchGiftTriggerService.previewGiftTriggeredEffectsOnAllyDowned(...)`
- `previewHbp01124FanTriggeredEffectsOnSelfDowned(...)`

並累積到：

- `officialOshiSelfDownedSummary`
- `defenderGiftTriggeredEffects`

### pending interaction timing

目前順序是：

1. 先建立 attacker side post-trigger pending summary / decision
2. 再建立 defender Gift pending summary / decision
3. 再 rest attacker
4. 再寫 payload / action log

第一版不應改變此順序。

---

## 三、第一版目標

第一版只拆出 defender follow-up resolution，不搬 pending interaction creation。

應覆蓋：

1. 接收 downed target / holder snapshot / fan support snapshots。
2. 只有 attack downed 時才執行 defender follow-up。
3. 建立 `officialOshiSelfDownedSummary`。
4. 建立 `defenderGiftTriggeredEffects`：
   - self-downed Gift preview
   - ally-downed Gift preview
   - HBP01-124 Fan self-downed preview
5. 回傳 `downedTargetCardId` 與 `downedTargetZone`，供 pending interaction source card 使用。

建議新增：

- `AttackDefenderGiftFollowupContext`
- `AttackDefenderGiftFollowupResult`
- `AttackDefenderGiftFollowupService`
- `AttackDefenderGiftFollowupServiceTest`

第一版 service 可以呼叫既有：

- `MatchGiftTriggerService`
- 暫時搬入或委派 official Oshi self-downed helper
- 暫時搬入或委派 HBP01-124 Fan preview helper

---

## 四、責任邊界

### `AttackDefenderGiftFollowupService`

應負責：

- 判斷 attack down 後是否需要 defender follow-up
- 使用既有 holder snapshot 建立 Oshi self-downed summary
- 建立 self-downed Gift preview
- 建立 ally-downed Gift preview
- 建立 HBP01-124 Fan self-downed preview
- 回傳 defender pending interaction 所需的 source target metadata

不應負責：

- 抓 holder snapshot / fan support snapshot
- damage / down 判斷
- 建立 pending interaction
- attacker side post-trigger pending interaction
- attacker rest
- action payload / action log append
- finish condition evaluation

### `MatchActionService.attackArt(...)`

第一版應只改成：

- 保留 damage 前 snapshot 擷取位置
- 呼叫 `AttackDefenderGiftFollowupService.resolveFollowup(...)`
- 使用 result 提供的：
  - `officialOshiSelfDownedSummary`
  - `defenderGiftTriggeredEffects`
  - `downedTargetCardId`
  - `downedTargetZone`
- 保留 pending interaction creation 與後續 payload / finish checks

---

## 五、輸入 / 輸出草案

### Input

`AttackDefenderGiftFollowupContext` 至少包含：

- `matchId`
- `defenderUserId`
- `attackerUserId`
- `turnNumber`
- `hasDownedHolomem`
- `downedTargetCardInstanceId`
- `downedTargetCardId`
- `downedTargetZone`
- `downedTarget`
- `holderSnapshot`
- `fanSupportSnapshots`
- `artSummary`

### Output

`AttackDefenderGiftFollowupResult` 至少包含：

- `officialOshiSelfDownedSummary`
- `defenderGiftTriggeredEffects`
- `downedTargetCardId`
- `downedTargetZone`

可加 helper：

- `hasDefenderGiftTriggeredEffects()`
- `hasOfficialOshiSelfDownedSummary()`

---

## 六、現有測試基準

代表性 integration：

- `attackArtShouldTriggerOfficialExtraLifeLossForHbp02041WhenSelfDowned`
- `attackArtShouldTriggerOfficialExtraLifeLossForHbp03022WhenSelfDowned`
- `attackArtShouldTriggerOfficialGiftHsd08005WhenAllyDownedAndLifeIsNotHigher`
- `attackArtShouldNotTriggerOfficialGiftHsd08005WhenOwnerLifeIsHigherThanOpponent`
- `attackArtShouldTriggerOfficialGiftHsd09007WhenSelfDownedInCollabAndLifeIsLower`
- `attackArtShouldTriggerOfficialExtraLifeLossForHbp03039WhenSelfDowned`
- `attackArtShouldTriggerOfficialExtraLifeLossForHbp03083WhenSelfDowned`
- `attackArtShouldTriggerOfficialGiftHbp05028WhenStageBotanDealsSpecialDamageThirtyOrMore`

第一版 focused tests 應覆蓋：

1. no down 時不觸發任何 defender follow-up。
2. downed 時建立 self-downed Gift preview。
3. downed 時建立 ally-downed Gift preview。
4. holder snapshot 存在時，HBP01-124 Fan preview 帶入 cheer / stack snapshot。
5. official Oshi self-downed summary 仍由 holder snapshot 驅動。
6. result 保留 `downedTargetCardId` / `downedTargetZone`。

---

## 七、允許暫留

第一版允許：

- damage 前 snapshot 擷取仍留在 `MatchActionService`。
- pending interaction creation 仍留在 `MatchActionService`。
- `buildGiftTriggeredEffectDeferredSummary(...)` 仍留在 `MatchActionService`。
- `buildGiftTriggerInteractionCards(...)` 仍留在 `MatchActionService`。
- payload / finish checks 仍留在 `MatchActionService`。

第一版不允許：

- 改變 holder snapshot / fan support snapshot 擷取時間點。
- 改變 attacker side post-trigger pending 與 defender Gift pending 的建立順序。
- 改變 defender Gift confirm payload key。
- 改變 attacker rest timing。
- 改變 finish condition evaluation。
- 順手改 Oshi / Gift / Fan 規則判斷。

---

## 八、建議施工順序

### Step ADFG-1：contract / service skeleton

- 新增 context / result 型別
- 新增 service
- 搬出 defender follow-up preview 組裝
- 補 focused tests
- 不改 `attackArt(...)`

### Step ADFG-2：adapter bridge

- `MatchActionService.attackArt(...)` 改呼叫 service
- 保留 snapshot 擷取位置
- 保留 pending interaction creation
- 跑 self-downed / ally-downed integration baseline

### Step ADFG-3：acceptance review

- 檢查完成條件
- 檢查 allow / block 清單
- 盤點測試缺口
- 決定下一個 attack 子流程

---

## 九、下一步

建議先進 `ADFG-1`：

- `AttackDefenderGiftFollowupContext`
- `AttackDefenderGiftFollowupResult`
- `AttackDefenderGiftFollowupService`
- focused tests
