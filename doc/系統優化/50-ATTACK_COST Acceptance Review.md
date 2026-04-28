# ATTACK_COST Acceptance Review

更新日期：2026-04-28
定位：`ATTACK_COST` 前置拆分驗收 review
用途：對照 `49-ATTACK_COST 前置拆分規劃.md`，確認 attack art Cheer cost 子流程是否已可視為 `ATTACK` 主流程拆分前的穩定基準。

---

## 一、結論

`ATTACK_COST` 前置拆分目前可視為已通過階段性驗收。

理由：

1. attack cost context / result / service 已存在。
2. cost JSON parse、reduction、payment selection、payment summary 已集中到 `AttackCostService`。
3. `MatchActionService.attackArt(...)` 已改成 adapter 呼叫 service，並保留既有 payload shape。
4. `HardNpcService` 已共用 `AttackCostService` 判斷可支付性，避免玩家與 NPC 各自維護顏色 / 無色支付規則。
5. focused unit tests 與既有 attack cost integration baseline 已覆蓋主要行為。
6. 本步沒有改 attack target、damage、down、Gift follow-up，也沒有開始消耗 Cheer。

---

## 二、完成條件對照

### 1. Contract / service skeleton

已完成：

- `AttackCostPaymentContext`
- `AttackCostPaymentResult`
- `AttackCostService`
- `AttackCostServiceTest`

目前 service 負責：

- parse `cost_cheer_json`
- normalize color key
- apply reduction
- query attached Cheer rows
- select payment rows
- build payment summary
- insufficient-cost error handling

判定：通過。

### 2. Adapter bridge

`MatchActionService.attackArt(...)` 目前保留：

- art metadata loading
- passive Gift cost reduction 查詢
- target / damage / down / Gift follow-up 主流程
- legacy action log payload

已移出：

- `resolveArtCheerCost(...)`
- `applyArtCheerCostReduction(...)`
- `payArtCost(...)`
- attack cost 專用的 `findFirstCheerIndexByColor(...)`

目前 payload 仍保留：

- `artBaseCost`
- `artCost`
- `passiveGiftArtCostReduction`
- `costPayment`

判定：通過。

### 3. NPC reuse

`HardNpcService.canPayArtCost(...)` 目前仍作為 NPC 私有決策入口，但內部已改用：

- `AttackCostService.parseCostStrict(...)`
- `AttackCostService.resolvePayment(...)`
- `AttackCostPaymentContext.preview(...)`

已移出：

- NPC 內部 `parseCostMap(...)`
- NPC 內部 available Cheer counting
- NPC 內部 color / colorless 支付判斷

NPC malformed cost JSON 的保守不可支付語意仍保留，沒有套用玩家主流程的寬鬆解析。

判定：通過。

---

## 三、舊入口 allow / block 清單對照

### 允許保留項

目前仍留在舊入口，且符合 `49` 文件允許範圍：

- `attackArt(...)` 仍是 `ATTACK_ART` 主入口。
- target selection / redirect 留在 `MatchActionService`。
- damage calculation / damage reduction 留在 `MatchActionService`。
- down / life loss / match finish checks 留在 `MatchActionService`。
- Gift / down event follow-up 留在 `MatchActionService` 與既有 effect services。
- passive Gift cost reduction 仍由 `MatchEffectCombatModifierService` 提供。
- Cheer 不消耗，`costPayment.consumed = false`。
- `HardNpcService.canPayArtCost(...)` 保留 NPC 決策語意入口。

判定：通過。

### 不允許保留項

以下項目已不再位於舊入口主流程或 NPC 自行複製邏輯：

- attack cost JSON parse 留在 `MatchActionService`。
- attack cost reduction 留在 `MatchActionService`。
- attack cost payment selection 留在 `MatchActionService`。
- payment summary 組裝留在 `MatchActionService`。
- NPC 重複維護 color / colorless 支付規則。
- 本前置拆分順手改 target / damage / down / Gift 規則。
- 本前置拆分順手讓 attack cost 開始消耗 Cheer。

判定：通過。

---

## 四、測試覆蓋對照

### Focused unit tests

`AttackCostServiceTest` 已覆蓋：

- parse cost normalize 與 invalid value ignore
- malformed JSON 寬鬆解析回傳 empty
- malformed JSON strict 解析拒絕
- reduction 不低於 0
- 指定顏色優先於 `COLORLESS`
- 指定顏色不足時拒絕
- empty cost zero summary
- payment summary key shape

判定：通過。

### Integration baseline

已跑過的 attack cost integration baseline：

- `attackArtShouldRequireSpecificColorBeforeColorlessCost`
- `attackArtShouldApplyOfficialPassiveGiftHbp04078ArtCostReductionOnCenterHolderSelf`
- `attackArtShouldApplyOfficialPassiveGiftHbp04078ArtCostReductionFromCollabHolderToCenterAnya`
- `attackArtShouldNotApplyOfficialPassiveGiftHbp04078ArtCostReductionWithoutAttachedAncientWeapon`
- `attackArtShouldApplyOfficialPassiveGiftHbp06056ArtCostReductionAfterReferencedSpOshiSkillUsedThisGame`
- `attackArtShouldNotApplyOfficialPassiveGiftHbp06056ArtCostReductionWithoutReferencedSpOshiSkillHistory`

已跑過的 NPC regression：

- `HardNpcServiceIntegrationTest#executeHardNpcTurnShouldAttackWhenUsableArtExists`

判定：通過。

---

## 五、測試缺口

目前沒有 blocker。

仍可後續補強：

1. NPC 不可支付時不攻擊的 focused regression。
2. malformed cost JSON 在 NPC 決策中不攻擊的 focused regression。
3. 未來若產品判斷 attack cost 要實際消耗 Cheer，需要另開規劃，不應在目前 preview contract 內偷改。

這些缺口不阻塞本次前置拆分驗收。

---

## 六、風險與暫留技術債

1. `ATTACK_ART` 主流程仍然很大。
   - 本次只拆 cost 子流程，target / damage / down / Gift follow-up 尚未拆。
2. `AttackCostPaymentContext.consume` 目前只有 preview 語意。
   - 實際消耗 Cheer 牽涉規則判斷與使用者體驗，需要產品確認。
3. `costPayment.paidCheerCardInstanceIds` 現在由 service 正確帶出 match card id。
   - payload key 保持相容；若前端曾依賴空陣列，需要後續 UI smoke 確認。

判定：可接受。

---

## 七、下一步建議

下一步不建議直接一次拆完整 `ATTACK` 主流程。

建議順序：

1. 先補 ATTACK_COST acceptance commit。
2. 接著規劃 `ATTACK_TARGET` 或 `ATTACK_DAMAGE` 子流程，二選一小步推進。
3. 若要回頭補 resource operation，先處理 attack cost consume 的產品判斷，再改 production 行為。
