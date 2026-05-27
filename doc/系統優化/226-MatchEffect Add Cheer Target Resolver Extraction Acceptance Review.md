# AAA-226 MatchEffect Add Cheer Target Resolver Extraction Acceptance Review

日期：2026-05-27

## Summary

本批延續 AAA-225，抽出 `ADD_CHEER` 目標解析器 `MatchAddCheerTargetResolverService`。

這批只搬移目標解析，不改來源解析、不改 attach 執行、不改 DB migration、REST / WebSocket public API。

## Key Changes

- 新增 `MatchAddCheerTargetResolverService`。
- 新 service 集中處理：
  - `ADD_CHEER` target clause 擷取。
  - `自分のホロメン` fallback 判斷。
  - `他の` / `以外` exclude holder 判斷。
  - target zone / level / name / tag 條件解析。
  - owned stage Holomem candidate SQL 查詢。
  - restricted target 找不到時不 fallback 的既有保護規則。
- `MatchEffectService` 建立 `addCheerTargetResolverService` 欄位。
- `MatchGiftAddCheerEffectExecutionService` 與 `MatchGiftReattachEffectExecutionService` 的 ADD_CHEER target callback 改接新 resolver。
- `ARCHIVE_STACK_CARD` 需要的 stack level 文案解析改由新 resolver 提供。
- 移除 `MatchEffectService` 內原本的 ADD_CHEER target helper。
- 新增 `MatchAddCheerTargetResolverServiceTest`，覆蓋 no restriction delegate、any own Holomem fallback、BACK restriction、restricted target no-fallback。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`5,020` 行。
- `src/main/java/com/hololive/cardgame/service/MatchAddCheerTargetResolverService.java`：`251` 行。
- `src/test/java/com/hololive/cardgame/service/MatchAddCheerTargetResolverServiceTest.java`：`100` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftAddCheerEffectExecutionService.java`：`216` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。

## Verification

- TDD red：`./mvnw -q -Dtest=MatchAddCheerTargetResolverServiceTest test` 初次失敗，原因是 `MatchAddCheerTargetResolverService` 尚未存在。
- `./mvnw -q -Dtest=MatchAddCheerTargetResolverServiceTest test`：PASS。
- `./mvnw -q -Dtest=MatchAddCheerTargetResolverServiceTest,MatchGiftAddCheerEffectExecutionServiceTest,MatchGiftEffectServiceHandlersTest,MatchGiftReattachEffectExecutionServiceTest,MatchGiftEffectDispatcherTest,MatchGiftEffectExecutionCoordinatorTest test`：PASS。
- `./mvnw -q -DskipTests compile`：PASS。
- 沙盒內 `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportShouldAttachCheerToTargetHolomem+collabHsd01009ShouldSendCheerAndMoveSelfToBackWhenDiceIsOne+collabHsd13015ShouldReturnStageCheerThenAddCheer test`：FAIL，原因是 Docker / PostgreSQL socket `Operation not permitted`，未進入應用行為。
- 提權重跑同一條 support / collab `ADD_CHEER` integration command：PASS。

## Notes

- `resolvePreferredAddCheerSource(...)`、`extractAddCheerSourceClause(...)`、`findAttachableCheerCard(...)` 仍留在 `MatchEffectService`，下一批再處理。
- `findCheerCardFromZone(...)` 目前仍被 reattach 與 source fallback 使用，本批沒有搬動。

## Next Step

下一批建議拆 `ADD_CHEER` source resolver：

- 新增 `MatchAddCheerSourceResolverService`。
- 搬移 `resolvePreferredAddCheerSource(...)`、`extractAddCheerSourceClause(...)`、`findAttachableCheerCard(...)`。
- `findCheerCardFromZone(...)` 先以 callback 注入，或等下一批再評估是否抽 shared cheer candidate provider。
