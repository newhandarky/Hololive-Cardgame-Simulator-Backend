# AAA-221 MatchEffect Gift Archive Return Effect Execution Service Extraction Acceptance Review

日期：2026-05-26

## Summary

本批延續 AAA-220 的 Gift dispatcher 拆分，抽出 `MatchGiftArchiveReturnEffectExecutionService`，集中 HBP02-039 / `REPLACE_ARCHIVE_WITH_HAND` 的 Holox reveal archive 支援卡回手流程。

這批只搬移既有行為，不改 DB migration、REST / WebSocket public API，也不重新解釋 Gift 規則。

## Key Changes

- 新增 package-private `MatchGiftArchiveReturnEffectExecutionService`。
  - 查詢 latest `ATTACK_ART` payload 中同一 holder 的 `ホロックスロット` reveal 結果。
  - 從 `holoxReveal.archivedSupportCardInstanceIds` 取出候選支援卡。
  - 將第一張仍在 `ARCHIVE` 的候選卡移回 `HAND`，並設定下一個 hand order。
  - 保留既有 summary 欄位：`effectType`、`candidateCardInstanceIds`、`applied`、`reason`、`movedCardInstanceId`、`movedCardId`、`movedCount`。
- `MatchGiftEffectServiceHandlers` 的 `executeReplaceArchiveWithHandEffect(...)` 改委派新 service。
- `MatchEffectService` 建立 `MatchGiftArchiveReturnEffectExecutionService` 欄位，並移除原本的 archive 回手 private / package-private helper。
- 新增 `MatchGiftArchiveReturnEffectExecutionServiceTest`，覆蓋：
  - latest Holox reveal 有 archived support card 時，移動第一張候選卡到 `HAND` 並回傳 applied summary。
  - latest Holox reveal 沒有 archived support card 時，回傳 no-op summary 且不更新 `match_cards`。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`5,730` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftArchiveReturnEffectExecutionService.java`：`148` 行。
- `src/test/java/com/hololive/cardgame/service/MatchGiftArchiveReturnEffectExecutionServiceTest.java`：`113` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftEffectServiceHandlers.java`：`131` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。

## Verification

- TDD red：第一次執行 `./mvnw -q -Dtest=MatchGiftArchiveReturnEffectExecutionServiceTest test` 因 `MatchGiftArchiveReturnEffectExecutionService` 尚未存在而編譯失敗。
- `./mvnw -q -Dtest=MatchGiftArchiveReturnEffectExecutionServiceTest test`：PASS。
- `./mvnw -q -Dtest=MatchGiftArchiveReturnEffectExecutionServiceTest,MatchGiftEffectDispatcherTest,MatchGiftEffectExecutionCoordinatorTest,MatchEffectTypeInferenceServiceTest test`：PASS。
- `./mvnw -q -DskipTests compile`：PASS。
- 沙盒內 `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerOfficialGiftHbp02039WhenHoloxSlotRevealsSupport' test`：FAIL，原因是 Docker / PostgreSQL socket `Operation not permitted`，未進入應用行為。
- 提權重跑同一條 HBP02-039 integration command：PASS。
- `docker ps`：無本批 Testcontainers PostgreSQL container 殘留；只看到既有 `bokiv4-nginx` 與 `bokiv4-frontend`。

## Notes

- 新 service 目前直接依賴 `JdbcTemplate` 與 `EffectTextParser`，避免再透過 `MatchEffectService` callback 查 payload 或搬移卡片。
- `nextZoneOrder(...)` 先留在新 service 內，符合這段 archive-to-hand 行為的局部需求；暫不抽共用 zone order helper，避免擴大本批範圍。
- 沒有修改 Gift execution coordinator、dispatcher routing、`ADD_CHEER` 或 `REATTACH` 行為。

## Next Step

下一批建議處理 `REATTACH` handler，抽出 `MatchGiftReattachEffectExecutionService`：

- 先補 focused unit test，鎖定 reattach 成功、找不到目標、骰子條件不符合與 summary 欄位。
- 再將 `executeReattachEffect(...)` 從 `MatchEffectService` 搬移到新 service。
- `MatchGiftEffectServiceHandlers` 改直接委派新 service，進一步減少 Gift adapter 對主 service 的回呼。
- 保持 `ADD_CHEER` 不動，因它的來源區、目標區位、等級與 sequential choice 邏輯更大，適合獨立一批處理。
