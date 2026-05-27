# AAA-239 MatchAction Turn Start Decision Resolution Acceptance Review

日期：2026-05-27

## Summary

本批延續 MatchAction decision lifecycle 主線，將 `TURN_START` decision resolution 從 `MatchActionService` 搬入 `MatchDecisionResolutionService`。

此次只搬移既有 orchestration 行為，不改 REST / WebSocket public API、不新增 DB migration，也不改 `TURN_START` 規則語意。

## Scope

- `MatchDecisionResolutionService`
  - 新增 `TURN_START` branch。
  - 注入 `MatchTurnStartCollabReturnService`。
  - 新增 `resolveTurnStartDecision(...)`，集中 pending resolved、collab return helper 委派與 lifecycle confirm。

- `MatchActionService`
  - `resolveDecision(...)` 不再直接分支處理 `TURN_START`。
  - 移除原本 private `resolveTurnStartDecision(...)`。
  - 建構 `MatchDecisionResolutionService` 時傳入 `MatchTurnStartCollabReturnService`。

- Tests
  - `MatchDecisionResolutionServiceTest` 補 `TURN_START` focused test：
    - 回傳 handled。
    - 標記 pending resolved。
    - 委派 `MatchTurnStartCollabReturnService.returnCollabToBackAsRested(...)`。
    - 委派 `MatchTurnLifecycleService.confirmTurnStartDecision(...)`。
    - 不寫入額外 action log。

## File Size

- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`5,091` 行。
- `src/main/java/com/hololive/cardgame/service/MatchDecisionResolutionService.java`：`534` 行。
- `src/test/java/com/hololive/cardgame/service/MatchDecisionResolutionServiceTest.java`：`274` 行。

## Verification

TDD 紅燈：

```bash
./mvnw -q -Dtest=MatchDecisionResolutionServiceTest test
```

結果：

- 第一次執行因 `MatchDecisionResolutionService` constructor 尚未接收 `MatchTurnStartCollabReturnService` 而 test compile 失敗，符合預期紅燈。

已執行：

```bash
./mvnw -q -Dtest=MatchDecisionResolutionServiceTest test
./mvnw -q -Dtest=MatchDecisionResolutionServiceTest,MatchTurnStartCollabReturnServiceTest,PendingDecisionCreationServiceTest,PendingDecisionStoreTest,PendingDecisionReaderTest test
./mvnw -q -DskipTests compile
./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#resolveDecisionShouldMoveTurnStartToDrawPhase' test
```

結果：

- `MatchDecisionResolutionServiceTest`：通過。
- Decision / pending focused unit combo：通過。
- `compile`：通過。
- `resolveDecisionShouldMoveTurnStartToDrawPhase`：一般沙盒因 Docker / PostgreSQL socket `Operation not permitted` 失敗；同一 command 已提權重跑，使用 Testcontainers PostgreSQL，結果通過。

檢查：

```bash
git diff --check
```

結果：

- 通過，沒有 whitespace error。

## Acceptance

- `TURN_START` decision resolution 已從 `MatchActionService` 搬入 `MatchDecisionResolutionService`。
- `TURN_START` 前的 collab return stage mutation 仍由 AAA-238 的 `MatchTurnStartCollabReturnService` 處理。
- Pending resolved 與 `MatchTurnLifecycleService.confirmTurnStartDecision(...)` lifecycle 委派語意保留。
- `TRIGGER_EFFECT_CONFIRM` 與 `CARD_SELECTION` 尚未搬移，本批沒有混入 triggered effect / support selection 副作用重構。

## Next Step

下一批建議先處理 `CARD_SELECTION` resolution boundary：

- 補 focused tests 鎖定 selected-card validation、`applySupportEffect(...)`、phase transition、follow-up decision payload 與 finalize side effects。
- 再決定整段搬入 `MatchDecisionResolutionService`，或先抽出 support card selection helper。
- `TRIGGER_EFFECT_CONFIRM` 保留後續獨立批次，避免同時搬移 trigger effect apply 與 support selection。
