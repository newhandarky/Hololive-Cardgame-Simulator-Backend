# MatchAction Attack Post Trigger Facade Cleanup Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 `MatchActionService.createAttackArtPostTriggerConfirmPendingInteraction(...)` private facade cleanup。

範圍包含：

- 移除已無 production call path 的 attack post-trigger private facade
- 移除對應 reflection-level `MatchActionServiceTest`
- 保留 `AttackArtPendingDecisionCreatorTest`
- 保留 `AttackArtPostTriggerConfirmPendingInputBuilderTest`

不包含：

- Gift pending private facade
- source cards builder
- pending input builder
- pending writer SQL
- schema / public API
- attack post-trigger pending 建立順序

---

## 二、完成條件檢查

### facade usage

狀態：完成

`createAttackArtPostTriggerConfirmPendingInteraction(...)` 在 `AttackArtPendingDecisionCreator` 抽出後已無 production call path。

移除後，attack post-trigger pending decision creation 由：

- `AttackPostTriggerPendingService`
- `AttackArtPendingDecisionCreator`
- `AttackArtPostTriggerConfirmPendingInputBuilder`
- `FollowupTriggerConfirmPendingDecisionWriter`

共同承接。

### test coverage

狀態：完成

移除 reflection-level `MatchActionServiceTest.createAttackArtPostTriggerConfirmPendingInteractionShouldKeepPostTriggerContext`。

等價 coverage 保留於：

- `AttackArtPendingDecisionCreatorTest`
- `AttackArtPostTriggerConfirmPendingInputBuilderTest`
- `AttackPostTriggerPendingServiceTest`

---

## 三、Allow / Block 清單

### Allow

- 移除已無 production call path 的 private facade。
- 移除只覆蓋該 private facade 的 reflection test。
- 使用 creator / input builder focused tests 保護行為。

### Block

- 不改 Gift pending facade。
- 不改 pending input context shape。
- 不改 source cards shape。
- 不改 pending writer SQL 或 schema。
- 不改 attacker pending 先於 defender pending 的順序。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=MatchActionServiceTest,AttackArtPendingDecisionCreatorTest,AttackArtPostTriggerConfirmPendingInputBuilderTest test`
- `./mvnw -q -DskipTests compile`

commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 `createGiftTriggeredEffectConfirmPendingInteraction(...)` 是否仍需留在 `MatchActionService`，因為 main step / phase / baton touch Gift still use it。
- 評估 main step / phase / baton touch Gift pending 是否要切成 dedicated adapters。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

MatchAction attack post-trigger facade cleanup 通過 acceptance review。

下一步建議回到 Gift pending private facade 盤點，但不要直接移除；它目前仍有 main step / phase / baton touch production call path。
