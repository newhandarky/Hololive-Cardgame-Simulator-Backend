# ATTACK_REST_AND_PAYLOAD 前置拆分規劃

更新日期：2026-04-28
定位：`ATTACK_POST_TRIGGER_PENDING` 驗收完成後、直接切 `ATTACK` 主流程前的最後一段 attack art 收斂
用途：把 `attackArt(...)` 尾段的 attacker rest、payload decision 欄位、action log 與 finish check 邊界先整理成可拆 contract，避免直接重寫完整攻擊主流程。

---

## 一、為什麼接著拆 rest / payload

`ATTACK_ART` 目前已拆出：

1. `ATTACK_COST`
2. `ATTACK_TARGET`
3. `ATTACK_DAMAGE`
4. `ATTACK_DAMAGE_APPLY`
5. `ATTACK_DOWN`
6. `ATTACK_DEFENDER_GIFT_FOLLOWUP`
7. `ATTACK_POST_TRIGGER_PENDING`

`attackArt(...)` 剩餘高風險尾段主要是：

- 將 attacker 設為 rested
- 回到 `PERFORMANCE` phase 並保存 match
- 組裝 `ATTACK_ART` action log payload
- 寫入 pending decision id / type
- 合併 finish check 用 effect summary
- 依卡片效果 / life / no holomem 判斷勝負
- enqueue life loss send cheer interaction

這段比 damage / trigger 規則更偏副作用與輸出封裝，適合在完整 `ATTACK` pilot 前先收斂。

---

## 二、目前位置

`MatchActionService.attackArt(...)` 目前在 post-trigger pending 後執行：

1. `UPDATE match_holomems SET is_rested = TRUE`
2. `hasAvailableArtAttacker(...)`
3. `context.match.setCurrentPhase(PERFORMANCE)`
4. `matchRepository.saveAndFlush(...)`
5. 建立 action payload：
   - attacker / target / art / cost
   - damage result fields
   - damage prevention / extra effect / Oshi reactive / self-downed / down event
   - post-trigger / defender Gift effects
   - pending decision id / type
   - defender pending decision id / type
6. `appendAction(..., "ATTACK_ART", payload, ...)`
7. 合併 `effectSummaryForChecks`
8. 依序執行：
   - `evaluateCardEffectMatchFinish(...)`
   - `evaluateLifeDefeat(...)`
   - `evaluateNoHolomemDefeat(...)`
9. `enqueueLifeLossSendCheerInteractions(...)`

目前這些責任仍混在 `attackArt(...)` 尾段，且 payload 欄位很多，不適合一次搬完。

---

## 三、第一版目標

第一版只拆出可測且不改規則的尾段封裝：

1. 建立 attack art payload。
2. 回填 attacker pending / defender pending decision 欄位。
3. 建立 finish check 用 merged effect summary。
4. 將 attacker rest 和 phase save 的資料需求明確化。
5. 保留既有 `appendAction(...)`、finish evaluator、enqueue interaction 的實際呼叫點。

建議新增：

- `AttackRestAndPayloadContext`
- `AttackRestAndPayloadResult`
- `AttackRestAndPayloadService`
- `AttackRestAndPayloadServiceTest`

第一版 service 應以純資料組裝為主，不直接寫 DB，不直接呼叫 repository，不直接判斷勝負。

---

## 四、責任邊界

### `AttackRestAndPayloadService`

應負責：

- 組裝 `ATTACK_ART` action payload
- 寫入 `pendingInteractionDecisionId` / `pendingInteractionDecisionType`
- 寫入 `defenderPendingInteractionDecisionId` / `defenderPendingInteractionDecisionType`
- 建立 `additionalEffectSummaries`
- 建立 `effectSummaryForChecks`
- 回傳 payload 與 finish check summary

不應負責：

- DB update attacker rested
- match phase transition / repository save
- `appendAction(...)`
- `evaluateCardEffectMatchFinish(...)`
- `evaluateLifeDefeat(...)`
- `evaluateNoHolomemDefeat(...)`
- `enqueueLifeLossSendCheerInteractions(...)`
- damage / down / Gift / pending decision 建立

