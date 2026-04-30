# Passive Gift Turn Usage Reader Port Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：Passive / Gift turn once reader port

---

## 一、目標

本步落地 `133-Passive Gift Turn Usage Reader Cleanup Planning.md` 的 PGTR-2。

目標是把 `MatchEffectService.isGiftAlreadyUsedThisTurn(...)` 內的 SQL reader 移出到小型 reader port，同時保留 `MatchEffectService` method 作為既有 caller 的 adapter。

本步不改：

- `MatchGiftTriggerService` method reference
- invalid input 行為
- SQL 條件
- general Gift 與 passive Gift 共用 `giftHolderHolomemId` marker 的 turn once 語意

---

## 二、完成項目

新增 `GiftTurnUsageReader`，承接：

- invalid input 回傳 `false`
- 查詢 `match_actions`
- `action_type = 'GIFT_TRIGGER'`
- `payload ->> 'giftHolderHolomemId' = ?`
- `COUNT(*) > 0` 回傳 `true`

`MatchEffectService` 改為：

- 建構 `GiftTurnUsageReader`
- `isGiftAlreadyUsedThisTurn(...)` 委派 reader

測試調整：

- 新增 `GiftTurnUsageReaderTest`，承接完整 reader baseline。
- `MatchEffectServiceGiftTurnUsageReaderTest` 改為 adapter smoke，確認舊 method 仍可走到 reader 行為。

---

## 三、Allow / Block 清單

### Allow

- 抽出 package-private reader。
- 保留 `MatchEffectService.isGiftAlreadyUsedThisTurn(...)` 作 adapter。
- 將完整 SQL / invalid input baseline 移到 reader unit test。

### Block

- 不改 `MatchGiftTriggerService` caller。
- 不改 `action_type = 'GIFT_TRIGGER'`。
- 不加入 `triggerType` 篩選。
- 不改 holder id 字串比較。
- 不把 writer / reader 合併成大型 repository。

---

## 四、測試與驗證

已通過：

- `./mvnw -q -Dtest=GiftTurnUsageReaderTest,MatchEffectServiceGiftTurnUsageReaderTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、結論

本步無 blocker。

Gift turn once reader 的 SQL 實作責任已移出 `MatchEffectService`，舊 method 仍保留為 adapter，避免擴大一般 Gift trigger path 的改動面。

下一步建議進 PGTR-3 acceptance review，收束 reader cleanup batch；若無缺口，再回到較高層路線挑下一個 legacy cleanup。
