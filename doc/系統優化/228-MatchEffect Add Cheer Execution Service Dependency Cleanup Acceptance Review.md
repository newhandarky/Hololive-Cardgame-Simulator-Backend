# AAA-228 MatchEffect Add Cheer Execution Service Dependency Cleanup Acceptance Review

日期：2026-05-27

## Summary

本批延續 AAA-227，收斂 `MatchGiftAddCheerEffectExecutionService` 的 ADD_CHEER source / target dependency wiring。

這批只調整 execution service 的依賴形狀，不改 `ADD_CHEER` 規則語意、不改 `SendCheerAction` side effect、不改 DB migration、REST / WebSocket public API。

## Key Changes

- `MatchGiftAddCheerEffectExecutionService` 直接持有 `MatchAddCheerTargetResolverService`。
- `MatchGiftAddCheerEffectExecutionService` 直接持有 `MatchAddCheerSourceResolverService`。
- 移除 `MatchGiftAddCheerEffectExecutionService` 內 ADD_CHEER 專用的 `PreferredAddCheerTargetResolver` functional interface。
- 移除 `MatchGiftAddCheerEffectExecutionService` 內 ADD_CHEER 專用的 `PreferredAddCheerSourceResolver` functional interface。
- `MatchEffectService` constructor wiring 改傳 source / target resolver service instance，不再傳 method reference。
- `MatchAddCheerSourceResolverService` 與 `MatchAddCheerTargetResolverService` 從 package-private `final` class 放寬為 package-private class，讓 focused unit test 可 mock 這兩個 service dependency。
- 更新 `MatchGiftAddCheerEffectExecutionServiceTest` 與 `MatchGiftEffectServiceHandlersTest` 的 constructor setup。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`4,939` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftAddCheerEffectExecutionService.java`：`202` 行。
- `src/main/java/com/hololive/cardgame/service/MatchAddCheerSourceResolverService.java`：`131` 行。
- `src/main/java/com/hololive/cardgame/service/MatchAddCheerTargetResolverService.java`：`251` 行。
- `src/test/java/com/hololive/cardgame/service/MatchGiftAddCheerEffectExecutionServiceTest.java`：`153` 行。
- `src/test/java/com/hololive/cardgame/service/MatchGiftEffectServiceHandlersTest.java`：`74` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。

## Verification

- `./mvnw -q -Dtest=MatchGiftAddCheerEffectExecutionServiceTest,MatchGiftEffectServiceHandlersTest test`：初次 FAIL，原因是 Mockito 無法 mock package-private final resolver class；未進入 ADD_CHEER 行為。
- 移除 source / target resolver 的 `final` 後，`./mvnw -q -Dtest=MatchGiftAddCheerEffectExecutionServiceTest,MatchGiftEffectServiceHandlersTest test`：PASS。
- `./mvnw -q -Dtest=MatchAddCheerSourceResolverServiceTest,MatchAddCheerTargetResolverServiceTest,MatchGiftAddCheerEffectExecutionServiceTest,MatchGiftEffectServiceHandlersTest,MatchGiftReattachEffectExecutionServiceTest,MatchGiftEffectDispatcherTest,MatchGiftEffectExecutionCoordinatorTest test`：PASS。
- `./mvnw -q -DskipTests compile`：PASS。
- 沙盒內 `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportShouldAttachCheerToTargetHolomem+collabHsd01009ShouldSendCheerAndMoveSelfToBackWhenDiceIsOne+collabHsd13015ShouldReturnStageCheerThenAddCheer test`：FAIL，原因是 Docker / PostgreSQL socket `Operation not permitted`，未進入應用行為。
- 提權重跑同一條 support / collab `ADD_CHEER` integration command：PASS。

## Notes

- `MatchGiftReattachEffectExecutionService` 仍以 callback 使用 `findCheerCardFromZone(...)`。
- `MatchAddCheerSourceResolverService` 仍以 callback 使用 `findCheerCardFromZone(...)`。
- 下一批若繼續收斂 Gift，可將共用 Cheer candidate lookup 抽成 query service，降低 `MatchEffectService` 對 reattach / add-cheer source 的查詢耦合。

## Next Step

下一批建議抽 `MatchCheerCandidateQueryService`：

- 搬移 `findCheerCardFromZone(...)`。
- 搬移或合併 add-cheer fallback attachable Cheer candidate 查詢。
- 讓 `MatchGiftReattachEffectExecutionService` 與 `MatchAddCheerSourceResolverService` 直接依賴新 query service。
