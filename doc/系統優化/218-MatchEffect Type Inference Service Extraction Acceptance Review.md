# MatchEffect Type Inference Service Extraction Acceptance Review

更新日期：2026-05-26

## Summary

AAA-218 將 Bloom / Collab / Gift / ArtDown 共用的 effect type 與 target type 文案分類邏輯，從 `MatchEffectService` 搬到 package-private `MatchEffectTypeInferenceService`。

這批不改 DB migration、REST / WebSocket public API，也不改文案分類語意；測試期望以搬移前 production code 的既有推斷結果為準。

## Key Changes

- 新增 `MatchEffectTypeInferenceService`：
  - `inferEffectTypes(...)` 集中原本 `inferBloomEffectTypes(...)` 的文案分類規則。
  - `inferTargetType(...)` 集中原本 `inferBloomTargetType(...)` 的 SELF / ENEMY 目標側判斷。
- 修改 `MatchEffectService`：
  - 建立 `effectTypeInferenceService` 欄位。
  - Gift execution、Gift preview、Bloom plan、Collab plan 與 structured fallback raw text 解析改用新 service。
  - 移除原本的 `inferBloomEffectTypes(...)` 與 `inferBloomTargetType(...)` 方法。
- 修改 `MatchBloomEffectDispatcher` / `MatchCollabEffectDispatcher`：
  - constructor 注入 `MatchEffectTypeInferenceService`。
  - effect target type 判斷改由新 service 提供，不再透過 `MatchEffectService` 取得。
- 修改 `MatchTriggeredCombatEffectService`：
  - art down executor callback 改接新 service 的 `inferEffectTypes(...)` / `inferTargetType(...)`。
- 新增 `MatchEffectTypeInferenceServiceTest`：
  - 覆蓋 composite 文案分類順序與 replacement rule。
  - 覆蓋 blank / unknown text 回傳 `UNIMPLEMENTED`。
  - 覆蓋 ENEMY / SELF target type 分類。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`6,082` 行。
- `src/main/java/com/hololive/cardgame/service/MatchEffectTypeInferenceService.java`：`166` 行。
- `src/test/java/com/hololive/cardgame/service/MatchEffectTypeInferenceServiceTest.java`：`42` 行。
- `src/main/java/com/hololive/cardgame/service/MatchBloomEffectDispatcher.java`：`349` 行。
- `src/main/java/com/hololive/cardgame/service/MatchCollabEffectDispatcher.java`：`339` 行。
- `src/main/java/com/hololive/cardgame/service/MatchTriggeredCombatEffectService.java`：`93` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。

## Verification

- TDD red：
  - `./mvnw -q -Dtest=MatchEffectTypeInferenceServiceTest test`
  - 初次執行因 `MatchEffectTypeInferenceService` 尚未存在而 compilation failed。
- Focused unit：
  - `./mvnw -q -Dtest=MatchEffectTypeInferenceServiceTest test`：pass。
  - `./mvnw -q -Dtest=MatchEffectTypeInferenceServiceTest,MatchTriggeredCombatEffectServiceTest,MatchArtDownTriggeredEffectExecutionServiceTest,AttackDownServiceTest test`：pass。
- Compile：
  - `./mvnw -q -DskipTests compile`：pass。
- Focused integration：
  - `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomHsd13011ShouldArchiveStackedDebutAndDamageOpponentCollab+collabHsd13015ShouldReturnStageCheerThenAddCheer+attackArtShouldApplyOfficialArtBonusAndAttachCheerForHsd13007WhenOpponentIsDowned+attackArtShouldNotAttachCheerForHsd13007WhenOpponentSurvives test`：pass。
- Cleanup check：
  - `docker ps`：確認沒有殘留本批 Testcontainers PostgreSQL container。
- Diff hygiene：
  - `git diff --check`：pass。

## Notes

- `MatchEffectTypeInferenceServiceTest` 的 composite 文案 case 保留既有 production 行為：同段文字若同時包含「手札に加える」「アーカイブ」「エール」等片段，會同時推導出 `REPLACE_ARCHIVE_WITH_HAND`、`ADD_CHEER`、`REMOVE_CHEER`、`DISCARD_HAND`。
- 本批只搬分類邏輯，不重新設計文案解析規則。

## Next Step

下一批建議做 AAA-219：拆分 Gift effect 執行摘要協調邏輯。

目標是整理 `executeGiftEffectsForHolder(...)` / `executeGiftEffectSafely(...)` 周邊的 sequential-cost、executed / unsupported / skipped summary 協調，先抽純 summary / dispatch helper，不搬 `applySupportEffect(...)` 與各 effect family SQL。
