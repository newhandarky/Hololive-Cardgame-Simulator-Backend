# MatchEffect Gift Effect Dispatcher Extraction Acceptance Review

更新日期：2026-05-26

## Summary

AAA-220 將 Gift effect type 的 switch routing 從 `MatchEffectService.executeGiftEffectByType(...)` 搬到 package-private `MatchGiftEffectDispatcher`。

這批不改 DB migration、REST / WebSocket public API，也不改 Gift 執行順序或規則語意；仍高耦合的副作用 handler 先透過 `MatchGiftEffectServiceHandlers` 回接 `MatchEffectService`。

## Key Changes

- 新增 `MatchGiftEffectDispatcher`：
  - 集中 Gift effect type routing。
  - 已抽出的低耦合 execution services 直接注入 dispatcher。
  - 未支援 effect 維持丟 `UnsupportedOperationException("UNSUPPORTED_GIFT_EFFECT")`，由 coordinator 收斂 skipped summary。
- 新增 `MatchGiftEffectServiceHandlers`：
  - 回接 `executeAddCheerEffect(...)`、`executeDamageEffect(...)`、`executeReattachEffect(...)`、`executeArchiveStackCardEffect(...)`、`executeBuffDebuffEffect(...)` 等尚未拆出的高耦合 handler。
  - `executeReplaceArchiveWithHandEffect(...)` 從 private 放寬為 package-private，供 adapter 呼叫。
- 修改 `MatchEffectService`：
  - 建立 `giftEffectDispatcher` 欄位。
  - `executeGiftEffectByType(...)` 改為單純委派 dispatcher。
- 新增 `MatchGiftEffectDispatcherTest`：
  - 覆蓋低耦合 routing。
  - 覆蓋 `DAMAGE` 以 inferred target type 呼叫 high-coupling handler。
  - 覆蓋 `UNIMPLEMENTED` no-op handler。
  - 覆蓋 unknown effect 維持 unsupported exception。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`5,841` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftEffectDispatcher.java`：`257` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftEffectServiceHandlers.java`：`126` 行。
- `src/test/java/com/hololive/cardgame/service/MatchGiftEffectDispatcherTest.java`：`104` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。

## Verification

- TDD red：
  - `./mvnw -q -Dtest=MatchGiftEffectDispatcherTest test`
  - 初次執行因 `MatchGiftEffectDispatcher` 尚未存在而 compilation failed。
- Focused unit：
  - `./mvnw -q -Dtest=MatchGiftEffectDispatcherTest test`：pass。
  - `./mvnw -q -Dtest=MatchGiftEffectDispatcherTest,MatchGiftEffectExecutionCoordinatorTest,MatchEffectTypeInferenceServiceTest test`：pass。
- Compile：
  - `./mvnw -q -DskipTests compile`：pass。
- Focused integration：
  - 沙盒內 `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerOfficialGiftHbp05035WhenOwnSakuraMikoDowned+mainStepGiftHsd13013ShouldArchiveStackCostAndAttachCheerFromDeckTop+mainStepGiftHsd13013ShouldNotAttachCheerWithoutStackCost+advancePhaseShouldSkipOfficialGiftHsd11006WhenHandHasNoMatchingFlowGlowMember test` 因 Docker / PostgreSQL socket `Operation not permitted` 失敗。
  - 相同 command 提權重跑：pass。
- Cleanup check：
  - `docker ps`：確認沒有殘留本批 Testcontainers PostgreSQL container。
- Diff hygiene：
  - `git diff --check`：pass。

## Notes

- 本批只拆 Gift routing，不搬 `applySupportEffect(...)`。
- `MatchGiftEffectServiceHandlers` 是過渡 adapter；後續每拆出一個高耦合 handler，就可以從 adapter 中移除一個 callback。

## Next Step

下一批建議做 AAA-221：抽出 Gift archive 回手效果執行器。

目標是搬移 `executeReplaceArchiveWithHandEffect(...)` 與 `loadLatestHoloxArchivedSupportCardInstanceIds(...)`，建立 package-private `MatchGiftArchiveReturnEffectExecutionService`，先處理 HBP02-039 類 Archive 支援卡回手流程，再評估 `ADD_CHEER` / `REATTACH` 這類較大的 Gift handler。
