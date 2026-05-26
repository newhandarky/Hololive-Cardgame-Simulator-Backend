# 210-MatchEffect Attached Support Incoming Damage Reduction Resolver Extraction Acceptance Review

更新日期：2026-05-26

## 結論

本批完成 attached support incoming damage reduction resolver extraction。`MatchAttachedSupportIncomingDamageReductionResolverService` 接手附著支援卡受傷減免查詢、raw text 解析、base segment 過濾、holder clause 判斷與 target zone 限制，`MatchEffectCombatModifierService` 不再回呼 `MatchEffectService` 執行這段解析。

## 範圍

本批新增：

- `MatchAttachedSupportIncomingDamageReductionResolverService`
- `MatchAttachedSupportIncomingDamageReductionResolverServiceTest`

本批調整：

- `MatchEffectCombatModifierService.resolveAttachedSupportIncomingDamageReduction(...)` 改為委派 resolver。
- 移除 `MatchEffectService` 內原本的 `extractAttachedSupportIncomingDamageReduction(...)` 與 attached support damage reduction 專用 pattern / zone matcher。
- 保留 `MatchEffectService` 內 conditional trigger 仍使用的 attached support holder clause helper。

本批不處理：

- DB migration。
- REST / WebSocket public API 契約。
- `MatchActionService` flow 重構。
- passive Gift HP change prevention resolver。
- attached support conditional trigger preview / execution。
- damage execution 的 down / life loss / no-op summary 行為調整。

## 行數變化

- `MatchEffectService.java`：`7,764` 行降至 `7,717` 行。
- `MatchEffectCombatModifierService.java`：`299` 行。
- 新增 `MatchAttachedSupportIncomingDamageReductionResolverService.java`：`118` 行。
- 新增 `MatchAttachedSupportIncomingDamageReductionResolverServiceTest.java`：`78` 行。

## 驗證結果

TDD 紅燈：

```bash
./mvnw -q -Dtest=MatchAttachedSupportIncomingDamageReductionResolverServiceTest test
```

結果：

- 初次執行因 `MatchAttachedSupportIncomingDamageReductionResolverService` 尚未存在而 test compile 失敗，符合預期紅燈。

已執行：

```bash
./mvnw -q -Dtest=MatchAttachedSupportIncomingDamageReductionResolverServiceTest test
./mvnw -q -Dtest=MatchAttachedSupportIncomingDamageReductionResolverServiceTest,AttackDamageServiceTest test
./mvnw -q -DskipTests compile
./mvnw -q -Dtest=OfficialCardSmokeCoverageIntegrationTest#officialAttachableSupportDamageReductionShouldRemainOngoingSmokeCovered test
```

結果：

- Focused resolver unit test 通過。
- `AttackDamageServiceTest` 通過，確認 attack damage modifier caller 保持相容。
- Java compile 通過。
- Official attached support damage reduction smoke integration 通過；第一次沙盒內執行因 Docker / PostgreSQL socket 權限失敗，已提高權限後使用 Testcontainers PostgreSQL 重跑通過。

## 下一步建議

下一批若繼續處理 `DAMAGE` 周邊，建議拆 passive Gift HP change prevention resolver，集中「相手能力不能改變 HP」的 target context、holder list 與條件解析。

該區塊仍屬戰鬥規則熱區，建議先補 focused test 鎖住 holder zone、target zone、level/tag/name 條件，再把 `blocksOpponentAbilityHpChangeFromHolder(...)` 與相關 loader 移出 `MatchEffectService`。
