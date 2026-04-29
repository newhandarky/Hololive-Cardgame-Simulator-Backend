# Gift Selection Pending Context Builder Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 Gift selection pending context builder 的接線收斂。

範圍包含：

- `GiftSelectionPendingContextBuilder`
- `PlayCardEffectResolutionService`
- `CollabEffectResolutionService`
- `MatchActionService` 既有接線
- focused unit baseline

不包含：

- `giftTriggers` payload builder
- source card payload builder
- confirm message builder
- trigger sections builder
- broader follow-up framework

---

## 二、完成條件檢查

### 既有 builder

狀態：完成

`GiftSelectionPendingContextBuilder` 已存在，且 focused tests 覆蓋：

- null / empty input
- 非單一 selectable trigger 不輸出 selection context
- 單一 selectable trigger 輸出 `candidateCardInstanceIds`
- `selectionGiftHolderCardInstanceId` 正規化
- `minSelect` / `maxSelect` floor 規則
- invalid candidate value filtering

### PLAY_CARD 接線

狀態：完成

`PlayCardEffectResolutionService` 已改用 `GiftSelectionPendingContextBuilder` 建立 pending context selection 欄位。

保留：

- `source_action_type = GIFT`
- `effect_type = GIFT_TRIGGER`
- `giftTriggers` payload 仍由 `GiftTriggerPendingPayloadBuilder` 建立
- confirm message 仍由 PLAY_CARD service 決定

### COLLAB 接線

狀態：完成

`CollabEffectResolutionService` 已改用 `GiftSelectionPendingContextBuilder` 建立 pending context selection 欄位。

保留：

- `source_action_type = COLLAB`
- `effect_type = COLLAB_TRIGGER`
- `giftTriggers` payload 仍由 `GiftTriggerPendingPayloadBuilder` 建立
- trigger sections 仍由 COLLAB service 決定
- confirm message 仍由 COLLAB service 決定

### legacy attack post-trigger 入口

狀態：完成

`MatchActionService` 既有 attack post-trigger follow-up flow 原本已使用 `GiftSelectionPendingContextBuilder`。

本輪未改 legacy attack post-trigger 行為，只確認此入口仍與 PLAY_CARD / COLLAB 共用同一個 selection context builder。

---

## 三、Allow / Block 清單

### Allow

- PLAY_CARD / COLLAB / legacy attack post-trigger flow 可共用 `GiftSelectionPendingContextBuilder`。
- `GiftSelectionPendingContextBuilder` 只負責從 Gift trigger preview 建立 selection pending context 欄位。
- 各 use case 仍可自行決定是否建立 pending decision、如何組 message、如何建立 source card payload。

### Block

- 不把 `giftTriggers` payload 合併進 `GiftSelectionPendingContextBuilder`。
- 不把 source card payload 合併進 `GiftSelectionPendingContextBuilder`。
- 不把 trigger sections 合併進 `GiftSelectionPendingContextBuilder`。
- 不把 confirm message 建立邏輯搬進 builder。
- 不把 follow-up pending decision 建立時機搬進 builder。
- 不改 `FollowupTriggerConfirmPendingDecisionInput`。
- 不改 `match_pending_decisions` SQL 欄位。

---

## 四、測試與驗證

已執行：

- `./mvnw -q -Dtest=PlayCardEffectResolutionServiceTest,CollabEffectResolutionServiceTest,GiftSelectionPendingContextBuilderTest,GiftTriggerPendingPayloadBuilderTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

測試涵蓋：

- builder selection context normalization
- PLAY_CARD Gift confirm pending baseline
- COLLAB Gift confirm pending baseline
- Gift trigger payload builder baseline 不退步

---

## 五、剩餘缺口

無 blocker。

可後續補強但不阻擋本輪收斂：

- 代表性 legacy API integration smoke 可在 use case cleanup 補：
  - PLAY_CARD Gift stage enter selection confirm
  - COLLAB collab + Gift selection confirm
  - attack post-trigger Gift selection confirm
- 若要繼續 cleanup，下一個候選是確認 `buildGiftTriggeredEffectDeferredSummary` 是否可收斂為共用 helper；需先確認 PLAY_CARD / COLLAB summary shape 完全一致。

---

## 六、結論

Gift selection pending context builder cleanup 通過 acceptance review。

本輪已完成：

1. 既有 builder 覆蓋確認
2. PLAY_CARD 接線
3. COLLAB 接線
4. legacy attack post-trigger 既有接線確認
5. acceptance review

下一步建議評估 Gift triggered effect deferred summary helper；不要直接抽完整 follow-up framework。
