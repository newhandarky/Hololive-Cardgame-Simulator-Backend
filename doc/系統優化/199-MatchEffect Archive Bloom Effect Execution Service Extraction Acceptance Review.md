# 199-MatchEffect Archive Bloom Effect Execution Service Extraction Acceptance Review

日期：2026-05-25
狀態：已完成

## 背景

本批延續 production code 主線拆分，在 `SUMMON_TO_STAGE` execution service 收斂後，處理同樣和 stage / stack 狀態相關的 `BLOOM_FROM_ARCHIVE`。

選擇 `BLOOM_FROM_ARCHIVE` 的原因：

- 它仍在 `MatchEffectService` 中持有 Archive Bloom 的目標查詢、候選查詢、zone update、Holomem 更新與 stack 記錄。
- 風險高於單純 zone movement，但責任邊界集中，仍低於 `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
- 可用 focused unit test 鎖住成功 Bloom、同回合已 Bloom no-op、找不到 Archive Bloom 卡與移動失敗 no-op。

## 本批完成內容

- 新增 `MatchArchiveBloomEffectExecutionService` package-private component。
- 從 `MatchEffectService` 搬出 `BLOOM_FROM_ARCHIVE` 執行流程：
  - current turn 解析。
  - `SearchCriteria` 解析與目標 Holomem 查詢。
  - 同回合已 Bloom 目標過濾。
  - Archive Bloom 卡候選查詢。
  - ARCHIVE -> STAGE zone update。
  - `match_holomems` card / level / last bloom turn 更新。
  - Holomem stack card 關聯建立。
  - applied / no-op summary payload 組裝。
- `MatchEffectService` 的 support / gift dispatch 改為委派新 service。
- `MatchBloomEffectDispatcher`、`MatchCollabEffectDispatcher` 改為直接委派新 service。
- 新增 `MatchArchiveBloomEffectExecutionServiceTest`。

## 責任邊界

`MatchArchiveBloomEffectExecutionService` 負責：

- `BLOOM_FROM_ARCHIVE` 的場上目標查詢、Archive Bloom 卡查詢與執行 SQL。
- 目標同回合已 Bloom 過的 no-op 判斷。
- Bloom level normalization、rank 判斷與 stack card 記錄。
- 保留既有 no-op reason 與 summary payload 格式。

`MatchEffectService` 保留：

- support / gift 高階 dispatch。
- Bloom / Collab orchestration 的入口。
- 其他尚未拆出的 effect family execution。

本批未處理：

- `RETURN_CHEER_TO_DECK_BOTTOM`。
- `DAMAGE` / `DOWN` / `HEAL` / `MOVE_ZONE`。
- 公開 API、資料庫 migration 或 seed data。

## 行數變化

- `MatchEffectService.java`：`9,675` 行 -> `9,489` 行，減少 `186` 行。
- 新增 `MatchArchiveBloomEffectExecutionService.java`：`321` 行。
- 新增 `MatchArchiveBloomEffectExecutionServiceTest.java`：`235` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchArchiveBloomEffectExecutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchBloomEffectIntegrationTest#bloomShouldTriggerBloomFromArchiveEffectFromStructuredDefinition test`

補充：

- integration test 需要 Testcontainers / PostgreSQL。
- 沙盒內首次執行 integration test 因 Docker/PostgreSQL socket `Operation not permitted` 失敗；提高權限後可執行並通過。
- `git diff --check` 於 commit 前執行。

## 判讀

- `MatchEffectService` 不再持有 Archive Bloom 的目標查詢、Archive candidate 查詢、ARCHIVE -> STAGE update、Holomem 更新與 stack 記錄。
- Bloom / Collab dispatcher 已直接依賴具體 Archive Bloom execution service，主 service 的 execution bridge 再縮小。
- 本批只搬移既有行為，沒有修改公開 API 或資料庫 schema。

## 下一步建議

下一批建議處理：

1. `RETURN_CHEER_TO_DECK_BOTTOM`：低中風險 zone movement effect，可延續目前 production god class 拆分主線。
2. `DOWN_NO_LIFE` / `DOWN_EXTRA_LIFE`：收益高但牽涉 down event、life 與 gift follow-up，建議等 cheer return 類效果完成後再規劃。
3. 繼續暫緩 `DAMAGE` / `HEAL` / `MOVE_ZONE`，避免過早觸碰核心戰鬥規則。
