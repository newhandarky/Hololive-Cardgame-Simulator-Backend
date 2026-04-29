# MatchAction Main Step Gift Pending Adapter Cleanup Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 main step Gift pending adapter cleanup。

範圍包含：

- draw reveal 後進 main step 的 Gift pending decision 建立
- turn cheer 後進 main step 的 Gift pending decision 建立
- 既有 `createDeferredGiftTriggerDecision(...)` adapter 重用

不包含：

- baton touch Gift pending
- advance phase Gift pending
- Gift pending input shape
- source cards builder
- pending writer SQL
- schema / public API

---

## 二、完成條件檢查

### draw reveal main step Gift

狀態：完成

`resolveDrawRevealDecision(...)` 內 main step Gift pending 建立改走：

- `createDeferredGiftTriggerDecision(matchId, userId, turnNumber, mainStepGiftEffects)`

保留：

- `mainStepGiftEffects` summary payload
- follow-up decision payload key
- source card 為 `null`
- source cards 由 adapter 使用 Gift holder cards 建立

### turn cheer main step Gift

狀態：完成

`resolveSendCheerDecision(...)` 內 turn cheer 後 main step Gift pending 建立改走同一個 adapter。

---

## 三、Allow / Block 清單

### Allow

- main step Gift pending 呼叫點重用既有 adapter。
- 移除兩段重複的 source cards + Gift pending wrapper inline code。

### Block

- 不改 Gift pending input shape。
- 不改 Gift source cards shape。
- 不改 pending writer SQL 或 schema。
- 不改 baton touch / advance phase Gift pending。
- 不改 turn phase transition。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=MatchActionServiceTest test`
- `./mvnw -q -DskipTests compile`

commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 `createDeferredGiftTriggerDecision(...)` 是否應更名為 main-step / phase shared Gift pending adapter。
- baton touch Gift pending 仍有 source card，需單獨規劃，不應套用無 source card adapter。
- advance phase Gift pending 已使用同一 adapter，可留待 acceptance review 收斂。

---

## 六、結論

MatchAction main step Gift pending adapter cleanup 通過 acceptance review。

下一步建議盤點 `createDeferredGiftTriggerDecision(...)` 的命名與責任邊界，確認是否可抽為 `GiftPendingDecisionCreator` 或保持 MatchAction local adapter。
