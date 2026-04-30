# Main Step / Advance Phase Gift Pending Smoke Batch Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本批範圍

本文件收束 `141` 至 `143` 的 main step / advance phase Gift pending smoke batch：

- `141-Main Step Advance Phase Gift Pending Smoke Planning.md`
- `142-Main Step Gift Pending Smoke Acceptance Review.md`
- `143-Advance Phase Gift Pending Smoke Acceptance Review.md`

本批目標是確認 `createGiftTriggerDecisionWithoutSourceCard(...)` 相關的 main step 與 advance phase Gift pending flow 有 focused integration smoke 可用。

## 二、完成條件對照

### Smoke planning

已完成。

- 盤點 main step Gift pending call site。
- 盤點 advance phase own / opponent Gift pending call site。
- 確認 `106` 已完成 sourceless Gift pending adapter 命名收斂。
- 本批不再重命名或移除 adapter。

### Main step Gift smoke

已完成。

通過：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#mainStepGiftHsd13013ShouldArchiveStackCostAndAttachCheerFromDeckTop test
```

驗證重點：

- `TRIGGER_EFFECT_CONFIRM` pending
- `triggerType = MAIN_STEP_SELF`
- `GIFT_TRIGGER` action payload
- stack cost archive
- deck top cheer attach

### Advance phase Gift smoke

已完成。

通過：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers test
```

驗證重點：

- `MAIN -> PERFORMANCE`
- own Gift pending
- opponent Gift pending
- `PERFORMANCE_START_SELF`
- `PERFORMANCE_START_OPPONENT`
- pending 未 resolve 前阻擋再次 advance phase
- resolve 後 phase 維持 `PERFORMANCE`

## 三、Allow / Block 清單

### Allow

- 後續若碰 `appendMainStepGiftFollowupPayload(...)`，優先跑 main step Gift smoke。
- 後續若碰 `createAdvancePhaseFollowup(...)` 或 `buildAdvancePhasePayload(...)`，優先跑 advance phase Gift smoke。
- 後續若碰 `createGiftTriggerDecisionWithoutSourceCard(...)`，兩個 smoke 都應跑。

### Block

- 不重命名 `createGiftTriggerDecisionWithoutSourceCard(...)`。
- 不移除 main step / advance phase local adapter。
- 不改 Gift pending context JSON shape。
- 不改 `MAIN_STEP_SELF`、`PERFORMANCE_START_SELF`、`PERFORMANCE_START_OPPONENT` trigger type。
- 不改 phase transition timing。
- 不改 pending resolve 行為。
- 不把完整 `MatchActionServiceIntegrationTest` 當作本批 blocker。

## 四、環境注意

這兩個 smoke 都需要 Docker/Testcontainers 與 PostgreSQL。

若 sandbox 內無法連 Docker socket，應使用 sandbox 外測試流程；sandbox Docker 權限失敗不代表測試案例本身失敗。

## 五、結論

Main step / advance phase Gift pending smoke batch 可收束。

下一步建議回到 MatchAction legacy cleanup 路線，優先挑不改規則的小切片；可評估 `buildAdvancePhasePayload(...)` payload append ownership，或轉向下一個已規劃的低風險 helper cleanup。
