# AAA-227 MatchEffect Add Cheer Source Resolver Extraction Acceptance Review

日期：2026-05-27

## Summary

本批延續 AAA-226，抽出 `ADD_CHEER` 來源解析器 `MatchAddCheerSourceResolverService`。

這批只搬移來源解析與 fallback Cheer candidate 查詢，不改目標解析、不改 `SendCheerAction` 執行語意、不改 DB migration、REST / WebSocket public API。

## Key Changes

- 新增 `MatchAddCheerSourceResolverService`。
- 新 service 集中處理：
  - `ADD_CHEER` source clause 擷取。
  - `アーカイブの` 來源判斷。
  - `エールデッキ` 來源判斷。
  - 來源 clause 的 `SearchCriteriaParser` 條件解析。
  - 未指定來源時的 fallback attachable Cheer candidate SQL 查詢。
- `MatchEffectService` 建立 `addCheerSourceResolverService` 欄位。
- `MatchGiftAddCheerEffectExecutionService` 的 source callback 改接新 resolver。
- `findCheerCardFromZone(...)` 保留在 `MatchEffectService`，以 callback 形式注入新 resolver，避免同批重整 reattach 共用的 Cheer zone candidate provider。
- 移除 `MatchEffectService` 內原本的 `resolvePreferredAddCheerSource(...)`、`extractAddCheerSourceClause(...)`、`findAttachableCheerCard(...)`。
- 新增 `MatchAddCheerSourceResolverServiceTest`，覆蓋 archive source、cheer deck source 與 fallback candidate 查詢。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`4,939` 行。
- `src/main/java/com/hololive/cardgame/service/MatchAddCheerSourceResolverService.java`：`131` 行。
- `src/test/java/com/hololive/cardgame/service/MatchAddCheerSourceResolverServiceTest.java`：`96` 行。
- `src/main/java/com/hololive/cardgame/service/MatchAddCheerTargetResolverService.java`：`251` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftAddCheerEffectExecutionService.java`：`216` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。

## Verification

- TDD red：`./mvnw -q -Dtest=MatchAddCheerSourceResolverServiceTest test` 初次失敗，原因是 `MatchAddCheerSourceResolverService` 尚未存在。
- `./mvnw -q -Dtest=MatchAddCheerSourceResolverServiceTest test`：PASS。
- `./mvnw -q -Dtest=MatchAddCheerSourceResolverServiceTest,MatchAddCheerTargetResolverServiceTest,MatchGiftAddCheerEffectExecutionServiceTest,MatchGiftEffectServiceHandlersTest,MatchGiftReattachEffectExecutionServiceTest,MatchGiftEffectDispatcherTest,MatchGiftEffectExecutionCoordinatorTest test`：PASS。
- `./mvnw -q -DskipTests compile`：PASS。
- 沙盒內 `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportShouldAttachCheerToTargetHolomem+collabHsd01009ShouldSendCheerAndMoveSelfToBackWhenDiceIsOne+collabHsd13015ShouldReturnStageCheerThenAddCheer test`：FAIL，原因是 Docker / PostgreSQL socket `Operation not permitted`，未進入應用行為。
- 提權重跑同一條 support / collab `ADD_CHEER` integration command：PASS。

## Notes

- `MatchGiftAddCheerEffectExecutionService` 仍以 functional callback 接 source / target resolver；下一批可改成直接依賴 `MatchAddCheerSourceResolverService` 與 `MatchAddCheerTargetResolverService`，降低 wiring 間接層。
- `findCheerCardFromZone(...)` 仍留在 `MatchEffectService`，因 reattach 仍共用這個 Cheer zone candidate lookup。若後續要再降耦合，建議獨立拆 shared Cheer candidate provider。

## Next Step

下一批建議收斂 `ADD_CHEER` execution service 依賴：

- 讓 `MatchGiftAddCheerEffectExecutionService` 直接持有 source / target resolver service。
- 移除 source / target resolver functional interface 欄位與 constructor callback wiring。
- 保留 `findCheerCardFromZone(...)` callback 給 source resolver，下一批再評估是否抽 shared Cheer candidate provider。
