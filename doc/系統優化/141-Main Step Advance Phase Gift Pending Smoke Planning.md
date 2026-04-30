# Main Step / Advance Phase Gift Pending Smoke Planning

日期：2026-04-30
狀態：規劃完成

## 一、背景

Gift trigger pending facade cleanup 已收束，`MatchActionService` 目前仍保留兩個語意 adapter：

- `createGiftTriggerDecisionWithoutSourceCard(...)`
- `createBatonTouchGiftTriggerDecision(...)`

其中 `createGiftTriggerDecisionWithoutSourceCard(...)` 已在 `106-MatchAction Sourceless Gift Pending Adapter Naming Acceptance Review.md` 完成命名收斂，明確表示：

- source card instance id 為 `null`
- source card id 為 `null`
- cards payload 由 Gift holder cards 組成

因此本輪不建議再改名，而是先規劃 main step / advance phase Gift pending 的 smoke 保護。

## 二、現況 call site

### Advance phase Gift

`createAdvancePhaseFollowup(...)` 使用 `createGiftTriggerDecisionWithoutSourceCard(...)` 建立：

- own Gift pending
- opponent Gift pending

這些 pending 會透過 `buildAdvancePhasePayload(...)` append 到 action payload：

- `pendingInteractionDecisionId`
- `opponentPendingInteractionDecisionId`

### Main step Gift

`appendMainStepGiftFollowupPayload(...)` 使用 `createGiftTriggerDecisionWithoutSourceCard(...)` 建立 main step Gift pending。

此 helper 目前仍保留在 `MatchActionService` private scope，並維持：

- 無 Gift effect 時仍寫入 `mainStepGiftEffects` summary
- 有 Gift effect 時建立 pending decision
- action payload append pending interaction decision

## 三、既有 smoke 候選

### Main step Gift

既有 integration smoke：

- `mainStepGiftHsd13013ShouldArchiveStackCostAndAttachCheerFromDeckTop`
- `mainStepGiftHsd13013ShouldNotAttachCheerWithoutStackCost`
- `mainStepGiftHbp03030ShouldTriggerAndBuffArtWhenAttached35PAndDiceIsThree`
- `mainStepGiftHbp03030ShouldNotTriggerWithoutAttached35P`

建議優先挑一個最穩定且涵蓋 pending context 的案例：

- `mainStepGiftHsd13013ShouldArchiveStackCostAndAttachCheerFromDeckTop`

理由：

- 已檢查 `TRIGGER_EFFECT_CONFIRM` pending context。
- 已檢查 `triggerType = MAIN_STEP_SELF`。
- 已驗證 resolve 後會寫入 `GIFT_TRIGGER` action payload。

### Advance phase Gift

既有 integration smoke：

- `advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers`
- `advancePhaseShouldCreateOpponentPerformanceEndGiftConfirmWhenLifeReducedDuringPerformance`
- `advancePhaseShouldCreateOwnPerformanceEndGiftConfirm`
- `advancePhaseShouldCreateOpponentPerformanceEndGiftConfirmWhenHolderHpUnchanged`

建議優先挑一個主 smoke：

- `advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers`

理由：

- 同時涵蓋 own / opponent pending。
- 檢查 `PERFORMANCE_START_SELF` 與 `PERFORMANCE_START_OPPONENT`。
- 驗證 pending 未 resolve 前阻擋再次 advance phase。
- resolve 後 phase 仍維持正確。

## 四、Allow / Block 清單

### Allow

- 將上述兩個 integration test 標記為本批 smoke baseline。
- 若測試時間可接受，可在後續 commit 前固定跑這兩個 focused tests。
- 若需要補文件，可只更新 acceptance review，不改 production。

### Block

- 不重命名 `createGiftTriggerDecisionWithoutSourceCard(...)`。
- 不移除 main step / advance phase local adapter。
- 不改 Gift pending context JSON shape。
- 不改 phase transition timing。
- 不改 pending resolve 行為。
- 不把 full `MatchActionServiceIntegrationTest` 當作本批 blocker。

## 五、建議驗證命令

後續若要跑 focused integration smoke，建議使用：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#mainStepGiftHsd13013ShouldArchiveStackCostAndAttachCheerFromDeckTop test
```

以及：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers test
```

文件或小型 adapter cleanup 固定執行：

```bash
git diff --check
```

## 六、下一步

進入 code review / commit checkpoint。

commit 後建議執行 main step Gift focused smoke，確認可作為後續 legacy cleanup 的穩定 baseline。
