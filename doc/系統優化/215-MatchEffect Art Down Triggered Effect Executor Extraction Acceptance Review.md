# MatchEffect Art Down Triggered Effect Executor Extraction Acceptance Review

更新日期：2026-05-26

## Summary

AAA-215 已抽出 `MatchArtDownTriggeredEffectExecutionService`，集中處理藝能擊倒對手後才發動的 follow-up 效果。

這批只搬移既有流程，不改 DB migration、REST / WebSocket public API，也不改 damage、down event、life loss 或 `artDownTriggeredEffectSummary` payload 語意。

## Key Changes

- 新增 package-private `MatchArtDownTriggeredEffectExecutionService`。
- 搬移藝能 down follow-up 的：
  - raw text 擷取入口協作。
  - `このアーツで相手のホロメンをダウンさせた時` clause 解析。
  - no-op summary 建構。
  - effect type / target type 解析後的 `applySupportEffect(...)` 包裝。
- `MatchEffectService.applyArtDownTriggeredEffects(...)` 保留原入口，只改為委派新 executor。
- 高耦合依賴先以 callback 注入：
  - art effect raw text extractor。
  - Bloom effect type resolver。
  - Bloom target type resolver。
  - support effect applier。

## Coverage

新增 `MatchArtDownTriggeredEffectExecutionServiceTest`，覆蓋：

- 藝能沒有 down follow-up 時回傳 `ART_DOWNED_OPPONENT` no-op summary。
- follow-up 無法解析 effect type 時回傳 no-op summary。
- 成功解析 follow-up 時包裝 `applySupportEffect(...)` summary，並保留 `triggerType`、follow-up `rawText`、source card instance id 與 target type。

## Size

實際 `wc -l`：

```text
6,527 src/main/java/com/hololive/cardgame/service/MatchEffectService.java
  130 src/main/java/com/hololive/cardgame/service/MatchArtDownTriggeredEffectExecutionService.java
  126 src/test/java/com/hololive/cardgame/service/MatchArtDownTriggeredEffectExecutionServiceTest.java
6,099 src/main/java/com/hololive/cardgame/service/MatchActionService.java
32,302 src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java
```

`MatchEffectService` 由 AAA-214 的 `6,580` 行下降到 `6,527` 行。

## Verification

TDD red：

```bash
./mvnw -q -Dtest=MatchArtDownTriggeredEffectExecutionServiceTest test
```

第一次執行時因 `MatchArtDownTriggeredEffectExecutionService` 尚未存在而編譯失敗，符合紅燈預期。

Green：

```bash
./mvnw -q -Dtest=MatchArtDownTriggeredEffectExecutionServiceTest test
./mvnw -q -Dtest=MatchArtDownTriggeredEffectExecutionServiceTest,AttackDownServiceTest,MatchArtTextDamageBonusResolverServiceTest test
./mvnw -q -DskipTests compile
```

以上皆通過。

Focused integration：

```bash
./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#attackArtShouldApplyOfficialArtBonusAndAttachCheerForHsd13007WhenOpponentIsDowned+attackArtShouldNotAttachCheerForHsd13007WhenOpponentSurvives' test
```

沙盒內第一次執行因 Docker / PostgreSQL socket 權限失敗，主要錯誤為 `Operation not permitted`。提權重跑後通過。

Additional checks：

```bash
git diff --check
```

通過。

## Risk Notes

- `AttackDownService` 與 `MatchTriggeredCombatEffectService` 的 public 呼叫鏈不變。
- 新 executor 不直接解釋對戰規則，只封裝原本的 clause 解析與 `applySupportEffect(...)` 包裝流程。
- HSD13-007 down / survive integration 已覆蓋「真的 down 才加 Cheer」與「未 down 不觸發 follow-up」兩條路徑。

## Next Step

下一批可評估 `resolveTriggeredGiftDamagePrevention(...)` 的拆分，讓 `MatchTriggeredCombatEffectService` 不再需要回呼 `MatchEffectService` 執行受擊觸發 Gift 防護流程。
