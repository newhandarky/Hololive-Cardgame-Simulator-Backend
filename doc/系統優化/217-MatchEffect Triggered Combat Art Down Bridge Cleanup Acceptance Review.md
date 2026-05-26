# MatchEffect Triggered Combat Art Down Bridge Cleanup Acceptance Review

更新日期：2026-05-26

## Summary

AAA-217 整理 `MatchTriggeredCombatEffectService.applyArtDownTriggeredEffects(...)` 的剩餘 bridge。

這批不改 DB migration、REST / WebSocket public API，也不改 HSD13-007 類藝能擊倒後 follow-up 的規則語意；目標只是在 `MatchTriggeredCombatEffectService` 直接持有 `MatchArtDownTriggeredEffectExecutionService`，移除 `MatchEffectService.applyArtDownTriggeredEffects(...)` 中轉入口。

## Key Changes

- 修改 `MatchTriggeredCombatEffectService`：
  - 新增 `MatchArtDownTriggeredEffectExecutionService` 欄位。
  - production constructor 直接建立 art down executor，callback 仍接回既有 `MatchEffectService` 的 raw text、effect type、target type 與 support effect execution 能力。
  - `applyArtDownTriggeredEffects(...)` 改為直接呼叫 art down executor。
  - 新增 package-private constructor，讓 focused test 可直接注入 executor。
- 修改 `MatchEffectService`：
  - 移除 `artDownTriggeredEffectExecutionService` 欄位。
  - 移除 `applyArtDownTriggeredEffects(...)` bridge method。
  - 將 `inferBloomEffectTypes(...)` 從 private 調整為 package-private，供 triggered combat constructor callback 使用。
- 新增 `MatchTriggeredCombatEffectServiceTest`：
  - 驗證 `applyArtDownTriggeredEffects(...)` 使用注入的 art down executor，並保留 `ART_DOWNED_OPPONENT` wrapped summary。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`6,225` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。
- `src/main/java/com/hololive/cardgame/service/MatchTriggeredCombatEffectService.java`：`92` 行。
- `src/test/java/com/hololive/cardgame/service/MatchTriggeredCombatEffectServiceTest.java`：`70` 行。

## Verification

- TDD red：
  - `./mvnw -q -Dtest=MatchTriggeredCombatEffectServiceTest test`
  - 初次執行因 `MatchTriggeredCombatEffectService` 尚未提供 direct executor constructor 而 compilation failed。
- Focused unit：
  - `./mvnw -q -Dtest=MatchTriggeredCombatEffectServiceTest test`：pass。
  - `./mvnw -q -Dtest=MatchTriggeredCombatEffectServiceTest,MatchArtDownTriggeredEffectExecutionServiceTest,AttackDownServiceTest test`：pass。
- Compile：
  - `./mvnw -q -DskipTests compile`：pass。
- Focused integration：
  - 初次 `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldApplyOfficialArtBonusAndAttachCheerForHsd13007WhenOpponentIsDowned+attackArtShouldNotAttachCheerForHsd13007WhenOpponentSurvives test` failed，原因是新增測試用 constructor 後 Spring 未選擇 production constructor，觸發 `No default constructor found`。
  - 在 production constructor 加上 `@Autowired` 後，重跑同一 focused integration：pass。
- Cleanup check：
  - `docker ps`：確認沒有殘留本批 Testcontainers PostgreSQL container。
- Diff hygiene：
  - `git diff --check`：pass。

## Next Step

下一批建議做 AAA-218：抽出 `MatchEffectTypeInferenceService`。

目標是把 `inferBloomEffectTypes(...)` 與 `inferBloomTargetType(...)` 從 `MatchEffectService` 移到 package-private service，讓 Bloom / Collab dispatcher、Gift 解析與 ArtDown follow-up 都能依賴同一個文案分類器。這批先不搬 `applySupportEffect(...)`，也不重新設計 effect JSON execution，避免把分類解析與副作用執行混在同一個 commit。
