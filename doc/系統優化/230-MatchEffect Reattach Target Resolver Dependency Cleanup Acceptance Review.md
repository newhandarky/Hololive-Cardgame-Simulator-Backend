# AAA-230 MatchEffect Reattach Target Resolver Dependency Cleanup Acceptance Review

日期：2026-05-27

## Summary

本批延續 AAA-229，收斂 `MatchGiftReattachEffectExecutionService` 的 target resolver dependency wiring。

這批只調整 `REATTACH` execution service 的依賴形狀，不改 `REATTACH` source mode、target fallback、Cheer 搬移語意，不改 DB migration、REST / WebSocket public API。

## Key Changes

- `MatchGiftReattachEffectExecutionService` 直接持有 `MatchAddCheerTargetResolverService`。
- 移除 `MatchGiftReattachEffectExecutionService.PreferredAddCheerTargetResolver` functional interface。
- `MatchEffectService` constructor wiring 改傳 `addCheerTargetResolverService` instance，不再傳 method reference。
- `MatchGiftReattachEffectExecutionServiceTest` 改以 `MatchAddCheerTargetResolverService` mock 建立 service，對齊 production dependency shape。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`4,910` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftReattachEffectExecutionService.java`：`563` 行。
- `src/test/java/com/hololive/cardgame/service/MatchGiftReattachEffectExecutionServiceTest.java`：`213` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。

## Verification

- TDD red：`./mvnw -q -Dtest=MatchGiftReattachEffectExecutionServiceTest test` 初次失敗，原因是 test 先改傳 `MatchAddCheerTargetResolverService`，但 production constructor 仍要求 `PreferredAddCheerTargetResolver` callback。
- `./mvnw -q -Dtest=MatchGiftReattachEffectExecutionServiceTest test`：PASS。
- `./mvnw -q -Dtest=MatchGiftReattachEffectExecutionServiceTest,MatchCheerCandidateQueryServiceTest,MatchAddCheerSourceResolverServiceTest,MatchAddCheerTargetResolverServiceTest,MatchGiftAddCheerEffectExecutionServiceTest,MatchGiftEffectServiceHandlersTest,MatchGiftReattachEffectExecutionServiceTest,MatchGiftEffectDispatcherTest,MatchGiftEffectExecutionCoordinatorTest test`：PASS。
- `./mvnw -q -DskipTests compile`：PASS。
- 沙盒內 `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportShouldAttachCheerToTargetHolomem+collabHsd01009ShouldSendCheerAndMoveSelfToBackWhenDiceIsOne+collabHsd13015ShouldReturnStageCheerThenAddCheer test`：FAIL，原因是 Docker / PostgreSQL socket `Operation not permitted`，未進入應用行為。
- 提權重跑同一條 support / collab `ADD_CHEER` integration command：PASS。

## Notes

- `REATTACH` 仍保留自己的 dice / owner / source mode / Cheer row 搬移與 summary 組裝。
- Gift / Add Cheer / Reattach 目前都已改成直接持有已抽出的 source / target / candidate service，而不是透過 `MatchEffectService` callback 做這些解析。

## Next Step

下一批建議暫停 Gift / Add Cheer 線，回頭處理 `MatchActionService`：

- 先盤點 decision resolution flow、turn / phase lifecycle、board action orchestration 的邊界。
- 選低風險入口補 focused protection test。
- 不同批再抽第一個 `MatchAction*` helper / service，避免同時處理 action flow 重構與測試巨檔治理。
