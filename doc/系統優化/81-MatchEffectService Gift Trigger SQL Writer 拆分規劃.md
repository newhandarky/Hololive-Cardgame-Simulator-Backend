# MatchEffectService Gift Trigger SQL Writer 拆分規劃

更新日期：2026-04-29
定位：`MatchActionService` legacy helper cleanup 之後的下一個低風險 cleanup 規劃

---

## 一、背景

`80-MatchActionService Legacy Helper Cleanup Acceptance Review.md` 已確認第一輪 helper cleanup 通過：

- `MatchPayloadJsonService`
- `MatchTimestampService`
- `GiftTriggerActionWriter`

但 `MatchEffectService` 內仍有一段直接寫入 `match_actions` 的 legacy SQL writer，用於記錄 passive Gift 的 turn once 使用紀錄。

本文件只規劃該 SQL writer 的拆分，不直接修改 production code。

---

## 二、目前盤點

主要寫入點：

- `MatchEffectService.recordPassiveGiftTurnUsage(...)`

相關讀取點：

- `MatchEffectService.isGiftAlreadyUsedThisTurn(...)`

目前 payload shape：

```json
{
  "triggerType": "PASSIVE_INCOMING_DAMAGE_REDUCTION",
  "giftHolderHolomemId": 123,
  "giftText": "...",
  "diceRoll": 1
}
```

目前 action 寫入欄位：

- `match_id`
- `user_id`
- `turn_number`
- `action_order`
- `action_type = GIFT_TRIGGER`
- `payload`
- `executed_at = CURRENT_TIMESTAMP`

目前 action order 來源：

- `MatchEffectService.resolveNextActionOrder(matchId, turnNumber)`
- SQL：`SELECT COALESCE(MAX(action_order), 0) FROM match_actions WHERE match_id = ? AND turn_number = ?`

---

## 三、風險判斷

### 低風險

- payload shape 固定且小。
- writer 只服務 passive incoming damage reduction Gift turn once tracking。
- `isGiftAlreadyUsedThisTurn(...)` 只依 `giftHolderHolomemId` 查詢，不依 action order。

### 需保留

- `triggerType = PASSIVE_INCOMING_DAMAGE_REDUCTION`
- `giftHolderHolomemId` 以 JSON 欄位存在，讓 turn once 查詢可沿用。
- `giftText` 仍使用 `nullToEmpty(rawText)` 的 legacy 語意。
- `diceRoll` 仍記錄實際擲骰結果。
- action order timing 不提前、不延後。

### 不應在本輪做

- 不改 passive Gift 規則。
- 不改 damage reduction 計算。
- 不改 `isGiftAlreadyUsedThisTurn(...)` 查詢語意。
- 不把所有 `GIFT_TRIGGER` writer 合併成同一個大型 action framework。
- 不搬動 `MatchActionService.appendAction(...)`。

---

## 四、建議切法

### Step MES-GT-1：focused baseline

目標：

- 補 focused unit test 或窄 integration smoke，鎖住 passive Gift payload shape：
  - `triggerType`
  - `giftHolderHolomemId`
  - `giftText`
  - `diceRoll`
- 鎖住 action order 大於同 turn 既有 max order。

不做：

- 不新增 writer service。
- 不改 production code。

### Step MES-GT-2：抽 writer port

目標：

- 新增小型 writer，例如 `PassiveGiftTriggerActionWriter`。
- 讓 `MatchEffectService.recordPassiveGiftTurnUsage(...)` 委派 writer。
- writer 內保留既有 SQL 欄位與 action order 計算。

不做：

- 不和 `GiftTriggerActionWriter` 合併。
- 不和 `AttackActionLogService` 合併。

### Step MES-GT-3：acceptance review

目標：

- 對照本文件確認：
  - payload shape 不變
  - turn once 查詢不變
  - action order timing 不變
  - passive Gift damage reduction smoke 通過

---

## 五、建議驗證

Focused：

- 新增 writer unit test後：
  - empty / invalid input 不寫入
  - payload 欄位完整
  - action order 使用 max + 1

Integration：

- 選一個 passive incoming damage reduction Gift 測試，確認：
  - 第一次觸發會寫 `GIFT_TRIGGER`
  - 第二次同 turn 不重複觸發
  - payload 包含 dice roll 與 holder id

Static：

- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 六、下一步

下一步建議先做 MES-GT-1：補 passive Gift `GIFT_TRIGGER` SQL writer baseline，不直接改 production code。
