# AAA-237 MatchAction Selected Card Validation Helper Extraction Acceptance Review

日期：2026-05-27

## Summary

本批延續 AAA-233 到 AAA-236 的 MatchAction decision lifecycle 主線，先抽出共用 `SelectedCardValidationService`，集中 selected-card sanitize、min/max selection 與 candidate validation。

此次只搬移既有 validation 行為，不改 REST / WebSocket public API、不新增 DB migration，也不改 `SEND_CHEER`、`TRIGGER_EFFECT_CONFIRM`、`CARD_SELECTION` 的 resolution 語意。

## Scope

- `SelectedCardValidationService`
  - 新增 `sanitize(...)`：過濾 `null` / `<=0`，並保留首次出現順序去重。
  - 新增 `validate(...)`：套用 sanitize 後檢查 min / max selection，並驗證 selected card 是否存在於 candidate list。
  - 保留既有錯誤訊息：
    - `選擇卡片數量不足，至少需要 N 張`
    - `選擇卡片數量超過上限，最多只能選 N 張`
    - `選擇的卡片不在候選清單內: id`

- `MatchDecisionResolutionService`
  - `SEND_CHEER` 改用 `SelectedCardValidationService.validate(...)`。
  - `LOOK_TOP_DECK` 改用共用 sanitize / validation。
  - `REORDER_DECK_BOTTOM` 改用共用 sanitize。
  - 移除原本 private `sanitizeSelectedCardInstanceIds(...)` 與 `validateSelectedCardsWithinCandidates(...)`。

- `MatchActionService`
  - `TRIGGER_EFFECT_CONFIRM` confirmed branch 改用 `SelectedCardValidationService`。
  - `CARD_SELECTION` support resolution 改用 `SelectedCardValidationService.validate(...)`。
  - 移除原本 private selected-card sanitize / candidate validation helper。

- Tests
  - 新增 `SelectedCardValidationServiceTest`，覆蓋 sanitize order / dedupe、min、max、candidate validation。
  - 更新 `MatchDecisionResolutionServiceTest` constructor wiring。

## File Size

- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`5,196` 行。
- `src/main/java/com/hololive/cardgame/service/MatchDecisionResolutionService.java`：`509` 行。
- `src/main/java/com/hololive/cardgame/service/SelectedCardValidationService.java`：`51` 行。
- `src/test/java/com/hololive/cardgame/service/SelectedCardValidationServiceTest.java`：`46` 行。
- `src/test/java/com/hololive/cardgame/service/MatchDecisionResolutionServiceTest.java`：`255` 行。

## Verification

TDD 紅燈：

```bash
./mvnw -q -Dtest=SelectedCardValidationServiceTest test
```

結果：

- 第一次執行因 `SelectedCardValidationService` 尚未存在而編譯失敗，符合預期紅燈。

已執行：

```bash
./mvnw -q -Dtest=SelectedCardValidationServiceTest test
./mvnw -q -Dtest=MatchDecisionResolutionServiceTest test
./mvnw -q -Dtest=SelectedCardValidationServiceTest,MatchDecisionResolutionServiceTest,PendingDecisionCreationServiceTest,PendingDecisionStoreTest,PendingDecisionReaderTest test
./mvnw -q -DskipTests compile
./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#resolveDecisionShouldAttachCheerForSendCheerInteraction' test
./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#resolveDecisionShouldApplySelectedCardAndMarkDecisionResolved' test
git diff --check
```

結果：

- `SelectedCardValidationServiceTest`：通過。
- `MatchDecisionResolutionServiceTest`：通過。
- focused unit 組合：通過。
- `compile`：通過。
- `MatchActionServiceIntegrationTest#resolveDecisionShouldAttachCheerForSendCheerInteraction`：提權使用 Testcontainers PostgreSQL 後通過。
- `MatchActionServiceIntegrationTest#resolveDecisionShouldApplySelectedCardAndMarkDecisionResolved`：
  - 第一次提權執行時 Docker 可連線，但 Testcontainers Ryuk 啟動逾時，fallback 到本機 `localhost:5432` 後 PostgreSQL connection refused，測試未進入實際 assertion。
  - 使用相同 Maven command 再次提權重跑後，Testcontainers PostgreSQL 啟動成功，測試通過。
- `git diff --check`：通過。

## Acceptance

- `SEND_CHEER`、`LOOK_TOP_DECK`、`REORDER_DECK_BOTTOM`、`TRIGGER_EFFECT_CONFIRM`、`CARD_SELECTION` 的 selected-card sanitize / validation 已共用同一個 helper。
- `MatchActionService` 與 `MatchDecisionResolutionService` 不再各自保留重複的 selected-card private helper。
- 後續搬移 `TRIGGER_EFFECT_CONFIRM` 或 `CARD_SELECTION` 前，selection validation 已先收斂。

## Next Step

下一批建議先處理 `TURN_START` 搬移前的 lifecycle 邊界：

- 低中風險：抽出 return-collab lifecycle helper，讓 rested collab 回後台的 stage mutation 不直接塞進 decision resolution service。
- 中風險：再將 `TURN_START` decision resolution 搬入 `MatchDecisionResolutionService`，保留 pending resolved、return collab、phase transition 與 action log 行為。
