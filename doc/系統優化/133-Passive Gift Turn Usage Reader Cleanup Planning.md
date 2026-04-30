# Passive Gift Turn Usage Reader Cleanup Planning

日期：2026-04-30
狀態：規劃完成
範圍：Passive / Gift turn once reader cleanup 前置規劃

---

## 一、背景

`132-Passive Gift Trigger Action Writer Unit Baseline Acceptance Review.md` 已補上 `PassiveGiftTriggerActionWriter` 的直接 unit baseline。

目前 passive Gift turn once 寫入責任已由 writer 承接，但 reader 仍留在：

- `MatchEffectService.isGiftAlreadyUsedThisTurn(...)`

這個 reader 目前不只被 passive incoming damage reduction 使用，也被 `MatchGiftTriggerService` 透過 method reference 當作一般 Gift turn once reader：

- `matchEffectService::isGiftAlreadyUsedThisTurn`

因此下一步不應直接搬 production code，應先鎖住 reader 語意與呼叫邊界。

---

## 二、目前 reader 語意

`isGiftAlreadyUsedThisTurn(matchId, userId, turnNumber, holderHolomemId)` 目前規則：

- invalid input 回傳 `false`
  - `matchId = null`
  - `userId = null`
  - `turnNumber <= 0`
  - `holderHolomemId = null`
  - `holderHolomemId <= 0`
- 查詢 `match_actions`
- 條件：
  - `match_id = ?`
  - `user_id = ?`
  - `turn_number = ?`
  - `action_type = 'GIFT_TRIGGER'`
  - `payload ->> 'giftHolderHolomemId' = ?`
- `COUNT(*) > 0` 時回傳 `true`

目前 reader 不檢查：

- `triggerType`
- `giftText`
- `diceRoll`
- action order
- card id

這點必須保留，因為一般 Gift turn once 與 passive Gift writer 都依賴同一個 `giftHolderHolomemId` marker。

---

## 三、風險判斷

### 低風險

- reader SQL 小且語意固定。
- invalid input 行為可直接 unit test。
- payload 欄位只依 `giftHolderHolomemId`，不需要解析完整 JSON。

### 需保留

- invalid input 一律 `false`。
- 不加入 `triggerType` 篩選。
- 不改 `action_type = 'GIFT_TRIGGER'`。
- 不改 `payload ->> 'giftHolderHolomemId'` 字串比較。
- 不改 `MatchGiftTriggerService` 目前的一般 Gift turn once 行為。

### 不應在本輪做

- 不改 Gift trigger turn once 規則。
- 不把 writer / reader 合併成大型 repository。
- 不改 `MatchGiftTriggerService` orchestration。
- 不改 passive incoming damage reduction 計算。

---

## 四、建議切法

### Step PGTR-1：reader baseline

目標：

- 補 focused unit test 鎖住 `MatchEffectService.isGiftAlreadyUsedThisTurn(...)`：
  - invalid input 不查 DB 並回傳 `false`
  - SQL 條件包含 `action_type = 'GIFT_TRIGGER'`
  - SQL 條件包含 `payload ->> 'giftHolderHolomemId' = ?`
  - `COUNT(*) = 0` 回傳 `false`
  - `COUNT(*) > 0` 回傳 `true`

不做：

- 不新增 production reader service。
- 不改 method visibility。

### Step PGTR-2：抽 reader port

目標：

- 新增小型 reader，例如 `GiftTurnUsageReader` 或 `PassiveGiftTurnUsageReader`。
- 讓 `MatchEffectService.isGiftAlreadyUsedThisTurn(...)` 委派 reader。
- 保留 `MatchEffectService` method 作為既有 caller 的 adapter，避免一次改 `MatchGiftTriggerService` method reference。

不做：

- 不直接替換所有 caller。
- 不改 SQL 條件。
- 不加入 trigger type filter。

### Step PGTR-3：acceptance review

目標：

- 對照本文件確認：
  - invalid input 行為不變
  - SQL reader 條件不變
  - general Gift 與 passive Gift 的 turn once marker 共用語意不變
  - focused tests / compile / diff check 通過

---

## 五、建議驗證

Focused：

- `GiftTurnUsageReaderTest` 或 `MatchEffectServiceGiftTurnUsageReaderTest`
- 若 PGTR-2 抽 reader，再補 reader unit test

Static：

- `./mvnw -q -DskipTests compile`
- `git diff --check`

Integration：

- reader port 本身不必第一步跑 integration。
- 若後續替換 `MatchGiftTriggerService` caller，再挑 general Gift turn once smoke。

---

## 六、下一步

下一步建議先做 PGTR-1：補 reader baseline test，不改 production code。
