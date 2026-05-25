# 203-MatchEffect Move Zone Effect Execution Service Extraction Acceptance Review

更新日期：2026-05-25

## 結論

本批已將 `MOVE_ZONE` 執行流程從 `MatchEffectService` 抽出到 package-private `MatchMoveZoneEffectExecutionService`。`MatchEffectService` 保留 dispatch / orchestration，Support / Bloom / Collab 的實際區域移動執行改委派新 service。

## 範圍

本批處理：

- dice condition no-op。
- target Holomem 解析後的 CENTER / BACK / COLLAB 移動。
- `バックホロメン` raw text fallback 目標解析。
- `コラボホロメンがいないなら` 條件。
- action lock 檢查。
- destination capacity 檢查。
- `GameActionExecutor` 的 `HolomemMoveZoneAction` 執行與 SQL fallback。
- summary payload：`effectType`、`targetHolomemId`、`targetHolomemCardInstanceId`、`fromZone`、`toZone`、`rested`、`moved`。

本批不處理：

- `DAMAGE` / 特殊傷害 / down event / life loss。
- `REMOVE_CHEER` / `REMOVE_STAGE_CHEER`。
- 公開 API、資料庫 migration、seed data。

## 行數變化

- `MatchEffectService.java`：`9,058` 行 -> `8,789` 行，淨減 `269` 行。
- 新增 `MatchMoveZoneEffectExecutionService.java`：`333` 行。
- 新增 `MatchMoveZoneEffectExecutionServiceTest.java`：`252` 行。
- `MatchActionServiceIntegrationTest.java`：`32,300` 行 -> `32,302` 行，補上 Bloom deferred confirm 流程。

## 行為保護

本批保留既有語意，並額外鎖住兩個解析細節：

- `コラボエフェクト` 只是 header，不應在文案實際寫 `バックポジションに移動` 時被誤判成目的地 `COLLAB`。
- Bloom triggered effect 目前是 deferred confirm 流程；integration test 需先解析 `TRIGGER_EFFECT_CONFIRM` 後，才驗證 MOVE_ZONE 實際執行。

## 驗證結果

已執行：

```bash
./mvnw -q -Dtest=MatchMoveZoneEffectExecutionServiceTest test
./mvnw -q -DskipTests compile
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportMoveZoneShouldMoveOpponentCenterToBackAndRest test
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomShouldTriggerMoveZoneEffectFromPassiveText test
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#collabHsd01009ShouldSendCheerAndMoveSelfToBackWhenDiceIsOne test
```

結果：

- Unit test 通過。
- Compile 通過。
- Bloom / Collab focused integration 通過。
- Support focused integration 單獨重跑通過。
- 三支 integration 並行時曾遇到測試前置資料不穩定，單獨重跑後通過；後續整合測試建議避免這類 Testcontainers 情境並行執行。

## 下一步建議

下一批建議先處理 `REMOVE_CHEER` / `REMOVE_STAGE_CHEER`，因為它們仍屬低中風險 movement 類效果，範圍比 `DAMAGE` 集中。`DAMAGE` 仍需暫緩，等 Cheer removal 類效果收斂後再以更完整 focused tests 拆分。
