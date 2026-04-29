# MatchAction Legacy Pending Writer Delegation Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 `MatchActionService.createTriggeredEffectConfirmPendingInteraction(...)` internal writer delegation。

範圍包含：

- `MatchActionService` 持有 `FollowupTriggerConfirmPendingDecisionWriter`
- `createTriggeredEffectConfirmPendingInteraction(...)` with-context overload 內部委派 writer
- `MatchActionServiceTest`
- `FollowupTriggerConfirmPendingDecisionWriterTest`

不包含：

- public action API
- source cards builder 搬移
- attack pending conversion 搬移
- attack post-trigger pending shape
- pending context builders 搬移
- `FollowupTriggerConfirmPendingDecisionWriter` SQL 改動
- `match_pending_decisions` schema
- use case timing

---

## 二、完成條件檢查

### writer delegation

狀態：完成

`MatchActionService` 已建立 `FollowupTriggerConfirmPendingDecisionWriter` 欄位，並在 constructor 中以既有 `jdbcTemplate` / `objectMapper` 建立。

`createTriggeredEffectConfirmPendingInteraction(...)` with-context overload 已改為：

1. 建立 `FollowupTriggerConfirmPendingDecisionInput`
2. 呼叫 `followupTriggerConfirmPendingDecisionWriter.create(...)`

保留：

- no-context overload
- method signature
- 所有呼叫點
- return type
- exception 行為

### context shape

狀態：完成

context JSON shape 維持由 `FollowupTriggerConfirmPendingDecisionWriter` 產生。

focused tests 覆蓋：

- Gift legacy pending context
- `giftTriggers`
- `giftCount`
- cards
- turn number
- additional context
- `minSelect` / `maxSelect`
- cards null fallback

---

## 三、Allow / Block 清單

### Allow

- `MatchActionService` generic pending helper 內部委派 `FollowupTriggerConfirmPendingDecisionWriter`。
- 保留 legacy facade helper，避免同步改多個呼叫點。
- focused tests 鎖住 Gift 與 generic additional context 行為。

### Block

- 不改 public action APIs。
- 不搬 Gift source cards builder。
- 不搬 attack pending conversion。
- 不改 attack post-trigger pending shape。
- 不改 pending context builders。
- 不改 writer SQL。
- 不改 pending schema。
- 不改 use case timing。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=MatchActionServiceTest,FollowupTriggerConfirmPendingDecisionWriterTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

測試涵蓋：

- MatchActionService Gift legacy pending regression
- MatchActionService generic additional context regression
- FollowupTriggerConfirmPendingDecisionWriter baseline

---

## 五、剩餘缺口

無 blocker。

可後續補強但不阻擋本輪收斂：

- 補 attack post-trigger pending context focused regression test。
- 評估 `MatchActionService` 是否仍需要保留 no-context overload，或可後續簡化呼叫點。
- 評估 `createGiftTriggeredEffectConfirmPendingInteraction(...)` 是否可再縮小為 adapter wrapper。
- 重新評估 full `MatchActionServiceIntegrationTest` 既有失敗清單，規劃測試穩定化。

---

## 六、結論

MatchAction legacy pending writer delegation 通過 acceptance review。

本輪已完成：

1. writer 欄位建立
2. helper internal delegation
3. 呼叫點保留
4. focused tests
5. compile 驗證
6. acceptance review

下一步建議補 attack post-trigger pending context focused regression test，確認 writer delegation 對 attack post-trigger context shape 無回歸；再評估是否能 cleanup legacy helper overload。
