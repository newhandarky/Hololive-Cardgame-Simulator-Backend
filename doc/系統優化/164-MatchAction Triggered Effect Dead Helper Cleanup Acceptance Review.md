# MatchAction Triggered Effect Dead Helper Cleanup Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本步回到 MatchAction legacy cleanup 路線，移除 `MatchActionService` 內已無呼叫點的 triggered-effect helper。

移除項目：

- `buildTriggeredEffectConfirmMessage(...)`
- `buildTriggeredEffectDeferredSummary(...)`

不包含：

- BLOOM triggered effect resolution
- COLLAB triggered effect resolution
- Gift deferred summary builder
- pending decision creation
- pending context JSON shape
- confirm message 文案調整

## 二、完成內容

- 刪除 `MatchActionService` 內兩個 dead private helpers。
- 保留實際仍在使用的 `BloomEffectResolutionService` / `CollabEffectResolutionService` 專屬 summary / message builder 邏輯。
- 確認 `MatchActionService` 內無 `buildTriggeredEffectConfirmMessage(...)` / `buildTriggeredEffectDeferredSummary(...)` 呼叫點。

## 三、Allow / Block 對照

### Allow

- 移除無呼叫點 private helper。
- 用 focused compile / unit tests 驗證無遺漏引用。

### Block

- 不新增新 builder。
- 不修改 BLOOM / COLLAB service 的 message 或 summary shape。
- 不修改 `hasBloomEffect` / `hasCollabEffect` payload key。
- 不修改 pending decision timing。
- 不修改任何 integration expectation。

## 四、測試結果

已通過：

- `./mvnw -q -Dtest=MatchActionServiceTest,BloomEffectResolutionServiceTest,CollabEffectResolutionServiceTest test`

## 五、大檔尺寸變化

- `MatchActionService.java`：`6,180` -> `6,141` 行，減少 `39` 行。

## 六、剩餘缺口

目前沒有本步 blocker。

保留缺口：

- `MatchActionService` 仍有其他 legacy helpers；後續應繼續以呼叫點與 use case 邊界評估，不做盲目搬移。
- 完整 `MatchActionServiceIntegrationTest` 仍有既有廣域不穩定，不作為本步 blocker。

## 七、結論

MatchAction triggered-effect dead helper cleanup 可視為完成。

本步只移除已無呼叫點的 legacy helper，未改 BLOOM / COLLAB / Gift 實際流程。

## 八、下一步

進入 code review / commit checkpoint。

commit 後建議繼續盤點 `MatchActionService` 末段 legacy helper；優先挑已有 service 接手、且 private helper 已變成 thin/dead adapter 的項目。
