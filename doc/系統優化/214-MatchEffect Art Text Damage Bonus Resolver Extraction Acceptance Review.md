# MatchEffect Art Text Damage Bonus Resolver Extraction Acceptance Review

更新日期：2026-05-26

## Summary

AAA-214 已抽出 `MatchArtTextDamageBonusResolverService`，集中處理藝能文案自體加傷解析。

這批只搬移既有行為，不改 DB migration、REST / WebSocket public API，也不重新解釋官方規則。

## Key Changes

- 新增 package-private `MatchArtTextDamageBonusResolverService`。
- 將藝能文字加傷所需的 target context 查詢、raw text 擷取、句子切分、`このアーツ+N` 解析與條件判斷移出 `MatchEffectService`。
- `MatchEffectCombatModifierService.resolveArtTextDamageBonus(...)` 改委派新 resolver。
- 從 `MatchEffectService` 移除：
  - `ArtSelfBonusTargetContext`
  - `loadArtSelfBonusTargetContext(...)`
  - `resolveArtTextDamageBonusFromRawText(...)`
  - art text damage bonus 專用 helper。
- 保留 `MatchEffectService` 中仍被其他流程使用的支援卡 stat bonus、passive Gift 條件、extra bloom helper。

## Coverage

新增 `MatchArtTextDamageBonusResolverServiceTest`，覆蓋：

- 每張附著 Cheer 加傷。
- LIFE 3 以下加傷與 LIFE 高於 3 時不加傷。
- 本回合指定 Holomem 使用過藝能時加傷。
- 本回合指定推し技能使用過時加傷。
- 指定推し + 附著 Cheer 門檻加傷。
- public entry 會載入 target context 並解析 art effect JSON。

## Size

實際 `wc -l`：

```text
6,580 src/main/java/com/hololive/cardgame/service/MatchEffectService.java
  268 src/main/java/com/hololive/cardgame/service/MatchEffectCombatModifierService.java
  356 src/main/java/com/hololive/cardgame/service/MatchArtTextDamageBonusResolverService.java
  183 src/test/java/com/hololive/cardgame/service/MatchArtTextDamageBonusResolverServiceTest.java
6,099 src/main/java/com/hololive/cardgame/service/MatchActionService.java
32,302 src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java
```

`MatchEffectService` 由 AAA-213 的 `6,858` 行下降到 `6,580` 行。

## Verification

TDD red：

```bash
./mvnw -q -Dtest=MatchArtTextDamageBonusResolverServiceTest test
```

第一次執行時因 `MatchArtTextDamageBonusResolverService` 尚未存在而編譯失敗，符合紅燈預期。

Green：

```bash
./mvnw -q -Dtest=MatchArtTextDamageBonusResolverServiceTest test
./mvnw -q -Dtest=MatchArtTextDamageBonusResolverServiceTest,AttackDamageServiceTest test
./mvnw -q -Dtest=MatchDamageEffectExecutionServiceTest test
./mvnw -q -Dtest=MatchEffectDamageExecutionCharacterizationTest test
./mvnw -q -DskipTests compile
```

以上皆通過。

Focused integration：

```bash
./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#attackArtShouldApplyOfficialArtBonusAndAttachCheerForHsd13007WhenOpponentIsDowned+attackArtShouldApplyOfficialArtBonusHsd07009WhenLifeIsThreeOrLess+attackArtShouldApplyOfficialArtBonusHbp05050WhenOnlyReferencedOshiSkillUsedThisTurn+attackArtShouldApplyOfficialArtBonusHbp06052WhenMunaOshiAndFourCheersAttached+attackArtShouldApplyOfficialArtBonusHbp06070WhenReferencedOshiSkillUsedThisTurn' test
```

沙盒內第一次執行因 Docker / PostgreSQL socket 權限失敗，主要錯誤為 `Operation not permitted`。提權重跑後通過。

Additional checks：

```bash
git diff --check
```

通過。

## Risk Notes

- 這批沒有改 `AttackDamageService` 的 damage summary 欄位與公開 payload 形狀。
- `artTextDamageBonus` 仍由 `MatchEffectCombatModifierService` 對外提供，呼叫點不變。
- 新 resolver 內仍沿用既有 conservative text pattern，避免擴大支援範圍造成規則語意變動。

## Next Step

下一批建議拆 `applyArtDownTriggeredEffects(...)` 為 package-private executor，讓藝能文字加傷與藝能擊倒後 follow-up 分別落在相鄰但獨立的 art flow service。
