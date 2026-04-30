# Advance Phase Gift Pending Smoke Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本文件驗收 advance phase Gift pending focused smoke baseline。

本步只執行既有 integration smoke，未改 production code。

## 二、驗證案例

使用既有測試：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers test
```

此案例涵蓋：

- `MAIN -> PERFORMANCE` phase transition
- own Gift pending 建立
- opponent Gift pending 建立
- pending context 包含 `PERFORMANCE_START_SELF`
- pending context 包含 `PERFORMANCE_START_OPPONENT`
- pending 未 resolve 前會阻擋再次 advance phase
- resolve 後 phase 維持 `PERFORMANCE`
- own / opponent Gift resolve 後各自抽牌

## 三、執行結果

已在 sandbox 外執行並通過。

此測試需要 Docker/Testcontainers 與 PostgreSQL，因此應在允許連 Docker socket 的環境下執行。

## 四、Allow / Block 對照

### Allow

- 將此測試列為 advance phase Gift pending baseline。
- 後續若碰 `createAdvancePhaseFollowup(...)`、`buildAdvancePhasePayload(...)` 或 `createGiftTriggerDecisionWithoutSourceCard(...)`，應優先跑此 focused smoke。

### Block

- 不改 Gift pending context JSON shape。
- 不改 `PERFORMANCE_START_SELF` / `PERFORMANCE_START_OPPONENT` trigger type。
- 不改 phase transition timing。
- 不改 pending resolve block 行為。
- 不把完整 `MatchActionServiceIntegrationTest` 當作本批 blocker。

## 五、結論

advance phase Gift pending smoke baseline 可用。

main step / advance phase Gift pending smoke baseline 目前都已確認可跑。

下一步建議補一份 smoke batch acceptance review，收束 `141` 至 `143`，再回到下一個 MatchAction legacy cleanup 小切片。
