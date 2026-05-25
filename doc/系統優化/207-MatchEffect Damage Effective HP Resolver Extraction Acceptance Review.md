# 207-MatchEffect Damage Effective HP Resolver Extraction Acceptance Review

更新日期：2026-05-25

## 結論

本批完成 `DAMAGE` effective HP snapshot resolver extraction。`MatchDamageEffectiveHpResolverService` 接手 base HP、attached support HP bonus、self passive Gift HP bonus 的快照解析，`MatchDamageEffectExecutionService` 不再透過 `MatchEffectService` callback 取得 effective HP。

## 範圍

本批新增：

- `MatchDamageEffectiveHpResolverService`
- `MatchDamageEffectiveHpResolverServiceTest`

本批調整：

- `MatchEffectService` 建立並注入 `MatchDamageEffectiveHpResolverService`，移除原本的 `resolveDamageEffectiveHp(...)` 與 passive Gift HP 受益者查詢。
- `MatchEffectCombatModifierService` 改為共用 `MatchDamageEffectiveHpResolverService.resolvePassiveGiftHpBonus(...)`，避免同一段 passive Gift HP 規則留在 `MatchEffectService`。

本批不處理：

- DB migration。
- REST / WebSocket public API 契約。
- `MatchActionService` flow 重構。
- 特殊傷害免疫 resolver。
- passive gift incoming damage reduction resolver。
- passive Gift raw text parser 的跨 service 共用 helper 整理。

## 行數變化

- `MatchEffectService.java`：`8,432` 行降至 `8,306` 行。
- 新增 `MatchDamageEffectiveHpResolverService.java`：`254` 行。
- 新增 `MatchDamageEffectiveHpResolverServiceTest.java`：`134` 行。

## 驗證結果

已執行：

```bash
./mvnw -q -Dtest=MatchDamageEffectiveHpResolverServiceTest,MatchDamageEffectExecutionServiceTest,MatchEffectDamageExecutionCharacterizationTest test
./mvnw -q -DskipTests compile
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldApplyDamageToOpponentHolomemAndRestAttacker test
git diff --check
```

結果：

- Focused effective HP resolver unit test 通過。
- Damage execution service unit test 通過。
- Damage characterization test 通過。
- Java compile 通過。
- Focused integration test 通過；第一次沙盒內執行因 Docker / PostgreSQL socket 權限失敗，已提高權限後使用 Testcontainers PostgreSQL 重跑通過。
- diff whitespace 檢查通過。

## 下一步建議

下一批若繼續處理 `DAMAGE`，建議優先拆：

- `SpecialDamagePreventionResolver`：集中 `isSpecialDamageImmunityActive(...)` 與 HSD13-012 stack cost 防護解析。
- 或 passive gift incoming damage reduction resolver：集中一般受傷減免與藝能減傷解析。

兩者都屬戰鬥規則熱區，應維持單批只搬一個 resolver，先補 focused test 再改 production code。
