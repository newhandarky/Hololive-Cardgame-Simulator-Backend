# Advance Phase Followup Preparation Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、範圍

本輪執行 APFC-2，將 advance phase transition preview preparation 與 followup creation 收斂到同一個 package-private creator。

## 二、完成內容

- `AdvancePhaseFollowupCreator` 注入 `MatchPhaseAdvanceGiftTransitionService`。
- 新增 `prepareAdvancePhaseFollowup(...)`，集中處理：
  - `transition == null` 時回傳 empty followup。
  - 呼叫 `prepareAdvancePhaseTransition(...)` 取得 Gift preview。
  - 委派既有 `createAdvancePhaseFollowup(...)` 建立 own / opponent pending decision。
- `MatchActionService.advancePhase(...)` 直接委派 creator，不再保留 private `prepareAdvancePhaseFollowup(...)` helper。
- 擴充 `AdvancePhaseFollowupCreatorTest`，覆蓋 transition null 與 transition preview preparation 資料流。

## 三、Allow / Block 對照

### Allow

- 已 adapter 化 advance phase followup preparation。
- `MatchPhaseAdvanceGiftTransitionService` 仍持有 performance start / end Gift preview 實作。
- `MatchActionService.advancePhase(...)` 仍保留 phase resolve、payload build、turn lifecycle advance orchestration。

### Block

- 未改 Gift preview 查詢內容。
- 未改 performance start / performance end trigger timing。
- 未改 pending decision schema。
- 未改 Gift pending context JSON shape。
- 未改 main step Gift pending creation。
- 未改 attack Gift pending creation。

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

- `MatchActionService.java`：`6,261` -> `6,246` 行，減少 `15` 行。
- `AdvancePhaseFollowupCreator.java`：`65` -> `95` 行。

## 六、下一步

進入 code review / commit checkpoint。

commit 後建議收束 APFC batch acceptance review，確認 APFC-1 / APFC-2 已完整關閉；後續再評估下一個 advance phase 切片或轉回 Gift pending shared helper cleanup。
