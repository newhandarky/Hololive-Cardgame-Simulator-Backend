# Passive Gift Trigger Action Writer Unit Baseline Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：Passive Gift `GIFT_TRIGGER` writer unit baseline 補強

---

## 一、目標

本步延續 `82-MatchEffectService Gift Trigger SQL Writer Acceptance Review.md` 的剩餘可補強項目，針對 `PassiveGiftTriggerActionWriter` 補直接 unit test。

目標是不用 integration / Docker 也能鎖住 writer 的低階行為：

- invalid input 不寫 DB
- action order 使用目前 max + 1
- payload shape 保持 passive incoming damage reduction 語意

本步不改 production code。

---

## 二、完成項目

新增 `PassiveGiftTriggerActionWriterTest`，覆蓋：

- `matchId = null` 不寫入
- `userId = null` 不寫入
- `turnNumber <= 0` 不寫入
- `holderHolomemId = null` 不寫入
- 有效輸入時查詢目前同 turn max action order
- 有效輸入時寫入 action order `max + 1`
- payload 欄位：
  - `triggerType = PASSIVE_INCOMING_DAMAGE_REDUCTION`
  - `giftHolderHolomemId`
  - `giftText`
  - `diceRoll`
- `giftText = null` 保持 legacy 空字串語意

---

## 三、Allow / Block 清單

### Allow

- 補 writer focused unit test。
- 使用 mocked `JdbcTemplate` 驗證 SQL writer 參數。
- 使用 `ObjectMapper` 解析 payload JSON 以避免字串順序依賴。

### Block

- 不改 `PassiveGiftTriggerActionWriter` production 行為。
- 不改 `MatchEffectService.recordPassiveGiftTurnUsage(...)`。
- 不改 `isGiftAlreadyUsedThisTurn(...)` 查詢語意。
- 不把 passive Gift writer 合併進其他 action writer。

---

## 四、測試與驗證

已通過：

- `./mvnw -q -Dtest=PassiveGiftTriggerActionWriterTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、結論

本步無 blocker。

`PassiveGiftTriggerActionWriter` 現在已有直接 unit baseline，補齊 `82` 文件中提到的可後續補強項目。後續若要再動 passive Gift writer，已有不依賴 Testcontainers 的快速回歸測試。

下一步建議回到路線圖，評估下一個低風險 legacy cleanup；若要延續 Gift 路線，可先盤點 `isGiftAlreadyUsedThisTurn(...)` reader cleanup 是否值得獨立規劃。
