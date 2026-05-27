# AAA-238 MatchAction Turn Start Collab Return Helper Extraction Acceptance Review

日期：2026-05-27

## Summary

本批延續 MatchAction decision lifecycle 主線，先抽出 `TURN_START` 搬移前的 collab return helper：`MatchTurnStartCollabReturnService`。

此次只搬移既有 stage mutation 行為，不改 REST / WebSocket public API、不新增 DB migration，也不改 `TURN_START` resolution 語意。

## Scope

- `MatchTurnStartCollabReturnService`
  - 新增 `returnCollabToBackAsRested(...)`。
  - 將目前玩家 `COLLAB` Holomem 移回 `BACK` 並設為 rested。
  - 保留 HBP03-039 / `フワワ・アビスガード` 中心特例：符合條件時，被移回 `BACK` 的 HBP03-039 維持 unrested。
  - 保留 no-op 條件：`matchId` / `userId` 為空或沒有 `COLLAB` 時不寫入。

- `MatchActionService`
  - 建立 `MatchTurnStartCollabReturnService` 欄位。
  - `resolveTurnStartDecision(...)` 改委派新 helper。
  - 移除原本 private `returnCollabToBackAsRested(...)` 與 `isOwnCenterHolomemNameContains(...)`。

- Tests
  - 新增 `MatchTurnStartCollabReturnServiceTest`：
    - 無 COLLAB 時不更新。
    - 一般 COLLAB 移回 BACK rested。
    - HBP03-039 / フワワ中心特例下，移回 BACK 後再設為 unrested。

## File Size

- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`5,111` 行。
- `src/main/java/com/hololive/cardgame/service/MatchTurnStartCollabReturnService.java`：`99` 行。
- `src/test/java/com/hololive/cardgame/service/MatchTurnStartCollabReturnServiceTest.java`：`57` 行。

## Verification

TDD 紅燈：

```bash
./mvnw -q -Dtest=MatchTurnStartCollabReturnServiceTest test
```

結果：

- 第一次執行因 `MatchTurnStartCollabReturnService` 尚未存在而編譯失敗，符合預期紅燈。

已執行：

```bash
./mvnw -q -Dtest=MatchTurnStartCollabReturnServiceTest test
./mvnw -q -Dtest=MatchDecisionResolutionServiceTest test
./mvnw -q -Dtest=MatchTurnStartCollabReturnServiceTest,MatchDecisionResolutionServiceTest,PendingDecisionCreationServiceTest,PendingDecisionStoreTest,PendingDecisionReaderTest test
./mvnw -q -DskipTests compile
./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#resolveDecisionShouldMoveTurnStartToDrawPhase' test
```

結果：

- `MatchTurnStartCollabReturnServiceTest`：通過。
- `MatchDecisionResolutionServiceTest`：通過。
- Pending decision focused unit combo：通過。
- `compile`：通過。
- `resolveDecisionShouldMoveTurnStartToDrawPhase`：通過；此 integration test 使用 Testcontainers PostgreSQL，已以提權 Docker access 重跑並通過。

檢查：

```bash
git diff --check
```

## Acceptance

- `TURN_START` 前的 collab return stage mutation 已從 `MatchActionService` 抽出。
- HBP03-039 / フワワ中心特例仍由 focused unit test 保護。
- `resolveTurnStartDecision(...)` 尚未搬入 `MatchDecisionResolutionService`，本批只先降低搬移前耦合。

## Next Step

下一批可開始搬移 `TURN_START` decision resolution：

- `MatchDecisionResolutionService` 新增 `TURN_START` branch。
- 委派 `MatchTurnStartCollabReturnService.returnCollabToBackAsRested(...)`。
- 保留 pending resolved、`MatchTurnLifecycleService.confirmTurnStartDecision(...)`、phase transition 與 action log 行為。
