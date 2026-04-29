# Gift Trigger Pending Payload Builder Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 Gift trigger pending payload builder 的接線收斂。

範圍包含：

- `GiftTriggerPendingPayloadBuilder`
- `PlayCardEffectResolutionService`
- `CollabEffectResolutionService`
- `MatchActionService` 既有接線
- focused unit baseline

不包含：

- selection context builder
- confirm message builder
- trigger sections builder
- source card payload builder
- broader follow-up framework

---

## 二、完成條件檢查

### 既有 builder

狀態：完成

`GiftTriggerPendingPayloadBuilder` 已存在，且 focused tests 覆蓋：

- null / empty input
- empty trigger filtering
- known field normalization
- id list 去重
- string list normalize / 去重
- invalid value fallback

### PLAY_CARD 接線

狀態：完成

`PlayCardEffectResolutionService` 已改用 `GiftTriggerPendingPayloadBuilder` 建立 pending context `giftTriggers`。

保留：

- `source_action_type = GIFT`
- `effect_type = GIFT_TRIGGER`
- selection context 仍由 PLAY_CARD service 決定
- confirm message 仍由 PLAY_CARD service 決定

### COLLAB 接線

狀態：完成

`CollabEffectResolutionService` 已改用 `GiftTriggerPendingPayloadBuilder` 建立 pending context `giftTriggers`。

保留：

- `source_action_type = COLLAB`
- `effect_type = COLLAB_TRIGGER`
- selection context 仍由 COLLAB service 決定
- trigger sections 仍由 COLLAB service 決定
- confirm message 仍由 COLLAB service 決定

---

## 三、Allow / Block 清單

### Allow

- PLAY_CARD / COLLAB / legacy attack post-trigger flow 可共用 `GiftTriggerPendingPayloadBuilder`。
- `GiftTriggerPendingPayloadBuilder` 只負責把 raw Gift trigger preview 轉成 pending context payload。
- 各 use case 仍可自行決定是否建立 pending decision、如何組 message、如何補 selection context。

### Block

- 不把 selection context 合併進 `GiftTriggerPendingPayloadBuilder`。
- 不把 source card payload 合併進 `GiftTriggerPendingPayloadBuilder`。
- 不把 trigger sections 合併進 `GiftTriggerPendingPayloadBuilder`。
- 不把 follow-up pending decision 建立時機搬進 builder。
- 不改 `FollowupTriggerConfirmPendingDecisionInput`。
- 不改 `match_pending_decisions` SQL 欄位。

---

## 四、測試與驗證

已執行：

- `./mvnw -q -Dtest=PlayCardEffectResolutionServiceTest,CollabEffectResolutionServiceTest,GiftTriggerPendingPayloadBuilderTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

測試涵蓋：

- builder payload normalization
- PLAY_CARD Gift confirm pending baseline
- COLLAB Gift confirm pending baseline
- pending context `cards` fallback baseline 不退步

---

## 五、剩餘缺口

無 blocker。

可後續補強但不阻擋本輪收斂：

- 代表性 legacy API integration smoke 可在 use case cleanup 補：
  - PLAY_CARD Gift stage enter confirm
  - COLLAB collab + Gift confirm
  - attack post-trigger Gift confirm
- 若要繼續 cleanup，下一個候選是 selection context builder 接線，需先確認 `GiftSelectionPendingContextBuilder` 是否可安全用於 PLAY_CARD / COLLAB。

---

## 六、結論

Gift trigger pending payload builder cleanup 通過 acceptance review。

本輪已完成：

1. 既有 builder 覆蓋確認
2. PLAY_CARD 接線
3. COLLAB 接線
4. acceptance review

下一步建議評估 selection context builder 接線；不要直接抽完整 follow-up framework。
