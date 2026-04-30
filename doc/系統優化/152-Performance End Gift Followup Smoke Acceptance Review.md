# Performance End Gift Followup Smoke Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、範圍

本輪補上 APFC batch 後保留的 performance end Gift followup focused smoke 缺口。

## 二、完成內容

- 新增 `advancePhaseShouldCreatePerformanceEndGiftConfirmForBothPlayers()`。
- 同一條 integration smoke 覆蓋：
  - 自方 performance end Gift 建立 `PERFORMANCE_END_SELF` pending。
  - 對手 performance end Gift 建立 `PERFORMANCE_END_OPPONENT` pending。
  - phase 從 `PERFORMANCE` 推進到 `END`。
  - 雙方 pending resolve 後各自抽 1 張。
- 修正測試期待：performance end 後 phase 已為 `END`，不再用二次 `advancePhase(...)` 驗證 pending guard。

## 三、Allow / Block 對照

### Allow

- 只補 integration smoke。
- 使用既有 test fixture 與 pending resolve helper。
- 鎖住 advance phase followup creator 在 performance end 雙方 pending 的舊入口行為。

### Block

- 未改 production code。
- 未改 Gift preview 查詢。
- 未改 pending schema。
- 未改 pending context JSON shape。
- 未改 phase timing。

## 四、測試結果

已通過，需沙盒外 Docker/Testcontainers：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#advancePhaseShouldCreatePerformanceEndGiftConfirmForBothPlayers test
```

## 五、大檔尺寸變化

- `MatchActionServiceIntegrationTest.java`：`34,955` -> `35,106` 行，增加 `151` 行。
- 本步未改 production code。

## 六、下一步

進入 code review / commit checkpoint。

commit 後建議評估下一個 advance phase 低風險切片：

- 若繼續 advance phase，優先檢視 `buildAdvancePhasePayload(...)` call-site 是否能下沉到 facade / adapter。
- 若轉回 Gift pending shared helper cleanup，先做 planning，不同時碰 main step 與 attack 路徑。
