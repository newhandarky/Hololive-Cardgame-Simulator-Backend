# AAA-225 MatchEffect Add Cheer Bridge Cleanup Acceptance Review

日期：2026-05-27

## Summary

本批延續 AAA-224，清理 `ADD_CHEER` 的薄 bridge。

這批只調整 service / dispatcher 接線，不改 `ADD_CHEER` 規則、不改 DB migration、REST / WebSocket public API。target/source resolver 仍暫留 `MatchEffectService` callback。

## Key Changes

- `MatchBloomEffectDispatcher` 直接持有 `MatchGiftAddCheerEffectExecutionService`。
- `MatchCollabEffectDispatcher` 直接持有 `MatchGiftAddCheerEffectExecutionService`。
- `MatchGiftEffectServiceHandlers` 直接持有 `MatchGiftAddCheerEffectExecutionService`。
- `MatchEffectService.applySupportEffect(...)` 的 `ADD_CHEER` 分支直接呼叫新 service。
- 移除 `MatchEffectService.executeAddCheerEffect(...)` 薄 delegate。
- 新增 `MatchGiftEffectServiceHandlersTest`，鎖定 Gift handler 的 `ADD_CHEER` 不再觸碰 `MatchEffectService`。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`5,215` 行。
- `src/main/java/com/hololive/cardgame/service/MatchBloomEffectDispatcher.java`：`355` 行。
- `src/main/java/com/hololive/cardgame/service/MatchCollabEffectDispatcher.java`：`345` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftEffectServiceHandlers.java`：`137` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftAddCheerEffectExecutionService.java`：`216` 行。
- `src/test/java/com/hololive/cardgame/service/MatchGiftEffectServiceHandlersTest.java`：`61` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。

## Verification

- TDD red：`./mvnw -q -Dtest=MatchGiftEffectServiceHandlersTest test` 初次失敗，原因是 `MatchGiftEffectServiceHandlers` constructor 尚未注入 `MatchGiftAddCheerEffectExecutionService`。
- `./mvnw -q -Dtest=MatchGiftEffectServiceHandlersTest test`：PASS。
- `./mvnw -q -Dtest=MatchGiftEffectServiceHandlersTest,MatchGiftAddCheerEffectExecutionServiceTest,MatchGiftReattachEffectExecutionServiceTest,MatchGiftEffectDispatcherTest,MatchGiftEffectExecutionCoordinatorTest test`：PASS。
- `./mvnw -q -DskipTests compile`：PASS。
- 沙盒內 `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportShouldAttachCheerToTargetHolomem+collabHsd01009ShouldSendCheerAndMoveSelfToBackWhenDiceIsOne+collabHsd13015ShouldReturnStageCheerThenAddCheer test`：FAIL，原因是 Docker / PostgreSQL socket `Operation not permitted`，未進入應用行為。
- 提權重跑同一條 support / collab `ADD_CHEER` integration command：PASS。

## Notes

- `MatchEffectService` 仍提供 `resolvePreferredAddCheerTargetHolomemId(...)`、`resolvePreferredAddCheerSource(...)`、`resolveCheerCount(...)`、`resolveCurrentTurnNumber(...)`、`resolveHolomemCardInstanceId(...)` callback 給 `MatchGiftAddCheerEffectExecutionService`。
- 本批只清理 bridge，沒有搬移 target/source resolver，也沒有改變 attach summary。

## Next Step

下一批建議拆 `ADD_CHEER` target resolver：

- 新增 `MatchAddCheerTargetResolverService`。
- 搬移 `resolvePreferredAddCheerTargetHolomemId(...)`、`findPreferredOwnedStageHolomemId(...)`、`resolveRequiredAddCheerTargetZone(...)`、`resolveRequiredAddCheerTargetLevelType(...)`。
- 先保留 source resolver 與 `findAttachableCheerCard(...)` 在 `MatchEffectService`，避免目標選擇與來源候選同批變動。
