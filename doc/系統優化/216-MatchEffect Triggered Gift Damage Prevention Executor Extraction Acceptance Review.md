# MatchEffect Triggered Gift Damage Prevention Executor Extraction Acceptance Review

更新日期：2026-05-26

## Summary

AAA-216 將藝能傷害套用前的 `DAMAGE_RECEIVED` Gift 防傷流程，從 `MatchEffectService.resolveTriggeredGiftDamagePrevention(...)` 搬到 package-private `MatchTriggeredGiftDamagePreventionExecutionService`。

這批不改 DB migration、REST / WebSocket public API，也不重新解釋 Gift 規則；行為以既有 production code 與 HBP01-027 integration coverage 為準。

## Key Changes

- 新增 `MatchTriggeredGiftDamagePreventionExecutionService`：
  - 查詢受擊 target holomem 與防守方 Gift holder。
  - 保留 `DAMAGE_RECEIVED` trigger、holder zone、turn usage、回合歸屬、LIFE、手牌、collab presence、target zone / holder、來源等級與 dice 條件。
  - 保留 `PREVENT_DAMAGE` summary payload，包括 `incomingDamage`、`damageAfter`、`preventedDamage`、`diceRoll`、`diceMatched`、`executedEffects` 與 `skippedEffects`。
- 修改 `MatchTriggeredCombatEffectService`：
  - 直接委派新 executor 處理 `resolveTriggeredGiftDamagePrevention(...)`。
  - 暫時保留 `MatchEffectService` dependency，只供 art down triggered effect bridge 使用。
- 修改 `MatchEffectService`：
  - 移除 `resolveTriggeredGiftDamagePrevention(...)`。
  - 移除 damage-received 專用 target / collab / dice helper。
  - 保留其他 Gift 條件 helper，因仍有其他 effect family 使用。
- 新增 `MatchTriggeredGiftDamagePreventionExecutionServiceTest`：
  - context 不完整時不查 DB 並回傳 `null`。
  - dice 條件成立時 prevent damage。
  - dice 條件不成立時保留 summary、標記 skipped，且 damage 不變。

## Size

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`：`6,260` 行。
- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`6,099` 行。
- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`：`32,302` 行。
- `src/main/java/com/hololive/cardgame/service/MatchTriggeredCombatEffectService.java`：`74` 行。
- `src/main/java/com/hololive/cardgame/service/MatchTriggeredGiftDamagePreventionExecutionService.java`：`377` 行。
- `src/test/java/com/hololive/cardgame/service/MatchTriggeredGiftDamagePreventionExecutionServiceTest.java`：`192` 行。

## Verification

- TDD red：
  - `./mvnw -q -Dtest=MatchTriggeredGiftDamagePreventionExecutionServiceTest test`
  - 初次執行因 `MatchTriggeredGiftDamagePreventionExecutionService` 尚未存在而失敗。
- Focused unit：
  - `./mvnw -q -Dtest=MatchTriggeredGiftDamagePreventionExecutionServiceTest test`：pass。
  - `./mvnw -q -Dtest=MatchTriggeredGiftDamagePreventionExecutionServiceTest,AttackEffectFollowupServiceTest,AttackDamageApplicationServiceTest test`：pass。
- Compile：
  - `./mvnw -q -DskipTests compile`：pass。
- Focused integration：
  - 沙盒內 `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention+attackArtShouldNotPreventDamageWithOfficialGiftHbp01027WhenDiceConditionFailed test`：failed，原因是 PostgreSQL socket `Operation not permitted`。
  - 提權重跑同一指令：failed，原因是 Testcontainers Ryuk 逾時後 fallback 到 `localhost:5432`，但本機 PostgreSQL `Connection refused`。
  - `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention+attackArtShouldNotPreventDamageWithOfficialGiftHbp01027WhenDiceConditionFailed test`：pass。
- Cleanup check：
  - `docker ps`：確認沒有殘留本批 Testcontainers PostgreSQL container。
- Diff hygiene：
  - `git diff --check`：pass。

## Next Step

下一批建議做 AAA-217：整理 `MatchTriggeredCombatEffectService` 的 art down bridge。

目標是讓 `MatchTriggeredCombatEffectService.applyArtDownTriggeredEffects(...)` 直接持有並呼叫 `MatchArtDownTriggeredEffectExecutionService`，移除 `MatchEffectService.applyArtDownTriggeredEffects(...)` 這層剩餘委派。這批只做接線整理與 focused test，不搬 `applySupportEffect(...)`、raw text inference 或 support effect execution，以免擴大 blast radius。
