# 213-MatchEffect Passive Gift Art Cost Reduction Resolver Extraction Acceptance Review

更新日期：2026-05-26

## 結論

本批完成 passive Gift art cost reduction resolver extraction。`MatchPassiveGiftArtCostReductionResolverService` 接手 passive Gift 藝能費用減免的 target context 查詢、CENTER / COLLAB holder 查詢、holder 文案解析、cheer color 減免值、holder / target 站位、target attached support、指定 art name 與歷史推し技能條件解析。

`MatchEffectCombatModifierService.resolvePassiveGiftArtCheerCostReduction(...)` 保留 public 入口，但改為委派新 resolver；`MatchEffectService` 不再承接 passive Gift 藝能費用減免規則。

## 範圍

本批新增：

- `MatchPassiveGiftArtCostReductionResolverService`
- `MatchPassiveGiftArtCostReductionResolverServiceTest`

本批調整：

- `MatchEffectCombatModifierService` 建立 `MatchPassiveGiftArtCostReductionResolverService` 欄位。
- `resolvePassiveGiftArtCheerCostReduction(...)` 改為委派 resolver。
- 移除 `MatchEffectService` 內原本的 `PassiveGiftArtCostReductionTargetContext`、art cost target loader、CENTER / COLLAB holder loader、holder resolver、art name 條件與 cost reduction result builder。
- 保留 `MatchEffectService.matchesPassiveGiftAttachedSupportCondition(...)`，因 `MatchGiftTriggerService` 仍透過 method reference 使用這段 holder attached support eligibility。

本批不處理：

- DB migration。
- REST / WebSocket public API 契約。
- `MatchActionService` flow 重構。
- art text damage bonus resolver。
- damage execution 的 no-op summary、damage_taken、down、life loss 行為調整。

## 行數變化

- `MatchEffectService.java`：`7,260` 行降至 `6,858` 行。
- `MatchEffectCombatModifierService.java`：`269` 行。
- 新增 `MatchPassiveGiftArtCostReductionResolverService.java`：`548` 行。
- 新增 `MatchPassiveGiftArtCostReductionResolverServiceTest.java`：`186` 行。

## 驗證結果

TDD 紅燈：

```bash
./mvnw -q -Dtest=MatchPassiveGiftArtCostReductionResolverServiceTest test
```

結果：

- 初次執行因 `MatchPassiveGiftArtCostReductionResolverService` 尚未存在而 test compile 失敗，符合預期紅燈。

已執行：

```bash
./mvnw -q -Dtest=MatchPassiveGiftArtCostReductionResolverServiceTest test
./mvnw -q -Dtest=MatchPassiveGiftArtCostReductionResolverServiceTest,MatchPassiveGiftArtBonusResolverServiceTest,AttackArtApplicationAdapterFactoryTest test
./mvnw -q -Dtest=MatchDamageEffectExecutionServiceTest test
./mvnw -q -Dtest=MatchEffectDamageExecutionCharacterizationTest test
./mvnw -q -DskipTests compile
```

結果：

- Focused passive Gift art cost reduction resolver unit test 通過。
- Passive Gift art bonus resolver 與 attack art adapter focused unit tests 通過，確認 combat modifier 兩個 passive Gift 入口拆分後仍維持輸出。
- Damage execution focused unit test 通過。
- Damage characterization test 通過。
- Java compile 通過。

Focused integration：

```bash
./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#attackArtShouldApplyOfficialPassiveGiftHbp06056ArtCostReductionAfterReferencedSpOshiSkillUsedThisGame+attackArtShouldNotApplyOfficialPassiveGiftHbp06056ArtCostReductionWithoutReferencedSpOshiSkillHistory' test
```

結果：

- 通過。
- Testcontainers PostgreSQL 正常啟動並完成 HBP06-056 passive Gift art cost reduction 成功 / 無 SP 推し技能歷史失敗兩個 integration cases。

## 下一步建議

下一批若繼續降低 `MatchEffectService` 與 combat modifier 的耦合，建議拆 art text damage bonus resolver：

- 搬移 `ArtSelfBonusTargetContext` 與 `loadArtSelfBonusTargetContext(...)`。
- 搬移 `resolveArtTextDamageBonusFromRawText(...)` 及其附著 Cheer、低 LIFE、指定藝能使用歷史、推し技能使用歷史條件解析。
- 保留 `MatchEffectCombatModifierService.resolveArtTextDamageBonus(...)` public 入口，只改為委派新 resolver。

這批應先補 focused unit test，再跑 HSD13-007 / HSD07-009 或現有命名聚焦 integration cases；不要和 `MatchActionService` flow 重構混在同批。
