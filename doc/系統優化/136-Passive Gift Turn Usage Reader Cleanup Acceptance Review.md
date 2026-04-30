# Passive Gift Turn Usage Reader Cleanup Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：Passive / Gift turn once reader cleanup 收束

---

## 一、目標

本文件收束 `133` 至 `135` 的 Passive / Gift turn usage reader cleanup。

本批目標是把 `MatchEffectService.isGiftAlreadyUsedThisTurn(...)` 的 SQL reader 實作責任移出，同時保留既有 method 作 adapter，避免擴大一般 Gift trigger path 的改動面。

本批不改：

- Gift trigger turn once 規則
- `MatchGiftTriggerService` method reference
- passive incoming damage reduction 規則
- `action_type = 'GIFT_TRIGGER'`
- `payload ->> 'giftHolderHolomemId' = ?`
- invalid input 回傳 `false` 的語意

---

## 二、完成項目

### PGTR-1：reader baseline

- 新增 `MatchEffectServiceGiftTurnUsageReaderTest`。
- 鎖住原 `MatchEffectService.isGiftAlreadyUsedThisTurn(...)` 語意：
  - invalid input 不查 DB 並回傳 `false`
  - `COUNT(*) = 0` 回傳 `false`
  - `COUNT(*) > 0` 回傳 `true`
  - SQL 保留 `action_type = 'GIFT_TRIGGER'`
  - SQL 保留 `payload ->> 'giftHolderHolomemId' = ?`

### PGTR-2：reader port

- 新增 `GiftTurnUsageReader`。
- `MatchEffectService.isGiftAlreadyUsedThisTurn(...)` 改為委派 reader。
- `MatchEffectService` method 保留為既有 caller adapter。
- `MatchGiftTriggerService` caller 未改。
- 新增 `GiftTurnUsageReaderTest`，承接完整 reader baseline。
- `MatchEffectServiceGiftTurnUsageReaderTest` 改為 adapter smoke。

### PGTR-3：acceptance review

- 本文件即為 PGTR-3 acceptance review。

---

## 三、Allow / Block 清單

### Allow

- `GiftTurnUsageReader` 作為 package-private 小型 reader。
- `MatchEffectService.isGiftAlreadyUsedThisTurn(...)` 保留為 adapter。
- 一般 Gift 與 passive Gift 仍共用 `giftHolderHolomemId` turn once marker。

### Block

- 不直接替換 `MatchGiftTriggerService` method reference。
- 不加入 `triggerType` 篩選。
- 不改 holder id 字串比較。
- 不把 writer / reader 合併成大型 repository。
- 不改 passive Gift damage reduction 計算。

---

## 四、測試與驗證

已通過：

- `./mvnw -q -Dtest=MatchEffectServiceGiftTurnUsageReaderTest test`
- `./mvnw -q -Dtest=GiftTurnUsageReaderTest,MatchEffectServiceGiftTurnUsageReaderTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

可後續補強但不阻擋本批：

- 若未來要直接替換 `MatchGiftTriggerService` caller，再挑一般 Gift turn once smoke。
- 若未來有不同 turn once marker，再另開規劃，不應直接擴大 `GiftTurnUsageReader`。

---

## 六、結論

Passive / Gift turn usage reader cleanup 通過 acceptance review。

`MatchEffectService` 已不再直接擁有 Gift turn once SQL reader 實作，舊 method 保留為 adapter，維持一般 Gift 與 passive Gift 的既有共用語意。

下一步建議回到較高層路線，挑下一個低風險 legacy cleanup；不建議在本批繼續擴大到 Gift trigger orchestration。
