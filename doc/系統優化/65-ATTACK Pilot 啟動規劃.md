# ATTACK Pilot 啟動規劃

更新日期：2026-04-28
定位：`ATTACK_REST_AND_PAYLOAD` 驗收完成後，啟動完整 `ATTACK` pilot 前的總體規劃
用途：盤點目前 attack art 已拆子流程，定義完整 `ATTACK` pilot 的分階段施工路線，避免直接把 `MatchActionService.attackArt(...)` 一次搬成大型 application service。

---

## 一、目前已完成的 attack 子流程

`ATTACK_ART` 已完成以下前置拆分：

1. `ATTACK_COST`
   - cost parse
   - passive Gift cost reduction
   - attached Cheer payment preview
2. `ATTACK_TARGET`
   - target resolve
   - target restriction
   - damage redirect
3. `ATTACK_DAMAGE`
   - base damage
   - art effect damage
   - modifier / bonus
4. `ATTACK_DAMAGE_APPLY`
   - apply damage
   - life loss
   - art summary
5. `ATTACK_DOWN`
   - down event
   - attacker side Gift trigger preview
   - art down triggered effects
6. `ATTACK_DEFENDER_GIFT_FOLLOWUP`
   - defender self-downed / ally-downed Gift preview
   - defender official Oshi self-downed follow-up
   - defender Fan self-downed preview
7. `ATTACK_POST_TRIGGER_PENDING`
   - attacker post-trigger pending
   - defender Gift pending
8. `ATTACK_REST_AND_PAYLOAD`
   - attack art payload
   - pending decision payload key
   - finish check summary

這些子流程讓 `attackArt(...)` 的規則段已大致可被 adapter 化，但仍有數個副作用與特殊效果段留在主流程。

---

## 二、`attackArt(...)` 仍保留的責任

目前仍在 `MatchActionService.attackArt(...)`：

- turn / phase / first-turn legality
- attacker loading / legality
- art metadata loading
- Holox slot reveal
- HBP02-039 support recovery
- HBP02-040 life loss
- defender damage received Gift prevention
- official card art extra effects
- official Oshi art reactive effects
- defender self-downed holder / Fan snapshot loading
- attacker rest DB update
- phase transition / save
- `ATTACK_ART` action log append
- finish evaluator
- life loss send cheer enqueue

其中有些是規則判斷，有些是副作用外殼。完整 `ATTACK` pilot 不應一次搬所有責任。

---

## 三、Pilot 目標

完整 `ATTACK` pilot 的終局目標：

- 讓 `MatchActionService.attackArt(...)` 退成薄 adapter。
- 將 attack art orchestration 收斂到一個 application service 或 pipeline。
- 保留既有 payload / pending decision / action log / finish 行為。
- 讓後續新增 attack 相關卡片效果時，不必在 `MatchActionService` 多段遠距修改。

第一輪不以完全消除 `MatchActionService.attackArt(...)` 為目標，而是先建立可替換的邊界。

---

## 四、建議分階段

### Step ATK-0：Pilot 規劃

- 建立本文件
- 盤點已拆子服務
- 盤點 `attackArt(...)` 剩餘責任
- 決定下一段施工優先順序

### Step ATK-1：`ATTACK_ACTION_LOG` 前置拆分

優先拆 action log 外殼，原因：

- 規則風險低
- payload 已由 `AttackRestAndPayloadService` 建立
- `appendAction(...)` 是共用副作用，先以 adapter 包裝即可

建議新增：

- `AttackActionLogContext`
- `AttackActionLogResult`
- `AttackActionLogService`
- `AttackActionLogServiceTest`

第一版只包裝 `ATTACK_ART` action log append 所需資料，不改 action order / action type / payload。

### Step ATK-2：`ATTACK_FINISH_CHECK` 前置拆分

拆 finish evaluator 外殼，原因：

- finish check evaluation order 是明確 block 清單
- 已有 `effectSummaryForChecks`
- 可以把 card effect finish / life defeat / no holomem defeat 的順序寫成 focused contract

建議新增：

- `AttackFinishCheckContext`
- `AttackFinishCheckResult`
- `AttackFinishCheckService`
- `AttackFinishCheckServiceTest`

第一版可透過 adapter 委派既有 private helper，或先搬出順序控制邏輯，實際 evaluator 暫留 `MatchActionService`。

### Step ATK-3：`ATTACK_EFFECT_FOLLOWUP` 盤點

盤點尚未收斂的 attack 特殊效果段：

- Holox slot reveal
- HBP02-039 support recovery
- HBP02-040 life loss
- defender damage received Gift prevention
- official card art extra effects
- official Oshi art reactive effects

這段牽涉卡片規則，風險高於 action log / finish check，因此建議在 ATK-1 / ATK-2 後再拆。

### Step ATK-4：`AttackArtApplicationService` 第一版

等 action log / finish check / effect follow-up 邊界穩定後，再建立完整 application service：

- 輸入：match / user / request / turn context
- 輸出：action result / pending decisions / finish state
- `MatchActionService.attackArt(...)` 只負責 transaction adapter 與 service 呼叫

---

## 五、第一階段建議：先做 `ATTACK_ACTION_LOG`

下一步建議先進 `ATTACK_ACTION_LOG`，而不是直接拆 `ATTACK_FINISH_CHECK` 或完整 application service。

理由：

1. `AttackRestAndPayloadService` 已提供 payload。
2. action log append 是明確副作用，包裝後能讓 `attackArt(...)` 尾段再少一塊。
3. 不碰勝負判定，不會改規則。
4. 可作為後續完整 application service 的輸出邊界。

---

## 六、`ATTACK_ACTION_LOG` 第一版邊界

### 應負責

- 接收 match / user / turn / payload
- 建立 `ATTACK_ART` action log
- 回傳 action type / payload snapshot / action order metadata

### 不應負責

- payload 組裝
- attacker rest
- phase transition
- finish check
- pending decision creation
- life loss send cheer enqueue

### 不允許改變

- action type：`ATTACK_ART`
- action payload JSON 內容
- action order 計算方式
- turn number
- user id

---

## 七、測試基準

Focused tests 應覆蓋：

1. 缺少 context 時拒絕。
2. 使用 `ATTACK_ART` action type。
3. payload 原樣送入 writer。
4. turn number / user id 原樣送入 writer。
5. 不嘗試修改 payload。

Integration baseline 可沿用：

- `attackArtShouldTriggerDownedHolomemExtraLifeLoss`
- `attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention`
- `attackArtShouldTriggerOfficialGiftHbp06014AndSwapHolopowerPickWithHandCard`
- `attackArtShouldTriggerOfficialGiftHbp06027AndGrantExtraBloomAllowance`

---

## 八、下一步

建議進入 `ATK-1`：

- 新增 `AttackActionLogContext`
- 新增 `AttackActionLogResult`
- 新增 `AttackActionLogService`
- 新增 `AttackActionLogServiceTest`
- 不改 `attackArt(...)`
