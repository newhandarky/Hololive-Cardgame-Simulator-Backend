# MatchEffect Gift Effect Execution Coordinator Extraction Acceptance Review

更新日期：2026-05-26

## Summary

AAA-219 將 Gift effect execution 的 sequential-cost 判斷、cost / resolved effect 執行順序、executed / unsupported / skipped summary 收斂與 `GiftExecutionSummary` 組裝，從 `MatchEffectService` 搬到 package-private `MatchGiftEffectExecutionCoordinator`。

這批不改 DB migration、REST / WebSocket public API，也不改 Gift 規則語意；`MatchEffectService.executeGiftEffectByType(...)` 仍保留副作用分派入口，避免同批搬動 `applySupportEffect(...)` 與各 effect family SQL。

## Key Changes

- 新增 `MatchGiftEffectExecutionCoordinator`：
  - 解析冒號前後 clause，判斷是否存在 meaningful sequential cost。
  - 成本段支付成功時才執行 resolved effect。
  - 成本段支付失敗時組裝 `前置成本未支付` skipped summary。
  - 統一收斂 executed / unsupported / skipped summary。
  - 保留 HBP05-035 類「冒號前綴只是觸發敘述，不是成本」的既有行為。
- 修改 `MatchEffectService`：
  - 建立 `giftEffectExecutionCoordinator` 欄位。
  - `executeGiftEffectsForHolder(...)` 改委派 coordinator。
  - 保留 `executeGiftEffectByType(...)` 作為副作用執行入口。
  - 移除原本的 Gift sequential-cost / execute-safely / summary helper。
- 新增 `MatchGiftEffectExecutionCoordinatorTest`：
  - 覆蓋 sequential cost 成功後執行 resolved effect。
  - 覆蓋 sequential cost 失敗時跳過 resolved effect。
  - 覆蓋 unsupported effect 轉為 skipped summary。
  - 覆蓋冒號前綴非成本時使用 resolved clause。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`5,924` 行。
- `src/main/java/com/hololive/cardgame/service/MatchGiftEffectExecutionCoordinator.java`：`153` 行。
- `src/test/java/com/hololive/cardgame/service/MatchGiftEffectExecutionCoordinatorTest.java`：`112` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。

## Verification

- TDD red：
  - `./mvnw -q -Dtest=MatchGiftEffectExecutionCoordinatorTest test`
  - 初次執行因 `MatchGiftEffectExecutionCoordinator` 尚未存在而 compilation failed。
- Focused unit：
  - `./mvnw -q -Dtest=MatchGiftEffectExecutionCoordinatorTest test`：pass。
  - `./mvnw -q -Dtest=MatchGiftEffectExecutionCoordinatorTest,MatchEffectTypeInferenceServiceTest,GiftTurnUsageReaderTest,GiftTriggerActionWriterTest test`：pass。
- Compile：
  - `./mvnw -q -DskipTests compile`：pass。
- Focused integration：
  - `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerOfficialGiftHbp05035WhenOwnSakuraMikoDowned+mainStepGiftHsd13013ShouldArchiveStackCostAndAttachCheerFromDeckTop+mainStepGiftHsd13013ShouldNotAttachCheerWithoutStackCost+advancePhaseShouldSkipOfficialGiftHsd11006WhenHandHasNoMatchingFlowGlowMember test`：pass。
- Cleanup check：
  - `docker ps`：確認沒有殘留本批 Testcontainers PostgreSQL container。
- Diff hygiene：
  - `git diff --check`：pass。

## Notes

- 本批只拆 Gift execution orchestration，不拆 `executeGiftEffectByType(...)` 的 switch routing。
- `executeGiftEffectByType(...)` 仍留在 `MatchEffectService`，因其中仍有 `executeAddCheerEffect(...)`、`executeDamageEffect(...)`、`executeReattachEffect(...)`、`executeArchiveStackCardEffect(...)`、`executeBuffDebuffEffect(...)` 等高耦合 handler。

## Next Step

下一批建議做 AAA-220：拆分 Gift effect type 分派器。

目標是抽出 package-private `MatchGiftEffectDispatcher`，先搬 `executeGiftEffectByType(...)` 的 switch routing；對已抽出的低耦合 execution services 直接注入，對仍留在 `MatchEffectService` 的高耦合副作用 handler 先以 callback 保留，避免同批改 Gift 規則語意或搬動 `applySupportEffect(...)`。
