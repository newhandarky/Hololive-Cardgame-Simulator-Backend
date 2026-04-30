# Advance Phase Payload Builder Planning

日期：2026-04-30
狀態：規劃完成

## 一、背景

`buildAdvancePhasePayload(...)` 目前仍留在 `MatchActionService`，同時負責：

- 建立 phase transition 基礎 payload
- 寫入 `fromPhase`
- 寫入 `toPhase`
- 寫入 `firstPlayerFirstTurnSkip`
- 透過 `MatchPhaseAdvanceGiftTransitionService.putAdvancePhaseGiftEffectPayload(...)` 寫入 Gift summary payload
- 透過 `FollowupDecisionPayloadAppender.append(...)` 寫入 own pending decision
- 透過 `FollowupDecisionPayloadAppender.appendOpponent(...)` 寫入 opponent pending decision

main step / advance phase Gift pending smoke baseline 已確認可用，因此可以開始規劃下一個低風險 cleanup。

## 二、目標

下一個 production slice 建議新增 package-private builder，先只搬 payload construction，不搬 phase transition 或 Gift trigger preview：

- 建議名稱：`AdvancePhasePayloadBuilder`
- 責任：建立 advance phase action payload
- 保留 `MatchActionService` 對 phase transition、followup preview、pending decision creation 的 orchestration ownership

## 三、Allow / Block 清單

### Allow

- 新增 `AdvancePhasePayloadBuilder`。
- 將 `fromPhase` / `toPhase` / `firstPlayerFirstTurnSkip` payload 建立搬進 builder。
- 將 Gift summary payload append 與 own/opponent pending decision append 搬進 builder。
- 讓 `MatchActionService.buildAdvancePhasePayload(...)` 暫時保留為 thin adapter，降低改動面。
- 補 `AdvancePhasePayloadBuilderTest` 鎖住 payload shape。

### Block

- 不改 `advancePhase(...)` phase transition timing。
- 不改 `createAdvancePhaseFollowup(...)`。
- 不改 `createGiftTriggerDecisionWithoutSourceCard(...)`。
- 不改 Gift pending context JSON shape。
- 不改 pending resolve 行為。
- 不改 `performanceStartGiftEffects` / `opponentPerformanceStartGiftEffects` / `performanceEndGiftEffects` / `opponentPerformanceEndGiftEffects` key。
- 不移除既有 smoke tests。

## 四、測試策略

新增 unit baseline：

- `AdvancePhasePayloadBuilderTest`
  - no transition / no followup 時只寫 phase 基礎 payload
  - performance start payload 寫入 own / opponent Gift summary key
  - pending decision 寫入 `pendingInteractionDecisionId` / `pendingInteractionDecisionType`
  - opponent pending decision 寫入 `opponentPendingInteractionDecisionId` / `opponentPendingInteractionDecisionType`

production 改動後建議跑：

```bash
./mvnw -q -Dtest=AdvancePhasePayloadBuilderTest,FollowupDecisionPayloadAppenderTest test
```

若碰 `MatchActionService.buildAdvancePhasePayload(...)` 接線，建議加跑：

```bash
./mvnw -q -DskipTests compile
```

若改動超過 thin adapter，應加跑 advance phase smoke：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers test
```

## 五、下一步

進入 code review / commit checkpoint。

commit 後建議執行 production slice：新增 `AdvancePhasePayloadBuilder` 與 unit test，讓 `MatchActionService.buildAdvancePhasePayload(...)` 改為 thin adapter。
