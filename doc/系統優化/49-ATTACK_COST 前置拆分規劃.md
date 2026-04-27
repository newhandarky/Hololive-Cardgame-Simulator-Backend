# ATTACK_COST 前置拆分規劃

更新日期：2026-04-27
定位：`PLAY_CARD` pilot 之後、直接拆 `ATTACK` 主流程之前的前置規劃
用途：先把 attack art 的 Cheer cost validation / payment 邊界切出來，避免一開始就重寫 `MatchActionService.attackArt(...)` 的完整攻擊流程。

---

## 一、為什麼先做 attack cost

`ATTACK` 主流程目前同時包含：

- turn / phase / attacker legality
- art metadata loading
- Cheer cost parse / reduction / payment validation
- target selection / redirect
- damage calculation
- damage reduction
- down / life loss
- Gift / down event follow-up
- pending decision
- action log payload
- match finish checks

若直接切 `ATTACK` 主流程，會同時碰到太多規則面與副作用。

`attack cost` 是比較適合先拆的原因：

1. 邊界比完整 attack 小。
2. 和 `ATTACH_CHEER` 的 resource relation 有明確關聯。
3. 現有 `payArtCost(...)` 已集中大部分 cost 選取邏輯。
4. `HardNpcService` 也有一份可支付判斷，顯示這段已需要共用 contract。
5. 完成後可以降低未來 `ATTACK` pilot 的風險。

---

## 二、目前位置

主要 production 邏輯位於：

- `MatchActionService.attackArt(...)`
  - 載入 art cost
  - 呼叫 `resolveArtCheerCost(...)`
  - 呼叫 `matchEffectCombatModifierService.resolvePassiveGiftArtCheerCostReduction(...)`
  - 呼叫 `applyArtCheerCostReduction(...)`
  - 呼叫 `payArtCost(...)`
  - 將結果寫入 action log payload：
    - `artBaseCost`
    - `artCost`
    - `passiveGiftArtCostReduction`
    - `costPayment`
- `MatchActionService.resolveArtCheerCost(...)`
- `MatchActionService.applyArtCheerCostReduction(...)`
- `MatchActionService.payArtCost(...)`
- `HardNpcService.canPayArtCost(...)`
- `HardNpcService.parseCostMap(...)`

目前 `payArtCost(...)` 的語意是：

- 驗證 attached Cheer 是否足夠
- 先支付指定顏色
- 再用剩餘 Cheer 支付 `COLORLESS`
- 回傳 paid summary
- 不消耗 Cheer
- `consumed = false`

---

## 三、第一版目標

第一版不重寫 attack damage / target / trigger。

只拆出 attack art cost 的可測邊界：

1. cost JSON parse
2. passive Gift cost reduction 套用
3. attached Cheer 可支付判斷
4. payment selection order
5. payment summary 建立
6. 既有不消耗 Cheer 的語意保留

建議新增：

- `AttackCostRequirement`
- `AttackCostPaymentContext`
- `AttackCostPaymentResult`
- `AttackCostService`
- `AttackCostServiceTest`

若要更貼近現有 pilot 模板，也可命名為：

- `AttackCostValidationContext`
- `AttackCostResolver`

但第一版建議先避免過度套用 action pipeline，因為 attack cost 不是獨立 player action，而是 `ATTACK_ART` 的子流程。

---

## 四、責任邊界

### `AttackCostService`

應負責：

- parse `cost_cheer_json`
- normalize color key
- apply reduction
- query attached Cheer rows
- select payment rows
- build payment summary
- throw existing compatible insufficient-cost error message

不應負責：

- 判斷是否可 attack
- 判斷 phase / turn action 是否完成
- 載入 attacker
- 載入 target
- 計算 damage
- rest attacker
- 寫 action log
- 建立 pending decision
- 消耗 Cheer

### `MatchActionService.attackArt(...)`

第一版應只改成：

- 保留 art loading
- 保留 passive Gift cost reduction 呼叫或透過 service 參數傳入
- 呼叫 `AttackCostService.resolvePayment(...)`
- 保留原 action log payload keys

---

## 五、輸入 / 輸出草案

### Input

`AttackCostPaymentContext` 至少包含：

- `matchId`
- `ownerUserId`
- `attackerHolomemId`
- `baseCost`
- `costReduction`

可選：

- `consume`
  - 第一版固定 `false`
  - 未來若規則確認要消耗 Cheer，再做產品判斷

### Output

`AttackCostPaymentResult` 至少包含：

- `baseCost`
- `reduction`
- `required`
- `requiredTotal`
- `paid`
- `paidTotal`
- `paidCheerCardIds`
- `paidCheerCardInstanceIds`
- `paidColors`
- `consumed`

---

## 六、現有測試基準

目前已有代表性 integration：

- `attackArtShouldRequireSpecificColorBeforeColorlessCost`
- `attackArtShouldApplyOfficialPassiveGiftHbp04078ArtCostReductionOnCenterHolderSelf`
- `attackArtShouldApplyOfficialPassiveGiftHbp04078ArtCostReductionFromCollabHolderToCenterAnya`
- `attackArtShouldNotApplyOfficialPassiveGiftHbp04078ArtCostReductionWithoutAttachedAncientWeapon`
- `attackArtShouldApplyOfficialPassiveGiftHbp06056ArtCostReductionAfterReferencedSpOshiSkillUsedThisGame`
- `attackArtShouldNotApplyOfficialPassiveGiftHbp06056ArtCostReductionWithoutReferencedSpOshiSkillHistory`

第一版新增 focused unit tests 應覆蓋：

1. 空 cost 回傳零需求。
2. malformed JSON 的處理策略。
3. 指定顏色優先於 `COLORLESS`。
4. 指定顏色不足時拒絕。
5. `COLORLESS` 可用剩餘任意顏色支付。
6. reduction 後需求不可低於 0。
7. payment summary 保持舊 payload key 相容。

---

## 七、允許暫留

第一版允許：

- `attackArt(...)` 仍是主流程入口。
- damage / target / down / Gift follow-up 留在 `MatchActionService`。
- passive Gift cost reduction 仍由 `MatchEffectCombatModifierService` 提供。
- Cheer 不消耗，維持 `consumed = false`。
- `HardNpcService` 暫時還有 parse / canPay duplicate，等 cost service 穩定後再改用。

第一版不允許：

- 順手改 attack target 規則。
- 順手改 damage / down / life loss。
- 順手讓 attack cost 開始消耗 Cheer。
- 把 `ATTACK_ART` 完整 action pipeline 一次搬完。

---

## 八、建議施工順序

### Step AC-1：contract / service skeleton

- 新增 cost context / result 型別
- 新增 `AttackCostService`
- 搬出 parse / reduction / payment selection
- 補 focused unit tests
- 不改 `attackArt(...)`

### Step AC-2：adapter bridge

- `MatchActionService.attackArt(...)` 改呼叫 `AttackCostService`
- 保留原 payload shape
- 跑既有 attack cost integration tests

### Step AC-3：NPC reuse

- `HardNpcService.canPayArtCost(...)` 改用 `AttackCostService`
- 保留 NPC 行為
- 補最小 regression tests 或 compile 驗證

### Step AC-4：acceptance review

- 檢查 cost 子流程是否可視為 `ATTACK` 前置拆分完成
- 再決定是否進 support card use case 或 attack target / damage 子流程

---

## 九、下一步

建議先進 `AC-1`：

- `AttackCostPaymentContext`
- `AttackCostPaymentResult`
- `AttackCostService`
- `AttackCostServiceTest`

完成後再評估是否接 `attackArt(...)` adapter。
