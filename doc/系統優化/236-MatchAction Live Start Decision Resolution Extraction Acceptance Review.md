# AAA-236 MatchAction Live Start Decision Resolution Extraction Acceptance Review

日期：2026-05-27

## Summary

本批延續 AAA-233 到 AAA-235 的 decision lifecycle 主線，將 `LIVE_START` pending decision resolution 從 `MatchActionService` 搬入 package-private `MatchDecisionResolutionService`。

此次只搬移既有 orchestration 行為，不改 REST / WebSocket public API、不新增 DB migration，也不改 opening card flip、phase update 或 `TURN_START` follow-up 建立語意。

## Scope

- `MatchDecisionResolutionService`
  - 新增 `LIVE_START` decision type handling。
  - 搬入 pending decision resolved 更新。
  - 委派 `MatchTurnLifecycleService.confirmLiveStartDecision(...)`，維持 opening card flip、phase update 與後續 `TURN_START` pending 建立由 lifecycle service 處理。

- `MatchActionService`
  - `resolveDecision(...)` 不再直接分支處理 `LIVE_START`。
  - 移除原本 `resolveLiveStartDecision(...)` private helper。
  - 移除 `INTERACTION_TYPE_LIVE_START` 常數。

- `MatchDecisionResolutionServiceTest`
  - 補 `LIVE_START` focused unit test：
    - low-coupling resolution 會回傳 handled。
    - mark pending resolved。
    - 委派 `MatchTurnLifecycleService.confirmLiveStartDecision(...)`。
    - 不直接寫入 action log。

## File Size

- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`5,230` 行。
- `src/main/java/com/hololive/cardgame/service/MatchDecisionResolutionService.java`：`535` 行。
- `src/test/java/com/hololive/cardgame/service/MatchDecisionResolutionServiceTest.java`：`254` 行。

## Verification

已執行：

```bash
./mvnw -q -Dtest=MatchDecisionResolutionServiceTest test
./mvnw -q -DskipTests compile
./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#resolveLiveStartShouldFlipOpeningCardsAndCreateTurnStart' test
```

結果：

- `MatchDecisionResolutionServiceTest`：通過。
- `compile`：通過。
- `MatchActionServiceIntegrationTest#resolveLiveStartShouldFlipOpeningCardsAndCreateTurnStart`：
  - 沙盒內首次執行因 Docker / PostgreSQL socket `Operation not permitted` 無法建立測試 datasource。
  - 使用相同 Maven command 提權後，Testcontainers PostgreSQL 啟動成功，測試通過。

## Acceptance

- `LIVE_START` resolution 已由 `MatchDecisionResolutionService` 承接。
- 原本 `MatchActionService` 內的 `resolveLiveStartDecision(...)` 已移除。
- Opening card flip 與後續 `TURN_START` pending 建立仍由 `MatchTurnLifecycleService` 維持既有行為。
- Focused unit test 與 lifecycle integration test 已保護委派與實際 flow。

## Next Step

下一批建議不要直接合併 `TRIGGER_EFFECT_CONFIRM` 與 `CARD_SELECTION`。建議先處理以下其中一個方向：

- 低中風險：抽出共用 selected-card validation helper，讓 `SEND_CHEER`、`TRIGGER_EFFECT_CONFIRM`、`CARD_SELECTION` 後續搬移時共用 sanitize / min-max / candidate validation。
- 中風險：評估 `TURN_START` 搬移前是否先抽 return-collab lifecycle helper，避免 decision resolution service 直接持有過多 stage mutation 細節。
