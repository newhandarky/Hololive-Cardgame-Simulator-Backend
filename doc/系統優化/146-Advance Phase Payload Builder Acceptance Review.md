# Advance Phase Payload Builder Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本文件驗收 `AdvancePhasePayloadBuilder` production slice。

本步只搬移 advance phase action payload construction，不改 phase transition、Gift preview、pending decision creation 或 pending resolve 行為。

## 二、完成內容

新增：

- `AdvancePhasePayloadBuilder`
- `AdvancePhasePayloadBuilderTest`

`MatchActionService.buildAdvancePhasePayload(...)` 改為 thin adapter：

- 將 private `AdvancePhaseFollowup` 攤平成 builder 參數。
- 保留 `MatchActionService` 對 `advancePhase(...)` orchestration ownership。
- 保留 `createAdvancePhaseFollowup(...)` 與 `createGiftTriggerDecisionWithoutSourceCard(...)` 原行為。

`AdvancePhasePayloadBuilder` 目前負責：

- `fromPhase`
- `toPhase`
- `firstPlayerFirstTurnSkip`
- own Gift summary payload key
- opponent Gift summary payload key
- own pending decision append
- opponent pending decision append

## 三、Allow / Block 對照

### Allow

- 新增 package-private builder。
- 新增 unit tests 鎖住 payload shape。
- 讓 `MatchActionService.buildAdvancePhasePayload(...)` 暫留為 thin adapter。

### Block

- 未改 `advancePhase(...)` phase transition timing。
- 未改 `createAdvancePhaseFollowup(...)`。
- 未改 `createGiftTriggerDecisionWithoutSourceCard(...)`。
- 未改 Gift pending context JSON shape。
- 未改 pending resolve 行為。
- 未改 `performanceStartGiftEffects` / `opponentPerformanceStartGiftEffects` / `performanceEndGiftEffects` / `opponentPerformanceEndGiftEffects` key。

## 四、驗證結果

已通過：

```bash
./mvnw -q -Dtest=AdvancePhasePayloadBuilderTest,FollowupDecisionPayloadAppenderTest test
```

已通過：

```bash
./mvnw -q -DskipTests compile
```

已通過：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers test
```

已通過：

```bash
git diff --check
```

## 五、結論

Advance phase payload builder extraction 完成，沒有 blocker。

下一步建議先做 code review / commit checkpoint；commit 後補一份 payload builder cleanup batch acceptance review，或繼續評估 `prepareAdvancePhaseFollowup(...)` 的下一個低風險切片。
