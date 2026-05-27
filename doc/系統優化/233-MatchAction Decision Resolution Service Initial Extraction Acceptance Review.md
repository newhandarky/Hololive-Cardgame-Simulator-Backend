# MatchAction Decision Resolution Service Initial Extraction Acceptance Review

日期：2026-05-27

## Summary

- 新增 package-private `MatchDecisionResolutionService`。
- 第一批只搬低耦合 decision handler：
  - `LOOK_TOP_DECK`
  - `LOOK_OPPONENT_HAND`
  - `LOOK_HOLOPOWER`
  - `REORDER_DECK_BOTTOM`
- `MatchActionService.resolveDecision(...)` 保留 public API，先處理 turn / live / draw / trigger / send cheer，接著委派 `MatchDecisionResolutionService.resolveLowCouplingDecision(...)`，未支援的 decision type 回到原本 support card selection 路徑。
- 不搬 `TRIGGER_EFFECT_CONFIRM`、`SEND_CHEER`、`CARD_SELECTION` 的高副作用流程。
- 不改 DB migration、REST / WebSocket public API、不改 pending payload 格式。

## Scope

- 新增：
  - `src/main/java/com/hololive/cardgame/service/MatchDecisionResolutionService.java`
  - `src/test/java/com/hololive/cardgame/service/MatchDecisionResolutionServiceTest.java`
- 修改：
  - `src/main/java/com/hololive/cardgame/service/MatchActionService.java`
  - `doc/系統優化/00-系統優化總覽.md`
  - `doc/系統優化/02-MatchActionService拆分路線圖.md`
  - `doc/系統優化/05-重構進度追蹤.md`

## Line Count

實際 `wc -l`：

```text
 5404 src/main/java/com/hololive/cardgame/service/MatchActionService.java
  288 src/main/java/com/hololive/cardgame/service/MatchDecisionResolutionService.java
  108 src/test/java/com/hololive/cardgame/service/MatchDecisionResolutionServiceTest.java
32302 src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java
```

## Tests

- `./mvnw -q -Dtest=MatchDecisionResolutionServiceTest test`
  - Red：service 尚未存在時，test compile 失敗。
  - Green：通過。
- `./mvnw -q -Dtest=MatchDecisionResolutionServiceTest,PendingDecisionCreationServiceTest,PendingDecisionStoreTest,PendingDecisionReaderTest test`
  - 通過。
- `./mvnw -q -DskipTests compile`
  - 通過。
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#resolveLookTopDeckDecisionShouldAcceptPlacementOption+resolveLookOpponentHandDecisionShouldMarkResolved+playSupportLookHolopowerShouldExposePendingInteractionAndResolve+collabTriggerConfirmShouldCreateLookTopDeckFollowupInteraction+collabTriggerConfirmShouldCreateLookOpponentHandFollowupInteraction+collabTriggerConfirmShouldCreateLookHolopowerFollowupInteraction test`
  - 使用 Testcontainers PostgreSQL 提權連接 Docker socket 後通過。

## Notes

- 這批讓 decision service 先具備低耦合 interaction confirm 能力。
- `MatchDecisionResolutionService` 直接依賴 `JdbcTemplate`、`PendingDecisionStore`、`MatchRepository`、`MatchActionRepository`、`MatchPayloadJsonService`、`InteractionConfirmedPayloadBuilder`、`MatchTimestampService`。
- 下一批建議搬 `DRAW_REVEAL` decision handler；它比 `SEND_CHEER` 更少副作用，適合作為第二刀。
