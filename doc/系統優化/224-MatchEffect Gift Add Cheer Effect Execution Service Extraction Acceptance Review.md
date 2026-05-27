# AAA-224 MatchEffect Gift Add Cheer Effect Execution Service Extraction Acceptance Review

日期：2026-05-27

## Summary

本批延續 AAA-223，先抽出 `ADD_CHEER` 的主執行 shell，新增 package-private `MatchGiftAddCheerEffectExecutionService`。

這批不改 `ADD_CHEER` 規則、不改 DB migration、REST / WebSocket public API。來源候選、目標解析、count 解析與目前 turn 查詢仍保留在 `MatchEffectService` callback，避免同批混入高耦合規則搬移。

## Key Changes

- 新增 `MatchGiftAddCheerEffectExecutionService`。
- 新 service 集中處理：
  - raw text / resolved effect clause 擷取。
  - BACK target preference 判斷。
  - `ADD_CHEER` attach count 決定。
  - 透過 `SendCheerAction` 與 `GameActionExecutor` 附加 Cheer。
  - executor 失敗時保留既有 direct SQL fallback。
  - `attachRequested` / `attachApplied` / `targetHolomemCardInstanceId` / `attachedCheerCardInstanceIds` / `sourceZones` summary 組裝。
- `MatchEffectService.executeAddCheerEffect(...)` 保留原簽名，但改為委派新 service，維持 Bloom / Collab / Gift 既有呼叫點相容。
- 新增 `MatchGiftAddCheerEffectExecutionServiceTest`，覆蓋 executor 成功、executor 失敗 fallback、目標無法解析錯誤。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`5,229` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftAddCheerEffectExecutionService.java`：`216` 行。
- `src/test/java/com/hololive/cardgame/service/MatchGiftAddCheerEffectExecutionServiceTest.java`：`138` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。

## Verification

- TDD red：`./mvnw -q -Dtest=MatchGiftAddCheerEffectExecutionServiceTest test` 初次失敗，原因是 `MatchGiftAddCheerEffectExecutionService` 尚未存在。
- `./mvnw -q -Dtest=MatchGiftAddCheerEffectExecutionServiceTest test`：PASS。
- `./mvnw -q -Dtest=MatchGiftAddCheerEffectExecutionServiceTest,MatchGiftReattachEffectExecutionServiceTest,MatchGiftEffectDispatcherTest,MatchGiftEffectExecutionCoordinatorTest test`：PASS。
- 沙盒內 `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportShouldAttachCheerToTargetHolomem+collabHsd01009ShouldSendCheerAndMoveSelfToBackWhenDiceIsOne+collabHsd13015ShouldReturnStageCheerThenAddCheer test`：FAIL，原因是 Docker / PostgreSQL socket `Operation not permitted`，未進入應用行為。
- 提權重跑同一條 support / collab `ADD_CHEER` integration command：PASS。

## Notes

- 本批保留 `MatchEffectService.executeAddCheerEffect(...)` 作為薄 bridge，避免同步修改 Bloom / Collab / Gift dispatcher 接線。
- `resolvePreferredAddCheerTargetHolomemId(...)`、`resolvePreferredAddCheerSource(...)`、`resolveCheerCount(...)`、`resolveCurrentTurnNumber(...)`、`resolveHolomemCardInstanceId(...)` 仍由 `MatchEffectService` 提供 callback。
- `ADD_CHEER` 的來源區、目標區位、等級條件與 sequential choice 仍是下一批拆分重點。

## Next Step

下一批建議做 `ADD_CHEER` bridge cleanup：

- `MatchBloomEffectDispatcher` 直接持有 `MatchGiftAddCheerEffectExecutionService`。
- `MatchCollabEffectDispatcher` 直接持有 `MatchGiftAddCheerEffectExecutionService`。
- `MatchGiftEffectServiceHandlers` 直接持有 `MatchGiftAddCheerEffectExecutionService`。
- 移除 `MatchEffectService.executeAddCheerEffect(...)` 薄 delegate。
- 不搬移 target/source resolver；橋接清完後再拆 `AddCheerTargetResolver` / `AddCheerSourceResolver`。
