# Main Step Gift Pending Smoke Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本文件驗收 main step Gift pending focused smoke baseline。

本步只執行既有 integration smoke，未改 production code。

## 二、驗證案例

使用既有測試：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#mainStepGiftHsd13013ShouldArchiveStackCostAndAttachCheerFromDeckTop test
```

此案例涵蓋：

- main step Gift 建立 `TRIGGER_EFFECT_CONFIRM` pending
- pending context 包含 `HSD13-013`
- pending context 包含 `triggerType = MAIN_STEP_SELF`
- resolve 後建立 `GIFT_TRIGGER` action payload
- stack cost 進 ARCHIVE
- deck top cheer attach 成功

## 三、執行結果

第一次在 sandbox 內執行失敗，原因為環境無法連 Docker/Testcontainers / PostgreSQL：

- Docker socket `Operation not permitted`
- Flyway 無法連測試資料庫

改在 sandbox 外執行後通過。

## 四、Allow / Block 對照

### Allow

- 將此測試列為 main step Gift pending baseline。
- 後續若碰 `appendMainStepGiftFollowupPayload(...)` 或 `createGiftTriggerDecisionWithoutSourceCard(...)`，應優先跑此 focused smoke。

### Block

- 不改 Gift pending context JSON shape。
- 不改 `MAIN_STEP_SELF` trigger type。
- 不改 stack cost archive 行為。
- 不改 cheer attach 行為。
- 不把完整 `MatchActionServiceIntegrationTest` 當作本批 blocker。

## 五、結論

main step Gift pending smoke baseline 可用。

下一步建議執行 advance phase Gift focused smoke：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers test
```

若通過，再補 advance phase Gift pending smoke acceptance review。
