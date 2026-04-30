# Advance Phase Followup Creator Batch Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、範圍

本文件收束 `148` 規劃下的 APFC batch：

- APFC-1：Focused baseline / creator extraction
- APFC-2：Transition preview preparation adapter
- APFC-3：Batch acceptance review

## 二、完成條件對照

### APFC-1

- 已新增 `AdvancePhaseFollowupCreator`。
- 已新增 package-private `AdvancePhaseFollowup`。
- 已以 focused unit test 鎖住：
  - `transitionPreview == null` 回傳 empty followup。
  - own Gift effects 建立 own pending decision。
  - opponent user 為 `null` 時不建立 opponent pending decision。
  - opponent Gift effects 建立 opponent pending decision。
- `MatchActionService` 內部 `createAdvancePhaseFollowup(...)` 已移除。

### APFC-2

- `AdvancePhaseFollowupCreator` 已接手 `prepareAdvancePhaseFollowup(...)`。
- transition null 判斷已移入 creator。
- transition preview preparation 已移入 creator。
- `MatchActionService` 不再保留 private `prepareAdvancePhaseFollowup(...)` helper。

### APFC-3

- 已完成 allow/block 對照。
- 已保留舊入口 smoke。
- 未發現 blocker。

## 三、舊入口 Allow / Block 對照

### Allow

- `MatchActionService.advancePhase(...)` 可委派 advance phase followup preparation。
- `AdvancePhasePayloadBuilder` 可繼續消費 `AdvancePhaseFollowup` 的 own / opponent effects 與 decisions。
- `MatchPhaseAdvanceGiftTransitionService` 保留 Gift preview 查詢與 performance phase snapshot ownership。

### Block

- 未改 phase transition timing。
- 未改 performance start / performance end Gift preview 查詢語意。
- 未改 pending decision schema。
- 未改 Gift pending context JSON shape。
- 未改 main step Gift pending creation。
- 未改 attack Gift pending creation。
- 未改 pending resolve 行為。

## 四、測試缺口

目前沒有 APFC blocker。

保留風險：

- 目前 focused integration smoke 只覆蓋 performance start both players。
- performance end Gift followup 仍依賴 `AdvancePhasePayloadBuilderTest` 與既有整合測試間接覆蓋。
- 若後續更動 `MatchPhaseAdvanceGiftTransitionService` 的 preview 查詢，應補 performance end 專用 focused smoke。

## 五、驗證結果

APFC-1 / APFC-2 已通過：

```bash
./mvnw -q -Dtest=AdvancePhaseFollowupCreatorTest,AdvancePhasePayloadBuilderTest test
```

舊入口 focused smoke 已通過，需沙盒外 Docker/Testcontainers：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers test
```

## 六、大檔尺寸變化

APFC batch 前後：

- `MatchActionService.java`：`6,306` -> `6,246` 行，減少 `60` 行。
- `AdvancePhaseFollowupCreator.java`：新增後目前 `95` 行。
- `AdvancePhaseFollowup.java`：新增後目前 `15` 行。

## 七、下一步

進入 code review / commit checkpoint。

commit 後建議下一輪優先評估 advance phase 的下一個低風險切片：

- `buildAdvancePhasePayload(...)` 呼叫點是否可轉成更薄的 adapter method。
- 或補 performance end Gift followup focused smoke，再決定是否碰 `MatchPhaseAdvanceGiftTransitionService`。

若不繼續 advance phase，次佳方向是回到 Gift pending shared helper cleanup，但應避免同時改 main step 與 attack 路徑。
