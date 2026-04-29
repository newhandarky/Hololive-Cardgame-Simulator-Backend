# Attack Art Post Trigger Pending Input Builder Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 `ATTACK_ART_POST_TRIGGER` confirm pending input builder extraction。

範圍包含：

- 新增 `AttackArtPostTriggerConfirmPendingInputBuilder`
- `MatchActionService.createAttackArtPostTriggerConfirmPendingInteraction(...)` 改為委派 builder + writer
- 移除 `MatchActionService` 內 attack post-trigger message / section / Gift payload / selection context facade
- focused builder tests
- MatchAction facade regression

不包含：

- attack pending orchestration 順序
- defender Gift pending conversion
- source cards builder
- Gift general pending input builder
- pending writer SQL
- schema / public API
- message / section builder 行為改動

---

## 二、完成條件檢查

### input builder extraction

狀態：完成

`AttackArtPostTriggerConfirmPendingInputBuilder` 負責建立 `FollowupTriggerConfirmPendingDecisionInput`，內容包含：

- `sourceActionType = ATTACK_ART_POST_TRIGGER`
- `effectType = ATTACK_ART_POST_TRIGGER`
- title / message
- cards
- gift trigger payload
- Gift selection context
- down event context
- trigger sections

### MatchAction cleanup

狀態：完成

`MatchActionService.createAttackArtPostTriggerConfirmPendingInteraction(...)` 現在只負責：

1. 呼叫 attack post-trigger input builder
2. 呼叫 `FollowupTriggerConfirmPendingDecisionWriter`

已移除 `MatchActionService` 內只服務 attack post-trigger 的：

- `buildGiftTriggerPayloads(...)`
- `appendGiftSelectionPendingContext(...)`
- `buildAttackArtPostTriggerConfirmMessage(...)`
- `buildAttackArtPostTriggerSections(...)`

---

## 三、Allow / Block 清單

### Allow

- 新增 package-private input builder。
- MatchAction private helper 委派 builder + writer。
- 移除已無其他用途的 private facade。
- 使用 focused tests 鎖住 input context shape。

### Block

- 不改 pending writer SQL 或 schema。
- 不改 pending decision type / effect type。
- 不改 Gift source cards builder。
- 不改 defender Gift pending conversion。
- 不改 message / section builder 行為。
- 不改 attack post-trigger pending 建立順序。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=AttackArtPostTriggerConfirmPendingInputBuilderTest,MatchActionServiceTest,AttackPostTriggerConfirmMessageBuilderTest,AttackPostTriggerSectionBuilderTest test`
- `./mvnw -q -DskipTests compile`

commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 `AttackPostTriggerPendingService` 是否可直接持有 pending input builder / writer adapter，進一步縮小 `MatchActionService.AttackArtPendingDecisionCreator`。
- 檢查 defender Gift pending 是否需要對稱的 input adapter。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

Attack Art post-trigger pending input builder extraction 通過 acceptance review。

下一步建議先做 `AttackArtPendingDecisionCreator` cleanup planning，確認是否能把 attacker-side pending decision creation 再往 `AttackPostTriggerPendingService` 邊界內推，但不改 attacker pending 先於 defender pending 的順序。
