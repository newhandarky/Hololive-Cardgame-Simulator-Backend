# Advance Phase Payload Followup Adapter Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、範圍

本輪收斂 `buildAdvancePhasePayload(...)` call-site，讓 `MatchActionService` 不再拆解 `AdvancePhaseFollowup` 內容。

## 二、完成內容

- `AdvancePhasePayloadBuilder` 新增接收 `AdvancePhaseFollowup` 的 package-private method。
- `AdvancePhasePayloadBuilder` 內部負責 null followup fallback 與 own / opponent effects / decisions 拆解。
- 原本接收 effects / decisions 的 builder method 改為 private。
- `MatchActionService.advancePhase(...)` 直接委派 `advancePhasePayloadBuilder.buildAdvancePhasePayload(...)`。
- 移除 `MatchActionService` 的 private `buildAdvancePhasePayload(...)` helper。
- 更新 `AdvancePhasePayloadBuilderTest`，改以 `AdvancePhaseFollowup` 驗證 payload contract。

## 三、Allow / Block 對照

### Allow

- 下沉 advance phase payload followup adapter。
- 保留 payload JSON keys 與 pending decision append 行為。
- 保留 `AdvancePhaseFollowupCreator` ownership。
- 保留 `MatchTurnLifecycleService.advancePhase(...)` 呼叫點。

### Block

- 未改 phase resolve。
- 未改 Gift preview。
- 未改 pending decision schema。
- 未改 Gift pending context JSON shape。
- 未改 main step / attack Gift pending path。

## 四、測試結果

已通過：

```bash
./mvnw -q -Dtest=AdvancePhasePayloadBuilderTest,AdvancePhaseFollowupCreatorTest test
```

已通過，需沙盒外 Docker/Testcontainers：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers,MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceEndGiftConfirmForBothPlayers test
```

## 五、大檔尺寸變化

- `MatchActionService.java`：`6,246` -> `6,233` 行，減少 `13` 行。
- `AdvancePhasePayloadBuilder.java`：`50` -> `68` 行。

## 六、下一步

進入 code review / commit checkpoint。

commit 後建議評估 advance phase 是否還有值得切的小 helper；若剩下切片價值偏低，建議轉回 Gift pending shared helper cleanup planning。
