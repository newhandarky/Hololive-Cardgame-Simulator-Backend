# Gift Deferred Summary Builder Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 Gift deferred summary builder 的接線收斂。

範圍包含：

- `GiftTriggeredEffectDeferredSummaryBuilder`
- `PlayCardEffectResolutionService`
- `CollabEffectResolutionService`
- `MatchActionService` legacy wrapper 接線
- focused unit baseline

不包含：

- `GiftTriggerPendingPayloadBuilder`
- `GiftSelectionPendingContextBuilder`
- source card payload builder
- confirm message builder
- trigger sections builder
- broader follow-up framework
- `AttackPostTriggerPendingService` 內部 summary helper

---

## 二、完成條件檢查

### 既有重複邏輯盤點

狀態：完成

本輪確認以下三個入口原本的 Gift deferred summary 欄位 shape 與 normalization 規則一致：

- `PlayCardEffectResolutionService`
- `CollabEffectResolutionService`
- `MatchActionService`

共同輸出欄位：

- `sourceActionType = GIFT`
- `deferred`
- `triggeredGifts`
- `requestedEffects`
- `executedEffects`
- `unsupportedEffects`

### builder 建立

狀態：完成

`GiftTriggeredEffectDeferredSummaryBuilder` 已建立，負責把 Gift trigger preview 轉為 deferred summary。

focused tests 覆蓋：

- null input
- empty input
- requested effect normalize
- requested effect 去重
- invalid requested effect value filtering
- `triggeredGifts` 保留原 trigger list

### PLAY_CARD 接線

狀態：完成

`PlayCardEffectResolutionService` 已改用 `GiftTriggeredEffectDeferredSummaryBuilder` 建立 `giftEffectSummary`。

保留：

- pending decision 建立時機
- pending context `giftTriggers`
- selection context
- confirm message
- triggered resolution order

### COLLAB 接線

狀態：完成

`CollabEffectResolutionService` 已改用 `GiftTriggeredEffectDeferredSummaryBuilder` 建立 Gift `giftEffectSummary`。

保留：

- collab effect summary
- pending decision 建立時機
- pending context `giftTriggers`
- selection context
- trigger sections
- confirm message
- triggered resolution order

### MatchActionService legacy wrapper 接線

狀態：完成

`MatchActionService` 內原 `buildGiftTriggeredEffectDeferredSummary` private wrapper 仍保留，內部改委派 `GiftTriggeredEffectDeferredSummaryBuilder`。

保留 wrapper 的理由：

- 降低本輪呼叫點改動面。
- 先收斂重複 summary shape，不在同一步改 legacy flow 呼叫結構。
- 讓後續若要拆 legacy `mainStepGiftEffects` / `batonTouchGiftEffectSummary`，可另開 slice 評估。

---

## 三、Allow / Block 清單

### Allow

- PLAY_CARD / COLLAB / legacy MatchActionService 可共用 `GiftTriggeredEffectDeferredSummaryBuilder`。
- builder 只負責 Gift deferred summary 欄位建立。
- legacy `MatchActionService` 可保留 private wrapper 以降低改動面。
- 完整 integration suite 未清時，允許以 focused tests + compile 作為本輪小型 cleanup 的 commit 門檻，但必須記錄缺口。

### Block

- 不把 `giftTriggers` pending payload 合併進 deferred summary builder。
- 不把 selection context 合併進 deferred summary builder。
- 不把 source card payload 合併進 deferred summary builder。
- 不把 trigger sections 合併進 deferred summary builder。
- 不把 confirm message 建立邏輯搬進 builder。
- 不把 pending decision 建立時機搬進 builder。
- 不改 `FollowupTriggerConfirmPendingDecisionInput`。
- 不改 `match_pending_decisions` SQL 欄位。
- 不在本輪修復或重寫廣域 `MatchActionServiceIntegrationTest` 失敗案例。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=PlayCardEffectResolutionServiceTest,CollabEffectResolutionServiceTest,GiftTriggeredEffectDeferredSummaryBuilderTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

嘗試但未通過：

- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest test`

結果：

- sandbox 內首次執行因 Docker socket 權限被擋。
- escalated 後可啟動 Testcontainers / PostgreSQL。
- 完整既有 suite 仍有多個 BLOOM / COLLAB / attack 斷言失敗與 3 個 `CENTER 已有 Holomem` 錯誤。

判讀：

- 失敗分布於廣域既有流程，不集中於本次 Gift deferred summary builder。
- 本輪不把完整 `MatchActionServiceIntegrationTest` 記為通過。
- 後續若要以 integration suite 作為固定門檻，需要先另開測試穩定化 slice。

---

## 五、剩餘缺口

無 blocker。

可後續補強但不阻擋本輪收斂：

- 補代表性 API / integration smoke：
  - PLAY_CARD Gift stage enter deferred summary
  - COLLAB Gift deferred summary
  - legacy MatchActionService `mainStepGiftEffects`
  - legacy MatchActionService `batonTouchGiftEffectSummary`
- 評估是否將 `AttackPostTriggerPendingService` 的同名 Gift summary helper 納入下一輪，但需先確認 attack post-trigger summary 的 use case 邊界。
- 另開 `MatchActionServiceIntegrationTest` 穩定化，不要混入 builder cleanup。

---

## 六、結論

Gift deferred summary builder cleanup 通過 acceptance review。

本輪已完成：

1. 重複 summary shape 確認
2. builder 建立
3. PLAY_CARD 接線
4. COLLAB 接線
5. MatchActionService legacy wrapper 接線
6. focused tests / compile / diff check
7. acceptance review

下一步建議評估 `AttackPostTriggerPendingService` 的 Gift summary helper 是否能納入共用 builder；若不想碰 attack post-trigger，可改做 Gift follow-up message / section builder 的 planning。
