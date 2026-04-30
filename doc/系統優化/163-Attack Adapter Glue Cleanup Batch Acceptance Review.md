# Attack Adapter Glue Cleanup Batch Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本批範圍

本文件收束 `160` 至 `162` 的 attack adapter glue cleanup batch：

- `160-Attack Art Post Trigger Deferred Summary Builder Acceptance Review.md`
- `161-Attack Performance State Updater Acceptance Review.md`
- `162-Attack Application Stage Records Acceptance Review.md`

本批目標是降低 attack post-trigger / adapter factory 的膠水密度，而不是改 attack 主規則。

## 二、完成內容

- 新增 `AttackArtPostTriggerDeferredSummaryBuilder`，下沉 `ATTACK_ART_POST_TRIGGER` deferred summary / down event section / Gift trigger section 組裝。
- `AttackPostTriggerPendingService` 保留 pending orchestration，只委派 summary shape 建立。
- 新增 `AttackPerformanceStateUpdater`，下沉 attacker rest、performance phase save、updated_at touch 與 next action availability 查詢。
- `AttackArtApplicationAdapterFactory` 的 rest-and-save 副作用改委派 updater。
- 新增 `AttackApplicationStages.java`，集中 package-private stage result records。
- `MatchActionService.attackArt(...)` 改直接使用 package-private `AttackApplicationRestPayloadStage.class`。

## 三、Allow / Block 對照

### Allow

- 收斂 attack post-trigger summary builder。
- 收斂 attack performance state side effect helper。
- 搬移 attack application stage result records。
- 保留 package-private 可見度，不擴大 public API。
- 用 focused unit tests 鎖定 stage order、rest/save timing 與 summary shape。

### Block

- 不改 attack cost / target / damage / down / Gift 規則。
- 不改 `ATTACK_ART` action log action type。
- 不改 action log timing。
- 不改 finish check timing。
- 不改 pending decision timing。
- 不改 stage constants / stage execution order。
- 不改 `AttackRestAndPayloadContext` 或 payload shape。
- 不在本批拆 resolver provider；目前 resolver 仍高度依賴 factory 既有 dependency graph，強拆收益不足。
- 不修完整 `MatchActionServiceIntegrationTest` 既有廣域不穩定案例。

## 四、測試結果

已通過：

- `./mvnw -q -Dtest=AttackArtPostTriggerDeferredSummaryBuilderTest,AttackPostTriggerPendingServiceTest test`
- `./mvnw -q -Dtest=AttackPerformanceStateUpdaterTest,AttackArtApplicationAdapterFactoryTest test`
- `./mvnw -q -Dtest=AttackArtApplicationServiceTest,AttackArtApplicationAdapterFactoryTest test`
- `git diff --check`

## 五、大檔尺寸變化

- `AttackPostTriggerPendingService.java`：`183` -> `82` 行，減少 `101` 行。
- `AttackArtApplicationAdapterFactory.java`：`740` -> `640` 行，減少 `100` 行。
- `MatchActionService.java`：`6,180` -> `6,180` 行，行數不變。
- 新增 `AttackArtPostTriggerDeferredSummaryBuilder.java`：`115` 行。
- 新增 `AttackPerformanceStateUpdater.java`：`55` 行。
- 新增 `AttackApplicationStages.java`：`79` 行。

## 六、剩餘缺口

目前沒有本批 blocker。

保留缺口：

- `AttackArtApplicationAdapterFactory` 仍有多個 inner stage resolver。
- 若後續要拆 resolver provider，需先規劃 dependency grouping，避免只把 constructor 複雜度搬到另一個檔案。
- 完整 `MatchActionServiceIntegrationTest` 仍有既有廣域不穩定，不作為本批 blocker。

## 七、結論

attack adapter glue cleanup batch 可收束。

本批降低了 post-trigger pending service 與 adapter factory 的資料組裝 / 副作用 / stage data 宣告密度，且未改 attack 規則、payload、pending timing、action log timing 或 finish check timing。

## 八、下一步

進入 code review / commit checkpoint。

commit 後建議回到路線圖盤點下一個高收益 cleanup：優先評估 `AttackArtApplicationAdapterFactory` resolver provider 是否值得另開 planning；若收益不足，轉向其他 legacy lifecycle / pending cleanup batch。
