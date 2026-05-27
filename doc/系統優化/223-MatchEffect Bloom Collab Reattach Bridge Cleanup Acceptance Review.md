# AAA-223 MatchEffect Bloom Collab Reattach Bridge Cleanup Acceptance Review

日期：2026-05-27

## Summary

本批延續 AAA-222，清理 Bloom / Collab dispatcher 對 `MatchEffectService.executeReattachEffect(...)` 的薄 bridge。

這批只調整 service 接線，不改 `REATTACH` 規則、不改 DB migration、REST / WebSocket public API。

## Key Changes

- `MatchBloomEffectDispatcher` 直接持有 `MatchGiftReattachEffectExecutionService`。
- `MatchCollabEffectDispatcher` 直接持有 `MatchGiftReattachEffectExecutionService`。
- `REATTACH` case 改由 dispatcher 直接呼叫新 service，不再回到 `MatchEffectService`。
- `MatchEffectService` constructor 將 `giftReattachEffectExecutionService` 注入 Bloom / Collab dispatcher。
- 移除 `MatchEffectService.executeReattachEffect(...)` 薄 delegate。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`5,302` 行。
- `src/main/java/com/hololive/cardgame/service/MatchBloomEffectDispatcher.java`：`352` 行。
- `src/main/java/com/hololive/cardgame/service/MatchCollabEffectDispatcher.java`：`342` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftReattachEffectExecutionService.java`：`573` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。

## Verification

- `./mvnw -q -Dtest=MatchGiftReattachEffectExecutionServiceTest,MatchGiftEffectDispatcherTest test`：PASS。
- `./mvnw -q -DskipTests compile`：PASS。
- 沙盒內 `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#bloomShouldTriggerReattachEffectFromPassiveText+attackArtShouldTriggerOfficialGiftHbp01124WhenSelfDownedAndReattachOwnCheer' test`：FAIL，原因是 Docker / PostgreSQL socket `Operation not permitted`，未進入應用行為。
- 提權重跑同一條 Bloom / HBP01-124 `REATTACH` integration command：PASS。
- `docker ps`：無本批 Testcontainers PostgreSQL container 殘留；只看到既有 `bokiv4-nginx` 與 `bokiv4-frontend`。

## Notes

- 這批沒有新增 production 行為，也沒有修改 `MatchGiftReattachEffectExecutionService` 的搬移邏輯。
- Bloom / Collab dispatcher 仍有其他 effect type 透過 `MatchEffectService` bridge；本批只針對 AAA-222 遺留的 `REATTACH` bridge。

## Next Step

下一批建議先規劃 `ADD_CHEER` handler extraction：

- 盤點 `executeAddCheerEffect(...)` 與來源區 / 目標區 / 等級條件 / sequential choice helper。
- 先補 focused characterization test，鎖定目前 summary 與 attach 行為。
- 再評估是否抽 `MatchGiftAddCheerEffectExecutionService`，或先拆較小的來源候選 / 目標解析 resolver。
