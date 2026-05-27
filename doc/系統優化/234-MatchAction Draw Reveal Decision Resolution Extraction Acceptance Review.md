# AAA-234 MatchAction Draw Reveal Decision Resolution Extraction Acceptance Review

日期：2026-05-27

## Summary

本批延續 AAA-233 的 `MatchDecisionResolutionService` 主線，將 `DRAW_REVEAL` pending decision resolution 從 `MatchActionService` 搬入 package-private service。

此次只搬移既有 orchestration 行為，不改 REST / WebSocket public API、不新增 DB migration，也不改抽牌或回合 Cheer 規則語意。

## Scope

- `MatchDecisionResolutionService`
  - 新增 `DRAW_REVEAL` decision type handling。
  - 搬入 pending resolved、turn cheer availability query、main step gift follow-up payload append、`MatchTurnLifecycleService.confirmDrawRevealDecision(...)` 呼叫。
  - 保留既有 phase 決策：可做回合 Cheer 時進 `CHEER`，否則進 `MAIN`。

- `MatchActionService`
  - `resolveDecision(...)` 不再直接分支處理 `DRAW_REVEAL`。
  - 移除原本 `resolveDrawRevealDecision(...)` private helper。
  - `canPerformTurnCheerAction(...)` 仍保留在主 service，供 `advancePhase(...)` 與 `attackArt(...)` 的 turn action required checks 使用；本批不擴張到 turn action availability service。

- `MatchDecisionResolutionServiceTest`
  - 補 `DRAW_REVEAL` focused unit tests：
    - 可做回合 Cheer 時 mark resolved，confirm lifecycle next phase 為 `CHEER`，且不附加 main step gift follow-up。
    - 無可用回合 Cheer 時 mark resolved，附加 main step gift follow-up，confirm lifecycle next phase 為 `MAIN`。

## File Size

- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`5,377` 行。
- `src/main/java/com/hololive/cardgame/service/MatchDecisionResolutionService.java`：`359` 行。
- `src/test/java/com/hololive/cardgame/service/MatchDecisionResolutionServiceTest.java`：`178` 行。

## Verification

已執行：

```bash
./mvnw -q -Dtest=MatchDecisionResolutionServiceTest test
./mvnw -q -DskipTests compile
./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#resolveDecisionShouldConfirmDrawRevealInteraction' test
```

結果：

- `MatchDecisionResolutionServiceTest`：通過。
- `compile`：通過。
- `MatchActionServiceIntegrationTest#resolveDecisionShouldConfirmDrawRevealInteraction`：
  - 沙盒內首次執行因 Docker / PostgreSQL socket `Operation not permitted` 無法建立測試 datasource。
  - 使用相同 Maven command 提權後，Testcontainers PostgreSQL 啟動成功，測試通過。

## Acceptance

- `DRAW_REVEAL` resolution 已由 `MatchDecisionResolutionService` 承接。
- 原本 `MatchActionService` 內的 `resolveDrawRevealDecision(...)` 已移除。
- 可用 / 不可用回合 Cheer 兩條分支皆有 focused unit test。
- 現有 `DRAW_REVEAL` integration case 維持通過。

## Next Step

下一批建議處理 `SEND_CHEER` decision handler：

- 先補 `MatchDecisionResolutionServiceTest` focused coverage。
- 搬移 selected cheer / target validation、`SendCheerAction` execution、resolved action payload、turn cheer action log。
- 保留 `TRIGGER_EFFECT_CONFIRM` 與 `CARD_SELECTION` 在後續批次，避免同批混入 support effect / gift follow-up resolution。
