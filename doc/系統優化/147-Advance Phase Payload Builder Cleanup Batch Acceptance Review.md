# Advance Phase Payload Builder Cleanup Batch Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本批範圍

本文件收束 `145` 至 `146` 的 advance phase payload builder cleanup batch：

- `145-Advance Phase Payload Builder Planning.md`
- `146-Advance Phase Payload Builder Acceptance Review.md`

本批目標是把 `MatchActionService.buildAdvancePhasePayload(...)` 內的 payload construction 搬到 package-private builder，同時保留 advance phase orchestration 在 `MatchActionService`。

## 二、完成條件對照

### APPB-1：Planning

已完成。

- 盤點 `buildAdvancePhasePayload(...)` 的既有責任。
- 明確切分 payload construction 與 phase orchestration。
- 定義 allow/block 清單。
- 確認 main step / advance phase smoke baseline 可用。

### APPB-2：Production slice

已完成。

- 新增 `AdvancePhasePayloadBuilder`。
- 新增 `AdvancePhasePayloadBuilderTest`。
- `MatchActionService.buildAdvancePhasePayload(...)` 改為 thin adapter。
- 保留 `createAdvancePhaseFollowup(...)`、Gift preview、pending creation 與 pending resolve 行為。

## 三、Allow / Block 對照

### Allow

- payload construction 由 `AdvancePhasePayloadBuilder` 承接。
- `MatchActionService` 保留 private `AdvancePhaseFollowup` 與 orchestration。
- 後續若只碰 payload shape，優先補 `AdvancePhasePayloadBuilderTest`。

### Block

- 不改 phase transition timing。
- 不改 Gift trigger preview。
- 不改 Gift pending context JSON shape。
- 不改 pending decision creation。
- 不改 pending resolve 行為。
- 不移除 main step / advance phase smoke baseline。

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

Advance phase payload builder cleanup batch 可收束。

下一步建議評估 `prepareAdvancePhaseFollowup(...)` / `createAdvancePhaseFollowup(...)` 的下一個低風險切片；優先做 planning 或 focused test，不直接搬 Gift preview / pending creation。
