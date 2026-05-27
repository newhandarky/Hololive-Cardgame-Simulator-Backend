# MatchAction Pending Decision Store Extraction Acceptance Review

日期：2026-05-27

## Summary

- 從 `MatchActionService` 抽出 package-private `PendingDecisionStore`，集中 pending decision 的 `FOR UPDATE` 載入、context JSON 映射與 `RESOLVED` 更新。
- 新增 top-level package-private `PendingDecision` record，讓後續 `MatchDecisionResolutionService` 可以逐步接手 decision flow，不再被 `MatchActionService` private record 綁住。
- `MatchActionService.resolveDecision(...)` 保留 public API 與各 decision handler 行為，只改用 `PendingDecisionStore.loadForUpdate(...)` / `markResolved(...)`。
- 不改 DB migration、REST / WebSocket public API、不改 pending payload 格式。

## Scope

- 新增：
  - `src/main/java/com/hololive/cardgame/service/PendingDecision.java`
  - `src/main/java/com/hololive/cardgame/service/PendingDecisionStore.java`
  - `src/test/java/com/hololive/cardgame/service/PendingDecisionStoreTest.java`
- 修改：
  - `src/main/java/com/hololive/cardgame/service/MatchActionService.java`
  - `doc/系統優化/00-系統優化總覽.md`
  - `doc/系統優化/02-MatchActionService拆分路線圖.md`
  - `doc/系統優化/05-重構進度追蹤.md`

## Line Count

實際 `wc -l`：

```text
4910 src/main/java/com/hololive/cardgame/service/MatchEffectService.java
5958 src/main/java/com/hololive/cardgame/service/MatchActionService.java
 131 src/main/java/com/hololive/cardgame/service/PendingDecisionStore.java
  22 src/main/java/com/hololive/cardgame/service/PendingDecision.java
 105 src/test/java/com/hololive/cardgame/service/PendingDecisionStoreTest.java
32302 src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java
```

## Tests

- `./mvnw -q -Dtest=PendingDecisionStoreTest test`
  - Red：service 尚未存在時，test compile 失敗。
  - Green：通過。
- `./mvnw -q -Dtest=PendingDecisionStoreTest,PendingDecisionReaderTest test`
  - 通過。
- `./mvnw -q -DskipTests compile`
  - 通過。
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#resolveDecisionShouldMoveTurnStartToDrawPhase+resolveDecisionShouldConfirmDrawRevealInteraction+resolveDecisionShouldAttachCheerForSendCheerInteraction+playSupportSearchShouldCreatePendingDecisionWhenMultipleCandidatesAndNoSelection+resolveDecisionShouldApplySelectedCardAndMarkDecisionResolved test`
  - 沙盒內因 Docker/Testcontainers socket `Operation not permitted` 失敗。
  - 同 command 提權重跑後通過。

## Notes

- `PendingDecisionStore` 保留原本 `MatchActionService` 的候選卡 ID 解析語意：忽略 null、非數字、<= 0 與重複值。
- 這批只搬 pending store，不搬 pending interaction 建立器，也不搬 confirm 後 effect apply、phase transition、defeat check。
- 下一批建議抽出 pending interaction 建立器，集中 `TURN_START`、`DRAW_REVEAL`、`SEND_CHEER`、`CARD_SELECTION` 的 context payload 與 insert SQL。
