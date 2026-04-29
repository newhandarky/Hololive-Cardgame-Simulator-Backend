# MatchEffectService Gift Trigger SQL Writer Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 `81-MatchEffectService Gift Trigger SQL Writer 拆分規劃.md` 內的 MES-GT-1 / MES-GT-2 / MES-GT-3。

範圍只包含 passive incoming damage reduction Gift 的 `GIFT_TRIGGER` SQL writer cleanup：

- `MatchEffectService.recordPassiveGiftTurnUsage(...)`
- `PassiveGiftTriggerActionWriter`
- `MatchEffectService.isGiftAlreadyUsedThisTurn(...)`
- HBP05-065 passive Gift damage reduction baseline

不包含：

- 一般 `DAMAGE_RECEIVED` Gift trigger flow
- `GiftTriggerActionWriter`
- `AttackActionLogService`
- `MatchActionService.appendAction(...)`
- passive Gift damage reduction 規則擴充

---

## 二、完成條件檢查

### MES-GT-1：focused baseline

狀態：完成

已在 `attackArtShouldApplyOfficialPassiveGiftHbp05065DamageReductionFortyWhenDiceOdd` 補 baseline：

- 既有減傷結果維持：
  - `passiveGiftIncomingDamageReduction = 40`
  - `incomingDamageReduction = 40`
  - `artTotalDamage = 60`
- `GIFT_TRIGGER` payload shape 已鎖住：
  - `triggerType = PASSIVE_INCOMING_DAMAGE_REDUCTION`
  - `giftHolderHolomemId`
  - `giftText`
  - `diceRoll = 1`
- action order 已鎖住：
  - passive Gift `GIFT_TRIGGER` 大於攻擊前同 turn max order
  - passive Gift `GIFT_TRIGGER` 小於本次 `ATTACK_ART`

### MES-GT-2：writer port

狀態：完成

已新增 `PassiveGiftTriggerActionWriter`，並讓 `MatchEffectService.recordPassiveGiftTurnUsage(...)` 委派 writer。

保留項目：

- invalid input 不寫入
- `MAX(action_order) + 1`
- `action_type = GIFT_TRIGGER`
- `triggerType = PASSIVE_INCOMING_DAMAGE_REDUCTION`
- `giftHolderHolomemId`
- `giftText = nullToEmpty(rawText)`
- `diceRoll`
- `executed_at = CURRENT_TIMESTAMP`

### MES-GT-3：acceptance review

狀態：完成

本文件即為 acceptance review。

---

## 三、舊入口 allow / block 清單

### Allow

- `MatchEffectService.recordPassiveGiftTurnUsage(...)` 可保留為 private adapter，避免擴大呼叫面。
- `PassiveGiftTriggerActionWriter` 可保留為 package-private 小型 writer。
- writer 可繼續使用 `JdbcTemplate`，因為本輪目標是搬離 `MatchEffectService` legacy SQL，不是統一所有 action 寫入模型。
- `isGiftAlreadyUsedThisTurn(...)` 可維持現有 SQL reader。

### Block

- 不把 passive Gift writer 合併進 `GiftTriggerActionWriter`。
- 不把 passive Gift writer 合併進 `AttackActionLogService`。
- 不在本輪改 `isGiftAlreadyUsedThisTurn(...)` 查詢語意。
- 不改 passive Gift damage reduction 規則。
- 不改 `MatchActionService.appendAction(...)`。
- 不把 `GIFT_TRIGGER` action type 常數化成跨服務大型抽象。

---

## 四、測試與驗證

已執行：

- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldApplyOfficialPassiveGiftHbp05065DamageReductionFortyWhenDiceOdd test`
- `git diff --check`

補充：

- integration test 需要 Docker/Testcontainers；sandbox 內會因 Docker socket 權限失敗，已在 sandbox 外重跑通過。

---

## 五、剩餘缺口

無 blocker。

可後續補強但不阻擋本輪收斂：

- 若未來要讓 writer 更獨立，可補 `PassiveGiftTriggerActionWriter` unit test，直接驗證 invalid input 不寫入。
- 若後續有第二種 passive Gift `GIFT_TRIGGER`，再評估 writer 是否需要拆 request object。
- `isGiftAlreadyUsedThisTurn(...)` 仍在 `MatchEffectService`，可等下一輪 reader cleanup 再評估，不和本輪 writer port 混在一起。

---

## 六、結論

`MatchEffectService` passive Gift `GIFT_TRIGGER` SQL writer cleanup 通過 acceptance review。

本輪已完成：

1. baseline snapshot
2. writer port
3. acceptance review

下一步建議回到較高層路線，評估下一個低風險 legacy helper / use case cleanup；不要直接切 `ATTACK` 主流程。
