# Advance Phase Followup Creator Planning

日期：2026-04-30
狀態：規劃完成

## 一、背景

`AdvancePhasePayloadBuilder` 已承接 advance phase action payload construction。

目前 `MatchActionService` 仍保留 advance phase Gift followup orchestration：

- `prepareAdvancePhaseFollowup(...)`
- `createAdvancePhaseFollowup(...)`
- private record `AdvancePhaseFollowup`

其中 `createAdvancePhaseFollowup(...)` 同時負責：

- 接收 `GiftTransitionPreview`
- 讀取 own Gift effects
- 建立 own Gift pending decision
- 讀取 opponent Gift effects
- 在 opponent 存在時建立 opponent Gift pending decision
- 組成 `AdvancePhaseFollowup`

這段邏輯牽涉 Gift preview 結果與 pending decision creation，不適合直接大搬移。

## 二、目標

下一輪優先目標是補 focused unit baseline，鎖住 `createAdvancePhaseFollowup(...)` 的資料流，再決定是否抽 builder / creator。

建議候選：

- `AdvancePhaseFollowupCreator`
- 或先保留 private helper，只補 `MatchActionService` adapter smoke / unit baseline

## 三、Allow / Block 清單

### Allow

- 補 focused tests 鎖住 own / opponent pending decision creation。
- 若要抽 production，先只抽 package-private creator，並由 `MatchActionService` 注入 `GiftPendingDecisionCreator` 或 adapter。
- 保留 `MatchPhaseAdvanceGiftTransitionService.prepareAdvancePhaseTransition(...)` 的 Gift preview ownership。
- 保留 `MatchActionService.advancePhase(...)` orchestration ownership。

### Block

- 不改 performance start / performance end Gift preview。
- 不改 `createGiftTriggerDecisionWithoutSourceCard(...)` 行為。
- 不改 Gift pending context JSON shape。
- 不改 pending decision schema。
- 不改 phase transition timing。
- 不改 pending resolve 行為。
- 不把 attack / main step Gift 一起搬進同一個切片。

## 四、建議分批

### APFC-1：Focused baseline

先補測試，鎖住：

- transition preview 為 `null` 時回傳 empty followup。
- own Gift effects 會建立 own pending decision。
- opponent user 為 `null` 時不建立 opponent pending decision。
- opponent Gift effects 會建立 opponent pending decision。

若 private helper 不容易直接測，可先抽出 package-private creator，但 production 行為必須完全等價。

### APFC-2：Creator extraction

在 APFC-1 有 baseline 後，才評估新增：

- `AdvancePhaseFollowupCreator`
- `AdvancePhaseFollowup`

注意：若要把 private record 搬出 `MatchActionService`，需同步更新 `AdvancePhasePayloadBuilder` adapter 邊界。

### APFC-3：Acceptance review

收束 allow/block 清單與 focused smoke：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers test
```

## 五、下一步

進入 code review / commit checkpoint。

commit 後建議先做 APFC-1，優先選擇 focused baseline；若需要 production extraction，切面必須只限 advance phase followup creation。
