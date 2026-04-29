# MatchAction Gift Source Cards Regression Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 `MatchActionService.buildGiftTriggerInteractionCards(...)` 的 focused regression baseline。

範圍包含：

- source card fallback payload
- Gift holder card fallback payload
- Gift holder fallback zone
- duplicate holder 去重
- invalid holder 跳過

不包含：

- production code extraction
- Gift trigger pending input builder
- Gift selection context builder
- pending decision writer
- public action API
- attack pending conversion
- source cards JSON 欄位命名調整

---

## 二、完成條件檢查

### source card payload

狀態：完成

新增 `buildGiftTriggerInteractionCardsShouldIncludeSourceAndGiftHoldersWithFallbackZones`，鎖住 source card fallback：

- `cardInstanceId`
- `cardId`
- `zone = STAGE`

### Gift holder payload

狀態：完成

同一測試鎖住 Gift holder fallback：

- holder `giftHolderZone = BACK` 時，cards payload zone 維持 `BACK`
- holder `giftHolderZone` 空字串時，cards payload zone fallback `STAGE`

### dedupe / invalid holder

狀態：完成

新增 `buildGiftTriggerInteractionCardsShouldDedupeAndSkipInvalidGiftHolders`，鎖住：

- holder 與 source card 相同時不重複加入
- duplicate holder 不重複加入
- `giftHolderCardInstanceId <= 0` 跳過
- cards order 維持 source card 在前、holder card 在後

---

## 三、Allow / Block 清單

### Allow

- 只補 `MatchActionServiceTest` focused regression。
- 透過 loader fallback 模式驗證 pending `cards` shape。
- 保留 legacy private helper 的 reflection-level regression。

### Block

- 不改 production code。
- 不抽 `buildGiftTriggerInteractionCards(...)`。
- 不改 Gift source cards payload shape。
- 不改 pending context。
- 不改 writer SQL 或 schema。
- 不改 attack / defender Gift pending conversion。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=MatchActionServiceTest test`

本輪尚未執行但 commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 抽出 MatchAction Gift source cards builder / helper。
- 將 helper 改用既有 `FollowupSourceCardPayloadBuilder`，但需保留 Gift holder fallback zone 行為。
- extraction 後重跑本輪 focused regression。

---

## 六、結論

MatchAction Gift source cards regression baseline 通過。

下一步建議進入 production extraction：把 `buildGiftTriggerInteractionCards(...)` 的 source cards 組裝移出 `MatchActionService`，但不改 pending input、selection context、writer 或 attack conversion。
