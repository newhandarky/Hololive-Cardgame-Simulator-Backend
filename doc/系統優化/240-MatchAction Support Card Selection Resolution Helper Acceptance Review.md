# AAA-240 MatchAction Support Card Selection Resolution Helper Acceptance Review

日期：2026-05-27

## Summary

本批延續 MatchAction decision lifecycle 主線，先將 `CARD_SELECTION` support / oshi selection resolution 抽成 package-private helper：`MatchSupportCardSelectionResolutionService`。

此次只搬移既有 orchestration 行為，不改 REST / WebSocket public API、不新增 DB migration，也不改 support / oshi effect 規則語意。

## Scope

- `MatchSupportCardSelectionResolutionService`
  - 集中 selected-card validation。
  - 透過 `SupportEffectApplier` callback 保留 `MatchEffectService.applySupportEffect(...)` 的既有副作用入口。
  - 集中 pending resolved、phase transition、resolved selection payload、follow-up decision payload append 與 action log。
  - 透過 `ResolvedEffectFinalizer` callback 保留 `MatchActionService.finalizeResolvedEffect(...)` 的既有後續勝負 / life loss send cheer side effects。

- `MatchActionService`
  - 建立 `MatchSupportCardSelectionResolutionService` 欄位。
  - `CARD_SELECTION` fallback 改委派新 helper。
  - 移除原本 private `resolveSupportCardSelectionDecision(...)`。
  - `TRIGGER_EFFECT_CONFIRM` 仍留在 `MatchActionService`，本批不搬 trigger effect apply。

- Tests
  - 新增 `MatchSupportCardSelectionResolutionServiceTest`：
    - 一般 support selection 會套用 effect、標記 pending resolved、轉回 MAIN、append follow-up payload、寫 `PLAY_SUPPORT` action 並呼叫 finalize callback。
    - Oshi skill 來源會寫 `USE_OSHI_SKILL` action，payload 使用 oshi 欄位，並保留 follow-up resolver 與 finalize callback。

## File Size

- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`5,063` 行。
- `src/main/java/com/hololive/cardgame/service/MatchSupportCardSelectionResolutionService.java`：`148` 行。
- `src/test/java/com/hololive/cardgame/service/MatchSupportCardSelectionResolutionServiceTest.java`：`137` 行。

## Verification

TDD 紅燈：

```bash
./mvnw -q -Dtest=MatchSupportCardSelectionResolutionServiceTest test
```

結果：

- 第一次執行因 `MatchSupportCardSelectionResolutionService` 尚未存在而 test compile 失敗，符合預期紅燈。

已執行：

```bash
./mvnw -q -Dtest=MatchSupportCardSelectionResolutionServiceTest test
./mvnw -q -Dtest=MatchSupportCardSelectionResolutionServiceTest,MatchDecisionResolutionServiceTest,SelectedCardValidationServiceTest,SupportOshiEffectPayloadBuilderTest test
./mvnw -q -DskipTests compile
./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#resolveDecisionShouldApplySelectedCardAndMarkDecisionResolved+resolveDecisionShouldCreateSendCheerInteractionWhenResolvedSupportReducesLife' test
```

結果：

- `MatchSupportCardSelectionResolutionServiceTest`：通過。
- Card selection / decision focused unit combo：通過。
- `compile`：通過。
- `resolveDecisionShouldApplySelectedCardAndMarkDecisionResolved` / `resolveDecisionShouldCreateSendCheerInteractionWhenResolvedSupportReducesLife`：通過；使用 Testcontainers PostgreSQL 提權執行。

檢查：

```bash
git diff --check
```

結果：

- 通過，沒有 whitespace error。

## Acceptance

- `CARD_SELECTION` support / oshi selection resolution 已從 `MatchActionService` 抽出。
- 高耦合的 support effect apply 與 resolved effect finalize 仍以 callback 回接既有流程，避免本批混入效果規則改寫。
- `MatchActionService.resolveDecision(...)` 仍保留 `CARD_SELECTION` fallback 判斷，但 orchestration 已委派新 helper。
- `TRIGGER_EFFECT_CONFIRM` 尚未搬移，本批沒有混入 trigger effect apply 重構。

## Next Step

下一批建議二擇一：

- 把 `CARD_SELECTION` fallback 接入 `MatchDecisionResolutionService`，讓 `resolveDecision(...)` 只剩 `TRIGGER_EFFECT_CONFIRM` 這個高耦合分支。
- 若接入仍受 finalize callback 面積阻礙，先抽 `ResolvedEffectFinalizationService` 或 life-loss follow-up enqueue helper，再回頭搬 `CARD_SELECTION` fallback。
