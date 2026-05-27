# MatchAction Pending Decision Creation Service Extraction Acceptance Review

日期：2026-05-27

## Summary

- 從 `MatchActionService` 與 `MatchTurnLifecycleService` 抽出 package-private `PendingDecisionCreationService`。
- 集中 `TURN_START`、`LIVE_START`、`DRAW_REVEAL`、`SEND_CHEER`、`CARD_SELECTION` pending decision 的 context payload 組裝與 insert SQL。
- `MatchActionService` 保留 public action API，`MatchTurnLifecycleService` 保留 `createTurnStartPendingInteraction(...)` public method，既有呼叫點只改為委派新 service。
- 不改 DB migration、REST / WebSocket public API、不改 pending payload 格式。

## Scope

- 新增：
  - `src/main/java/com/hololive/cardgame/service/PendingDecisionCreationService.java`
  - `src/test/java/com/hololive/cardgame/service/PendingDecisionCreationServiceTest.java`
- 修改：
  - `src/main/java/com/hololive/cardgame/service/MatchActionService.java`
  - `src/main/java/com/hololive/cardgame/service/MatchTurnLifecycleService.java`
  - `doc/系統優化/00-系統優化總覽.md`
  - `doc/系統優化/02-MatchActionService拆分路線圖.md`
  - `doc/系統優化/05-重構進度追蹤.md`

## Line Count

實際 `wc -l`：

```text
 5583 src/main/java/com/hololive/cardgame/service/MatchActionService.java
  812 src/main/java/com/hololive/cardgame/service/MatchTurnLifecycleService.java
  435 src/main/java/com/hololive/cardgame/service/PendingDecisionCreationService.java
  141 src/test/java/com/hololive/cardgame/service/PendingDecisionCreationServiceTest.java
32302 src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java
```

## Tests

- `./mvnw -q -Dtest=PendingDecisionCreationServiceTest test`
  - Red：service 尚未存在時，test compile 失敗。
  - Green：通過。
- `./mvnw -q -Dtest=PendingDecisionCreationServiceTest,PendingDecisionStoreTest,PendingDecisionReaderTest test`
  - 通過。
- `./mvnw -q -DskipTests compile`
  - 通過。
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#advancePhaseShouldHandOpeningSetupToGuestAndThenCreateLiveStart+resolveLiveStartShouldFlipOpeningCardsAndCreateTurnStart+endTurnShouldCreatePendingTurnStartInteractionForNextTurnPlayer+resolveDecisionShouldConfirmDrawRevealInteraction+resolveDecisionShouldAttachCheerForSendCheerInteraction+playSupportSearchShouldCreatePendingDecisionWhenMultipleCandidatesAndNoSelection+resolveDecisionShouldApplySelectedCardAndMarkDecisionResolved test`
  - 使用 Testcontainers PostgreSQL 提權連接 Docker socket 後通過。

## Notes

- 這批只搬 pending 建立，不搬 decision confirm 後 effect apply、phase transition、defeat check。
- `PendingDecisionCreationService` 直接依賴 `JdbcTemplate`、`MatchPayloadJsonService`、`PendingDecisionReader`。
- 下一批建議開始 `MatchDecisionResolutionService`，第一刀只搬低耦合 look / reorder decision handler。
