# Passive Gift Turn Usage Reader Baseline Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：Passive / Gift turn once reader baseline

---

## 一、目標

本步落地 `133-Passive Gift Turn Usage Reader Cleanup Planning.md` 的 PGTR-1。

目標是先用 focused unit test 鎖住 `MatchEffectService.isGiftAlreadyUsedThisTurn(...)` 的既有 reader 語意，不改 production code。

---

## 二、完成項目

新增 `MatchEffectServiceGiftTurnUsageReaderTest`，覆蓋：

- invalid input 回傳 `false` 且不查 DB：
  - `matchId = null`
  - `userId = null`
  - `turnNumber <= 0`
  - `holderHolomemId = null`
  - `holderHolomemId <= 0`
- `COUNT(*) = 0` 回傳 `false`
- `COUNT(*) > 0` 回傳 `true`
- SQL 條件保留：
  - `action_type = 'GIFT_TRIGGER'`
  - `payload ->> 'giftHolderHolomemId' = ?`
- holder id 仍以字串參數傳入，符合 legacy JSONB `->>` 比對語意。

---

## 三、Allow / Block 清單

### Allow

- 直接測 package-private `MatchEffectService.isGiftAlreadyUsedThisTurn(...)`。
- 使用 mocked `JdbcTemplate` 驗證 reader SQL 條件與參數。
- 只補 reader baseline，不改 production code。

### Block

- 不新增 reader service。
- 不改 method visibility。
- 不改 `MatchGiftTriggerService` method reference。
- 不加入 `triggerType` 篩選。
- 不改一般 Gift / passive Gift 共用 `giftHolderHolomemId` marker 的 turn once 語意。

---

## 四、測試與驗證

已通過：

- `./mvnw -q -Dtest=MatchEffectServiceGiftTurnUsageReaderTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、結論

本步無 blocker。

`isGiftAlreadyUsedThisTurn(...)` 的 reader baseline 已鎖住。下一步若要抽 reader port，可讓 `MatchEffectService` method 先委派新 reader，避免一次改動 `MatchGiftTriggerService` 的 method reference。

下一步建議進 PGTR-2：抽小型 Gift turn usage reader port，保留舊 method 作 adapter。
