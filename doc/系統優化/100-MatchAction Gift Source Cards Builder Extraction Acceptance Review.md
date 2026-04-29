# MatchAction Gift Source Cards Builder Extraction Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 `MatchActionService.buildGiftTriggerInteractionCards(...)` 的 production extraction。

範圍包含：

- 新增 `GiftTriggerInteractionCardsBuilder`
- `MatchActionService` 保留 private facade 並委派新 helper
- helper 使用既有 `FollowupSourceCardPayloadBuilder`
- focused helper tests
- MatchAction facade regression

不包含：

- Gift pending input builder 改動
- Gift selection context builder 改動
- pending decision writer 改動
- attack pending conversion 改動
- public action API
- schema / SQL shape
- source cards JSON 欄位命名調整

---

## 二、完成條件檢查

### helper extraction

狀態：完成

新增 package-private `GiftTriggerInteractionCardsBuilder`，負責：

- source card fallback payload
- Gift holder card fallback payload
- Gift holder zone fallback
- duplicate holder 去重
- invalid holder 跳過

### MatchAction facade

狀態：完成

`MatchActionService` 新增 `GiftTriggerInteractionCardsBuilder` 欄位並於 constructor 建立。

原本 private `buildGiftTriggerInteractionCards(...)` 保留 signature，但實作改為委派 helper；所有呼叫點維持不變。

### focused regression

狀態：完成

新增 `GiftTriggerInteractionCardsBuilderTest`，覆蓋：

- source card `zone = STAGE`
- holder zone 優先
- holder zone 空值 fallback `STAGE`
- holder 與 source card 相同時去重
- duplicate holder 去重
- invalid holder 跳過

既有 `MatchActionServiceTest` focused regression 仍通過，保護 facade 接線。

---

## 三、Allow / Block 清單

### Allow

- 新增 package-private helper。
- `MatchActionService` internal helper 委派。
- helper 使用既有 `FollowupSourceCardPayloadBuilder`。
- 新增 helper unit tests。

### Block

- 不改 pending `cards` payload shape。
- 不改 Gift trigger payload。
- 不改 Gift selection context。
- 不改 pending writer SQL 或 schema。
- 不改 attack / defender Gift pending conversion。
- 不改 public action API。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=GiftTriggerInteractionCardsBuilderTest,MatchActionServiceTest test`
- `./mvnw -q -DskipTests compile`

commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 `createGiftTriggeredEffectConfirmPendingInteraction(...)` 是否可縮小為更薄的 adapter wrapper。
- 進一步拆 Gift pending creation 時，需保留 source cards builder、pending input builder、writer 的責任邊界。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

MatchAction Gift source cards builder extraction 通過 acceptance review。

下一步建議回到 `createGiftTriggeredEffectConfirmPendingInteraction(...)` wrapper，評估是否能只保留 pending input builder + writer adapter，不混入 source cards 或 trigger payload 規則。
