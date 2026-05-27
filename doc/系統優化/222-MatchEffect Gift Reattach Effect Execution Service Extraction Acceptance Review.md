# AAA-222 MatchEffect Gift Reattach Effect Execution Service Extraction Acceptance Review

日期：2026-05-27

## Summary

本批延續 AAA-221 的 Gift handler extraction，抽出 `MatchGiftReattachEffectExecutionService`，集中 `REATTACH` 的 Cheer 付け替え / 回貼執行流程。

這批只搬移既有行為，不改 DB migration、REST / WebSocket public API，也不重新解釋 Gift / Bloom / Collab 規則。

## Key Changes

- 新增 package-private `MatchGiftReattachEffectExecutionService`。
  - 集中 `REATTACH` 的 dice 條件檢查、Cheer 文案限制、對手側 context 判斷、source owner 解析、target holder / target Holomem 解析、move count 解析、Cheer row 搬移與 summary 組裝。
  - 搬入 reattach 專用 helper：stored holder Cheer rows 查詢、holder Cheer row 搬移、source mode merge。
  - 高耦合目標解析先透過 callback 注入保留既有語意：對手 user、holder Holomem、preferred add-cheer target、Holomem owner、fallback target、zone-only Cheer candidate 與 Holomem card instance lookup。
- `MatchGiftEffectServiceHandlers` 的 `executeReattachEffect(...)` 改直接委派新 service，不再回接 `MatchEffectService` 執行 Gift `REATTACH`。
- `MatchEffectService` 建立 `MatchGiftReattachEffectExecutionService` 欄位，並移除大段 `REATTACH` 執行流程與 reattach 專用 helper。
- `MatchEffectService.executeReattachEffect(...)` 目前保留為薄 delegate，供 Bloom / Collab dispatcher 既有 bridge 使用；下一批可移除此 bridge。
- 新增 `MatchGiftReattachEffectExecutionServiceTest`，覆蓋：
  - dice 條件未命中時回傳 no-op summary。
  - 場上 attached Cheer 可移到目標 Holomem，回傳 `moveApplied`、`movedCheerCardIds`、`movedCheerRowIds` 與 `sourceMode = STAGE`。
  - stored holder Cheer 位於 `ARCHIVE` 時可回貼到目標 Holomem，回傳 `sourceMode = ARCHIVE`。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`5,318` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftReattachEffectExecutionService.java`：`573` 行。
- `src/test/java/com/hololive/cardgame/service/MatchGiftReattachEffectExecutionServiceTest.java`：`199` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftEffectServiceHandlers.java`：`134` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。

## Verification

- TDD red：第一次執行 `./mvnw -q -Dtest=MatchGiftReattachEffectExecutionServiceTest test` 因 `MatchGiftReattachEffectExecutionService` 尚未存在而編譯失敗。
- `./mvnw -q -Dtest=MatchGiftReattachEffectExecutionServiceTest test`：PASS。
- `./mvnw -q -Dtest=MatchGiftReattachEffectExecutionServiceTest,MatchGiftArchiveReturnEffectExecutionServiceTest,MatchGiftEffectDispatcherTest,MatchGiftEffectExecutionCoordinatorTest test`：PASS。
- `./mvnw -q -DskipTests compile`：PASS。
- 沙盒內 `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#bloomShouldTriggerReattachEffectFromPassiveText+attackArtShouldTriggerOfficialGiftHbp01124WhenSelfDownedAndReattachOwnCheer' test`：FAIL，原因是 Docker / PostgreSQL socket `Operation not permitted`，未進入應用行為。
- 提權重跑同一條 Bloom / HBP01-124 `REATTACH` integration command：PASS。
- `docker ps`：無本批 Testcontainers PostgreSQL container 殘留；只看到既有 `bokiv4-nginx` 與 `bokiv4-frontend`。

## Notes

- 這批沒有改 `ADD_CHEER` 的來源區、目標區位、等級條件或 sequential choice 行為。
- `MatchGiftReattachEffectExecutionService` 仍透過 callback 使用既有 target resolver，避免在同一批重寫 target 語意。
- Bloom / Collab dispatcher 仍暫時透過 `MatchEffectService.executeReattachEffect(...)` 薄 delegate 呼叫新 service，這是刻意留下的低風險分段點。

## Next Step

下一批建議做 AAA-223：清理 Bloom / Collab 的 `REATTACH` bridge。

- `MatchBloomEffectDispatcher` 與 `MatchCollabEffectDispatcher` 直接持有 `MatchGiftReattachEffectExecutionService`。
- 移除 `MatchEffectService.executeReattachEffect(...)` 薄 delegate。
- 驗證同一組 focused unit / compile / Bloom reattach integration，確認 AAA-222 的 service extraction 完整落地。
- 完成 bridge cleanup 後，再評估較大的 `ADD_CHEER` handler extraction。
