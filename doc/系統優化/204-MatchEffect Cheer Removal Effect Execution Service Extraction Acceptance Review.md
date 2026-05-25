# 204-MatchEffect Cheer Removal Effect Execution Service Extraction Acceptance Review

更新日期：2026-05-25

## 結論

本批已將 `REMOVE_CHEER` 與 `REMOVE_STAGE_CHEER` 執行流程從 `MatchEffectService` 抽出到 package-private `MatchCheerRemovalEffectExecutionService`。`MatchEffectService` 保留 dispatch / orchestration，Support / Bloom / Collab 的 Cheer 移除執行改委派新 service。

## 範圍

本批處理：

- `REMOVE_CHEER` 目標 Holomem 解析與擁有者解析。
- attached cheer row 查詢、刪除與對應 `match_cards` 移到 `ARCHIVE`。
- `REMOVE_STAGE_CHEER` 從己方場上 Holomem attached cheer 依序移除。
- `value` / `cards` / `amount` 與 raw text `エールN枚` 數量解析。
- attached cheer 缺 `match_card_id` 時，以 `cheer_card_id` fallback 查找 STAGE card instance。
- archive `order_index` 計算與 summary payload。

本批不處理：

- `DAMAGE` / 特殊傷害 / down event / life loss。
- Cheer 回牌庫底流程，該責任已由 `MatchCheerDeckReturnEffectExecutionService` 處理。
- 公開 API、資料庫 migration、seed data。

## 行數變化

- `MatchEffectService.java`：`8,789` 行 -> `8,651` 行，淨減 `138` 行。
- 新增 `MatchCheerRemovalEffectExecutionService.java`：`262` 行。
- 新增 `MatchCheerRemovalEffectExecutionServiceTest.java`：`212` 行。
- `MatchActionServiceIntegrationTest.java`：維持 `32,302` 行。

## 行為保護

本批保留既有語意，並鎖住以下細節：

- `REMOVE_CHEER` 的單一目標 attached cheer 即使查詢 row 未帶 `match_holomem_id`，仍可用目標 Holomem 作為刪除來源。
- `REMOVE_STAGE_CHEER` 可從己方場上 Holomem 的 attached cheer 依序移除，並保留來源 Holomem ids。
- 成功刪除 attached cheer 後，對應 STAGE `match_cards` 會移到 `ARCHIVE`，且 archive order 延續既有最大值。
- 刪除 row 失敗時不移動卡片，summary 以 `removeApplied = 0` 回報。

## 驗證結果

已執行：

```bash
./mvnw -q -Dtest=MatchCheerRemovalEffectExecutionServiceTest test
./mvnw -q -DskipTests compile
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportRemoveCheerShouldDetachAndArchiveCheerCard test
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#collabHbp06078ShouldPayAttachedCheerCostThenSearchOshiSameNameDebut test
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomHbp06081ShouldRequireSubaruOshiAndArchiveStageCheerBeforeSearch test
```

結果：

- Unit test 通過。
- Compile 通過。
- Support / Collab / Bloom focused integration 通過。

## 下一步建議

下一批建議正式評估 `DAMAGE` 拆分前的保護測試與責任切法。`DAMAGE` 是目前剩餘風險最高的 effect family，牽涉目標解析、傷害修正、special damage、down event、life loss 與 gift follow-up，不建議直接一次搬完整流程；建議先補 focused tests，再切出小型 `MatchDamageEffectExecutionService` 或先抽 damage calculation / target resolution 子責任。
