# AAA-229 MatchEffect Cheer Candidate Query Service Extraction Acceptance Review

日期：2026-05-27

## Summary

本批延續 AAA-228，抽出 `MatchCheerCandidateQueryService`，集中 `REATTACH` 與 `ADD_CHEER` source resolver 共用的 Cheer candidate 查詢。

這批只搬移查詢責任，不改 `REATTACH` / `ADD_CHEER` 規則語意、不改 `SendCheerAction` side effect、不改 DB migration、REST / WebSocket public API。

## Key Changes

- 新增 `MatchCheerCandidateQueryService`。
- 新 service 集中處理：
  - `findCheerCardFromZone(...)` zone-only 查詢。
  - `findCheerCardFromZone(...)` 帶 `SearchCriteria` 查詢。
  - unsupported zone no-op。
  - `ADD_CHEER` 未指定來源時的 CHEER_DECK > ARCHIVE > HAND fallback attachable Cheer 查詢。
- `MatchAddCheerSourceResolverService` 改直接依賴 `MatchCheerCandidateQueryService`。
- `MatchGiftReattachEffectExecutionService` 改直接依賴 `MatchCheerCandidateQueryService`。
- `MatchEffectService` 建立 `cheerCandidateQueryService` 欄位，移除原本私有的 `findCheerCardFromZone(...)` overload。
- 新增 `MatchCheerCandidateQueryServiceTest`，覆蓋 zone candidate、unsupported zone 與 fallback candidate。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`4,910` 行。
- `src/main/java/com/hololive/cardgame/service/MatchCheerCandidateQueryService.java`：`77` 行。
- `src/test/java/com/hololive/cardgame/service/MatchCheerCandidateQueryServiceTest.java`：`100` 行。
- `src/main/java/com/hololive/cardgame/service/MatchAddCheerSourceResolverService.java`：`91` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftReattachEffectExecutionService.java`：`576` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。

## Verification

- TDD red：`./mvnw -q -Dtest=MatchCheerCandidateQueryServiceTest test` 初次失敗，原因是 `MatchCheerCandidateQueryService` 尚未存在。
- `./mvnw -q -Dtest=MatchCheerCandidateQueryServiceTest test`：PASS。
- `./mvnw -q -Dtest=MatchCheerCandidateQueryServiceTest,MatchAddCheerSourceResolverServiceTest,MatchGiftReattachEffectExecutionServiceTest,MatchGiftAddCheerEffectExecutionServiceTest,MatchGiftEffectServiceHandlersTest test`：PASS。
- `./mvnw -q -DskipTests compile`：PASS。
- 沙盒內 `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportShouldAttachCheerToTargetHolomem+collabHsd01009ShouldSendCheerAndMoveSelfToBackWhenDiceIsOne+collabHsd13015ShouldReturnStageCheerThenAddCheer test`：FAIL，原因是 Docker / PostgreSQL socket `Operation not permitted`，未進入應用行為。
- 提權重跑同一條 support / collab `ADD_CHEER` integration command：PASS。

## Notes

- `MatchGiftReattachEffectExecutionService` 仍以 callback 使用 `MatchAddCheerTargetResolverService` 的 target resolver。
- `MatchAddCheerSourceResolverService` 已不再持有 `JdbcTemplate`，只負責 source clause / source zone 判斷。
- `MatchEffectService` 不再持有 Cheer zone candidate lookup 私有 helper。

## Next Step

下一批建議收斂 `REATTACH` target resolver wiring：

- 讓 `MatchGiftReattachEffectExecutionService` 直接持有 `MatchAddCheerTargetResolverService`。
- 移除 `MatchGiftReattachEffectExecutionService.PreferredAddCheerTargetResolver`。
- 保留現有 `REATTACH` source mode、archive / cheer deck / stage 搬移語意不變。
