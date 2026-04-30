# Advance Phase Followup Creator Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、範圍

本輪執行 APFC-1 focused baseline，目標是鎖住 advance phase Gift followup creation 的資料流，並做最小等價抽取。

## 二、完成內容

- 新增 `AdvancePhaseFollowupCreator`。
- 新增 package-private `AdvancePhaseFollowup` record。
- `MatchActionService.prepareAdvancePhaseFollowup(...)` 改為委派 creator。
- 移除 `MatchActionService` 內部 private record 與 `createAdvancePhaseFollowup(...)` helper。
- 新增 `AdvancePhaseFollowupCreatorTest`，覆蓋：
  - `transitionPreview == null` 回傳 empty followup。
  - own Gift effects 建立 own pending decision，且不帶 source card。
  - opponent user 為 `null` 時不建立 opponent pending decision。
  - opponent user 存在時建立 opponent pending decision。

## 三、Allow / Block 對照

### Allow

- 已補 focused unit baseline。
- 已用 package-private creator 承接 advance phase followup creation。
- 保留 `MatchPhaseAdvanceGiftTransitionService.prepareAdvancePhaseTransition(...)` 的 Gift preview ownership。
- 保留 `MatchActionService.advancePhase(...)` orchestration ownership。

### Block

- 未改 performance start / performance end Gift preview。
- 未改 `createGiftTriggerDecisionWithoutSourceCard(...)` 的 main step 路徑。
- 未改 Gift pending context JSON shape。
- 未改 pending decision schema。
- 未改 phase transition timing。
- 未改 pending resolve 行為。
- 未混入 attack / main step Gift followup 搬移。

## 四、測試結果

已通過：

```bash
./mvnw -q -Dtest=AdvancePhaseFollowupCreatorTest,AdvancePhasePayloadBuilderTest test
```

已通過，需沙盒外 Docker/Testcontainers：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers test
```

## 五、大檔尺寸變化

- `MatchActionService.java`：`6,306` -> `6,261` 行，減少 `45` 行。
- 新增 `AdvancePhaseFollowupCreator.java`：`65` 行。
- 新增 `AdvancePhaseFollowup.java`：`15` 行。

## 六、下一步

進入 code review / commit checkpoint。

commit 後建議進入 APFC-2，評估是否進一步把 `prepareAdvancePhaseFollowup(...)` 的 transition preview preparation adapter 化；下一刀仍應限制在 advance phase followup，不碰 main step / attack Gift pending creation。