### `MatchActionService.attackArt(...)`

第一版應保留：

- attacker rest DB update
- phase transition / save
- `appendAction(...)`
- finish check evaluator 呼叫
- enqueue life loss send cheer interaction

但改成：

- 呼叫 `AttackRestAndPayloadService.resolve(...)`
- 使用 result 提供的 payload 寫 action log
- 使用 result 提供的 `effectSummaryForChecks` 做 finish check

---

## 五、輸入 / 輸出草案

### Input

`AttackRestAndPayloadContext` 至少包含：

- attacker / target / art metadata
- target restriction / redirect result flags
- cost payload fields
- damage payload fields
- optional summaries：
  - `holoxReveal`
  - `hbp02039SupportRecovery`
  - `hbp02040LifeLoss`
  - `defenderDamageReceivedGift`
  - `officialCardArtExtra`
  - `officialOshiArtReactive`
  - `officialOshiSelfDowned`
  - `artDownTriggeredEffects`
  - `postTriggerEffects`
  - `defenderGiftEffects`
- `hasNextPerformanceAction`
- `lostLifeCardInstanceId`
- attacker pending decision id / type
- defender pending decision id / type
- extra effect summaries for finish checks

### Output

`AttackRestAndPayloadResult` 至少包含：

- `payload`
- `effectSummaryForChecks`

可加 helper：

- `hasLifeReduced()`
- `hasHolomemDowned()`

但第一版可先避免把 finish predicate 搬入 service，保留由 `MatchActionService` 既有 helper 判斷。

---

## 六、現有測試基準

代表性 integration：

- `attackArtShouldTriggerDownedHolomemExtraLifeLoss`
- `attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention`
- `attackArtShouldTriggerOfficialGiftHbp05028WhenStageBotanDealsSpecialDamageThirtyOrMore`
- `attackArtShouldTriggerOfficialGiftHbp06014AndSwapHolopowerPickWithHandCard`
- `attackArtShouldTriggerOfficialGiftHbp06027AndGrantExtraBloomAllowance`
- `attackArtShouldApplyOfficialPassiveGiftHbp04078ArtCostReductionOnCenterHolderSelf`

第一版 focused tests 應覆蓋：

1. payload 保留 attacker / target / art / cost 基本欄位。
2. damage result fields 被完整合併。
3. optional summary 空值時不寫入非必要欄位。
4. optional summary 存在時寫入既有 payload key。
5. attacker pending decision 欄位寫入既有 key。
6. defender pending decision 欄位寫入既有 key。
7. finish check summary 合併 art summary 與 additional effects。

---

## 七、允許暫留

第一版允許：

- attacker rest DB update 留在 `MatchActionService`。
- phase transition / repository save 留在 `MatchActionService`。
- action log append 留在 `MatchActionService`。
- finish evaluator 留在 `MatchActionService`。
- enqueue interaction 留在 `MatchActionService`。

第一版不允許：

- 改變 attacker rest timing。
- 改變 `ATTACK_ART` action type。
- 改變既有 payload key。
- 改變 pending decision payload key。
- 改變 finish check evaluation order。
- 改變 life loss send cheer enqueue timing。

---

## 八、建議施工順序

### Step ARP-1：contract / payload service skeleton

- 新增 context / result / service
- focused tests 覆蓋 payload 欄位與 finish check summary
- 不改 `attackArt(...)`

### Step ARP-2：adapter bridge

- `attackArt(...)` 尾段改呼叫 service 建立 payload / effectSummaryForChecks
- 保留 rest / save / appendAction / finish evaluator 呼叫點
- 跑 attack art payload / pending / finish baseline

### Step ARP-3：acceptance review

- 檢查完成條件
- 檢查 allow / block 清單
- 盤點測試缺口
- 決定是否進入完整 `ATTACK` pilot 或先拆 finish checker / action log writer

---

## 九、下一步

建議先進 `ARP-1`：

- `AttackRestAndPayloadContext`
- `AttackRestAndPayloadResult`
- `AttackRestAndPayloadService`
- `AttackRestAndPayloadServiceTest`
