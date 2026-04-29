# MatchAction Legacy Pending Creation Adapter Planning

更新日期：2026-04-29
狀態：規劃完成

---

## 一、背景

一般 Gift pending input builder 已完成，且已補 `MatchActionService` legacy Gift pending focused regression test。

下一個重複點是 `MatchActionService.createTriggeredEffectConfirmPendingInteraction(...)` 內部 SQL writer。

目前已存在 `FollowupTriggerConfirmPendingDecisionWriter`，它和 legacy helper 的責任高度重疊：

- 檢查 blocking pending decision
- 計算 `minSelect` / `maxSelect`
- 建立 trigger confirm context JSON
- 寫入 `match_pending_decisions`
- 回傳 `FollowupInteractionDecision`

但 legacy helper 不只服務 Gift，也服務 Bloom / Collab / effect post-trigger / attack post-trigger 等路徑，不能無保護地直接替換。

---

## 二、現況盤點

### 已有共用元件

- `FollowupTriggerConfirmPendingDecisionInput`
- `FollowupTriggerConfirmPendingDecisionWriter`
- `GiftTriggeredEffectConfirmPendingInputBuilder`
- `MatchActionServiceTest`

### MatchActionService legacy helper

`createTriggeredEffectConfirmPendingInteraction(...)` 目前仍保留：

- no additional context overload
- with additional context overload
- legacy SQL writer
- context JSON 組裝

呼叫來源包含：

- Bloom trigger confirm
- Collab trigger confirm
- Gift trigger confirm
- effect post-trigger confirm
- attack post-trigger confirm

---

## 三、建議施工目標

下一個 production slice 不建議直接搬全部 pending creation。

建議第一步只做：

`MatchActionService` legacy helper 內部改委派 `FollowupTriggerConfirmPendingDecisionWriter`

責任限定：

- 保留 `createTriggeredEffectConfirmPendingInteraction(...)` 方法簽名。
- 在 helper 內建立 `FollowupTriggerConfirmPendingDecisionInput`。
- 呼叫 `FollowupTriggerConfirmPendingDecisionWriter.create(...)`。
- 不改任何呼叫點。
- 不改 pending context shape。
- 不改 source cards shape。

這樣可以先消除 SQL writer duplication，同時保留 legacy facade 呼叫面。

---

## 四、Allow / Block 清單

### Allow

- 在 `MatchActionService` 新增 `FollowupTriggerConfirmPendingDecisionWriter` 欄位。
- `createTriggeredEffectConfirmPendingInteraction(...)` 內部委派 writer。
- 保留 no-context overload。
- focused tests 鎖住 Gift legacy pending context。
- 補一個 generic pending writer delegation test，避免 min/max / context shape 回歸。

### Block

- 不改 public action APIs。
- 不搬 Gift source cards builder。
- 不搬 attack pending conversion。
- 不改 attack post-trigger pending shape。
- 不改 `FollowupTriggerConfirmPendingDecisionWriter` SQL。
- 不改 `match_pending_decisions` schema。
- 不改 use case timing。

---

## 五、驗證策略

下一個 production slice 至少執行：

- `./mvnw -q -Dtest=MatchActionServiceTest,FollowupTriggerConfirmPendingDecisionWriterTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

建議新增 / 擴充 focused tests：

- generic helper with additional context 可寫入 `minSelect` / `maxSelect`
- Gift legacy pending context 仍包含 `giftTriggers` / `giftCount`
- cards null fallback 仍為 empty list

不建議在同一步跑 full `MatchActionServiceIntegrationTest` 當 blocker，因為目前 broad suite 仍有既有失敗。

---

## 六、完成標準

第一版完成標準：

1. `MatchActionService` 持有 `FollowupTriggerConfirmPendingDecisionWriter`
2. legacy helper 內部委派 writer
3. 呼叫點不變
4. focused tests 通過
5. compile 通過
6. 文件進度更新

---

## 七、下一步

下一步建議進入 production slice：

`MatchActionService.createTriggeredEffectConfirmPendingInteraction(...)` internal writer delegation。

不要同一步搬 source cards、attack pending conversion 或 pending context builders。
