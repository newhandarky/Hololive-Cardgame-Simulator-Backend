# MatchAction Gift Pending Wrapper Cleanup Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 `MatchActionService.createGiftTriggeredEffectConfirmPendingInteraction(...)` wrapper cleanup。

範圍包含：

- Gift pending input builder 接線
- Gift wrapper 直接委派 `FollowupTriggerConfirmPendingDecisionWriter`
- 保留 Gift wrapper method signature
- 保留所有呼叫點
- focused tests 與 compile 驗證

不包含：

- Gift source cards builder 改動
- Gift pending input shape 改動
- Gift selection context 改動
- generic triggered-effect pending helper 改動
- pending writer SQL 改動
- schema / public API
- attack pending conversion

---

## 二、完成條件檢查

### wrapper cleanup

狀態：完成

`createGiftTriggeredEffectConfirmPendingInteraction(...)` 原本流程：

1. 使用 `GiftTriggeredEffectConfirmPendingInputBuilder` 建立 `FollowupTriggerConfirmPendingDecisionInput`
2. 拆開 input 欄位
3. 呼叫 generic `createTriggeredEffectConfirmPendingInteraction(...)`
4. generic helper 再建立 writer input 並寫入 pending decision

本步改為：

1. 使用 `GiftTriggeredEffectConfirmPendingInputBuilder` 建立 `FollowupTriggerConfirmPendingDecisionInput`
2. 直接呼叫 `followupTriggerConfirmPendingDecisionWriter.create(input)`

### behavior boundary

狀態：完成

保留：

- Gift wrapper method signature
- 所有呼叫點
- source action type / effect type
- title / message
- cards payload
- additional context
- writer SQL 行為

---

## 三、Allow / Block 清單

### Allow

- Gift wrapper 直接委派 writer。
- 保留 private wrapper 作為 legacy facade。
- 使用既有 focused tests 驗證 pending input 與 writer shape。

### Block

- 不改 Gift pending input builder。
- 不改 Gift source cards builder。
- 不改 Gift trigger payload / selection context。
- 不改 generic pending helper。
- 不改 writer SQL 或 schema。
- 不改 attack pending conversion。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=MatchActionServiceTest,GiftTriggeredEffectConfirmPendingInputBuilderTest,FollowupTriggerConfirmPendingDecisionWriterTest test`
- `./mvnw -q -DskipTests compile`

commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 generic `createTriggeredEffectConfirmPendingInteraction(...)` 是否仍可拆出更多 specialized wrappers。
- 檢查 `buildGiftTriggerPayloads(...)` / `appendGiftSelectionPendingContext(...)` 是否仍只服務 attack post-trigger，避免誤刪。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

MatchAction Gift pending wrapper cleanup 通過 acceptance review。

下一步建議盤點 `MatchActionService` 內剩餘 Gift helper facade，優先確認 `buildGiftTriggerPayloads(...)` 與 `appendGiftSelectionPendingContext(...)` 是否已只剩 attack post-trigger 使用，再決定是否命名收斂或搬到 attack post-trigger pending helper。
