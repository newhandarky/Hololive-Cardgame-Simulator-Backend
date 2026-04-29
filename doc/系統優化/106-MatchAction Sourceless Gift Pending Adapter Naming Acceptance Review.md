# MatchAction Sourceless Gift Pending Adapter Naming Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 `MatchActionService` 內無 source card Gift pending adapter 命名收斂。

範圍包含：

- `createDeferredGiftTriggerDecision(...)` 更名為 `createGiftTriggerDecisionWithoutSourceCard(...)`
- main step Gift pending 呼叫點
- advance phase Gift pending 呼叫點

不包含：

- baton touch Gift pending
- Gift pending input shape
- source cards builder
- pending writer SQL
- schema / public API

---

## 二、完成條件檢查

### naming clarity

狀態：完成

原 method 名稱 `createDeferredGiftTriggerDecision(...)` 無法表達它只適用於 source card 為 `null` 的 Gift pending。

新名稱 `createGiftTriggerDecisionWithoutSourceCard(...)` 明確標示：

- source card instance id 為 `null`
- source card id 為 `null`
- cards payload 只由 Gift holder cards 組成

### call sites

狀態：完成

已更新呼叫點：

- draw reveal 後 main step Gift
- turn cheer 後 main step Gift
- advance phase own Gift
- advance phase opponent Gift

baton touch Gift pending 仍保留獨立 inline path，因為它需要 source card。

---

## 三、Allow / Block 清單

### Allow

- 命名調整。
- 呼叫點同步改名。

### Block

- 不改 pending input shape。
- 不改 source cards shape。
- 不改 pending writer SQL 或 schema。
- 不改 baton touch Gift pending。
- 不改 phase transition。

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

- 評估無 source card Gift pending adapter 是否應抽出 package-private creator。
- baton touch Gift pending 需要獨立 adapter 或保留在 local flow。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

MatchAction sourceless Gift pending adapter naming cleanup 通過 acceptance review。

下一步建議先不要抽大 service，改先盤點 baton touch Gift pending 的 source card 差異與必要 regression。
