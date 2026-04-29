# Attack Art Pending Decision Creator Extraction Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 `AttackArtPendingDecisionCreator` 從 `MatchActionService` inner class 抽出。

範圍包含：

- 新增 package-private `AttackArtPendingDecisionCreator`
- `AttackPostTriggerPendingService.PendingDecisionCreator` 實作移出 `MatchActionService`
- attacker-side post-trigger pending decision creation
- defender Gift pending decision creation
- source cards builder / input builder / writer / conversion service 接線
- focused creator tests

不包含：

- `AttackPostTriggerPendingService` orchestration 順序改動
- pending input shape 改動
- source cards shape 改動
- pending writer SQL / schema
- public action API
- attack rest / payload / action log

---

## 二、完成條件檢查

### creator extraction

狀態：完成

`AttackArtPendingDecisionCreator` 已成為獨立 package-private class，實作：

- `createAttackPostTriggerPending(...)`
- `createDefenderGiftPending(...)`

它接收並組合：

- `GiftTriggerInteractionCardsBuilder`
- `AttackArtPostTriggerConfirmPendingInputBuilder`
- `GiftTriggeredEffectConfirmPendingInputBuilder`
- `FollowupTriggerConfirmPendingDecisionWriter`
- `AttackPendingDecisionConversionService`

### MatchAction cleanup

狀態：完成

`MatchActionService` 已移除 inner `AttackArtPendingDecisionCreator`。

constructor 直接建立新的 package-private creator，並交給 `AttackPostTriggerPendingService`。

### focused regression

狀態：完成

新增 `AttackArtPendingDecisionCreatorTest`，覆蓋：

- attacker-side post-trigger pending 寫入 `ATTACK_ART_POST_TRIGGER`
- defender Gift pending 寫入 `GIFT` / `GIFT_TRIGGER`
- writer context 包含 gift triggers / down event / trigger sections
- 回傳 `AttackPendingDecision`

---

## 三、Allow / Block 清單

### Allow

- 將 pending decision creator 從 inner class 移為 package-private class。
- 保留 `AttackPostTriggerPendingService.PendingDecisionCreator` 介面。
- 保留 pending 建立順序由 `AttackPostTriggerPendingService` 控制。
- 新增 focused unit tests。

### Block

- 不改 attacker pending 先於 defender pending 的順序。
- 不改 pending decision type / effect type。
- 不改 source cards shape。
- 不改 pending input context shape。
- 不改 writer SQL 或 schema。
- 不改 attack rest / payload / action log。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=AttackArtPendingDecisionCreatorTest,AttackPostTriggerPendingServiceTest,MatchActionServiceTest test`
- `./mvnw -q -DskipTests compile`

commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 `MatchActionService.createAttackArtPostTriggerConfirmPendingInteraction(...)` 是否只剩 test/facade 用途，能否移除或改測 creator。
- 評估 defender Gift pending 是否能與一般 Gift pending wrapper 進一步收斂。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

Attack Art pending decision creator extraction 通過 acceptance review。

下一步建議清理 `MatchActionService` 中已無 production call path 的 attack post-trigger private facade，前提是保留 equivalent focused regression。
