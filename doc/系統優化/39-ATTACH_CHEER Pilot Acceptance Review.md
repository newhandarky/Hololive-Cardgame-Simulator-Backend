# ATTACH_CHEER Pilot Acceptance Review

更新日期：2026-04-27
定位：`ATTACH_CHEER` pilot 驗收 review
用途：對照 `38-ATTACH_CHEER Pilot Acceptance.md`，確認 ATTACH_CHEER 是否已達到第四條可複製 use case 的標準，以及哪些技術債允許暫留。

---

## 一、結論

`ATTACH_CHEER` pilot 目前可視為已通過第四條 use case 的階段性驗收。

理由：

1. contract 包已完成。
2. 舊 `MatchActionService.attachCheer(...)` 已退化成 adapter / facade。
3. source cheer validation 與 attachment mutation 已拆開。
4. `match_cards` 與 `match_holomem_cheers` 的一致性已有 focused tests 保護。
5. event / trigger path 已建立 thin contract，沒有新增產品規則。
6. acceptance 要求的 focused integration rejection paths 已補上。

---

## 二、架構條件對照

### 1. 新流程已存在

已完成：

- `AttachCheerAction`
- `AttachCheerActionValidator`
- `AttachCheerActionResolver`
- `AttachCheerApplicationService`
- `AttachCheerLegacyResolutionBridge`
- `AttachCheerEventFactory`
- `AttachCheerTriggerDispatcher`
- `AttachCheerSystemStateFinalizationHandler`
- `AttachCheerTriggerHandler`
- `AttachCheerTriggerHandlingResult`
- `AttachCheerTriggerDispatchResult`

判定：通過。

### 2. 舊入口已橋接

`MatchActionService.attachCheer(...)` 目前保留：

- request DTO 解析
- request DTO 轉 `AttachCheerAction`
- 呼叫 `AttachCheerApplicationService.validate(...)`
- 呼叫 `AttachCheerApplicationService.resolveState(...)`
- 呼叫 `AttachCheerApplicationService.dispatchResolvedEvents(...)`
- `matches.current_phase = MAIN` touch 行為
- legacy `ATTACH_CHEER` action log payload

已移出：

- source Cheer card 主要 validation
- target Holomem 主要 validation
- `match_cards` zone mutation
- `match_holomem_cheers` insert
- ATTACH_CHEER event 建立
- ATTACH_CHEER trigger dispatch 分類

判定：通過。

### 3. 責任已分層

目前責任邊界：

- `AttachCheerLegacyResolutionBridge`：載入 legacy DB context。
- `AttachCheerActionValidator`：判斷 action 是否可執行。
- `AttachCheerActionResolver`：執行 state mutation。
- `AttachCheerEventFactory`：從 action + resolution result 建立事件。
- `AttachCheerTriggerDispatcher`：依事件分派 handler，固定 sync 語意。
- `MatchActionService.attachCheer(...)`：adapter 與相容 payload glue。

判定：通過。

---

## 三、舊入口 allow / block 清單對照

### 允許保留項

目前仍留在舊入口，且符合 `38` 文件允許範圍：

- request DTO 轉 action
- 呼叫 application service
- legacy response payload 組裝
- 暫時的 action log compatibility glue
- snapshot / API 相容 glue
- `matches.current_phase = MAIN` touch 行為

判定：通過。

### 不允許保留項

以下項目已不再位於舊入口主流程：

- source cheer card 主要 validation 混在 `MatchActionService.attachCheer(...)`
- target Holomem 主要 validation 混在 `MatchActionService.attachCheer(...)`
- `match_cards` zone mutation 混在 `MatchActionService.attachCheer(...)`
- `match_holomem_cheers` insert 混在 `MatchActionService.attachCheer(...)`
- event 建立直接散在舊方法中
- trigger dispatch 分類直接散在舊方法中

判定：通過。

---

## 四、不可退步契約對照

### 1. Board state

由 `AttachCheerActionValidator` 與 `AttachCheerActionResolver` 負責：

- source Cheer card 從 `HAND` 或 `CHEER_DECK` 移到 `STAGE`
- source Cheer card 不留在來源 zone
- `match_holomem_cheers` 寫入 attachment row
- attached cheer face-up

`AttachCheerApplicationServiceIntegrationTest` 已驗證 direct application resolve path 的 DB mutation。

判定：通過。

### 2. Rule rejection

由 `AttachCheerActionValidator` 負責：

- 不是目前玩家
- phase 不允許
- 有 blocking pending interaction
- source card 不存在
- source card 不屬於 actor
- source zone 不是 `HAND` 或 `CHEER_DECK`
- source card 不是 Cheer
- target Holomem 不存在
- target Holomem 不屬於 actor
- action locked
- stale / duplicate action

`AttachCheerActionValidatorTest` 覆蓋 validator 代表性 allow / reject；`AttachCheerApplicationServiceIntegrationTest` 補上 source zone、non-cheer source、missing target 的 application integration rejection path。

判定：通過。

### 3. Action log / event

目前：

- action log 仍由 `MatchActionService.attachCheer(...)` 寫入，維持既有前端 payload 相容。
- event 由 `AttachCheerEventFactory` 根據 `AttachCheerResolutionResult` 產生，不依賴 action log payload。
- trigger dispatch 由 `AttachCheerApplicationService.dispatchResolvedEvents(...)` 進入 `AttachCheerTriggerDispatcher`。

判定：通過。

---

## 五、驗證結果

已通過：

- `git diff --check`
- `./mvnw -q -Dtest=AttachCheerActionValidatorTest,AttachCheerActionResolverTest,AttachCheerApplicationServiceTest,AttachCheerEventFactoryTest test`
- `./mvnw -q -Dtest=AttachCheerApplicationServiceIntegrationTest test`

補充：

- `AttachCheerApplicationServiceIntegrationTest` 需要 Testcontainers / Docker socket 權限。
- sandbox 內直接執行 integration test 會因 Docker socket / datasource 連線被拒而失敗；升權後通過。

---

## 六、測試缺口檢查

`38-ATTACH_CHEER Pilot Acceptance.md` 要求的 focused integration coverage 對照：

1. direct application `resolveState` DB mutation
   - 已補 `AttachCheerApplicationServiceIntegrationTest.resolveStateShouldMoveCheerCardToStageAndInsertAttachment`
2. existing legacy API success path 不退步
   - 由既有 `MatchActionServiceIntegrationTest#playSupportRemoveCheerShouldDetachAndArchiveCheerCard` smoke 保護
3. source zone rejection path
   - 已補 `AttachCheerApplicationServiceIntegrationTest.validateShouldRejectCheerSourceOutsideAllowedZones`
4. non-cheer source rejection path
   - 已補 `AttachCheerApplicationServiceIntegrationTest.validateShouldRejectNonCheerSourceCard`
5. missing target rejection path
   - 已補 `AttachCheerApplicationServiceIntegrationTest.validateShouldRejectMissingTargetHolomem`

判定：目前沒有 blocker。

---

## 七、可接受的暫留技術債

以下項目允許暫留到下一輪：

1. package 尚未搬到正式 `actions/validators/resolvers/triggers` 目錄。
2. `AttachCheerLegacyResolutionBridge` 仍直接用 repository / `JdbcTemplate` 載入 context。
3. idempotency key 尚未完整落到專用 persistence。
4. `MatchActionService.attachCheer(...)` 仍負責 legacy action log payload。
5. ATTACH_CHEER trigger handlers 仍是 thin/no-op。

以下項目已不再存在：

1. 舊 ATTACH_CHEER 主方法內混寫主要 validation。
2. 舊 ATTACH_CHEER 主方法內混寫主要 state mutation。
3. resolver 直接建立 action log。
4. event factory 依賴 action log payload。
5. trigger dispatch 分類散在舊方法中。

---

## 八、完成後可回答的問題

1. ATTACH_CHEER 現在由哪個 validator 負責合法性？
   - `AttachCheerActionValidator`

2. ATTACH_CHEER 現在由哪個 resolver 負責 state mutation？
   - `AttachCheerActionResolver`

3. ATTACH_CHEER 會產生哪些 events？
   - `ATTACH_CHEER_REQUEST_ACCEPTED`
   - `ATTACH_CHEER_RESOLVED`

4. 哪些 trigger 是 sync，哪些是 deferred？
   - sync：request accepted、resolved
   - deferred：目前沒有

5. action log 與 event 的責任差異是什麼？
   - action log 是外部相容與除錯紀錄。
   - event 是內部 trigger orchestration 事實。

6. `SEND_CHEER` 與 `ATTACH_CHEER` 的責任邊界在哪？
   - `SEND_CHEER` 是 turn cheer lifecycle 與 pending decision flow。
   - `ATTACH_CHEER` 是將已指定 source Cheer 附加到目標 Holomem 的 resource attachment action。

7. attack cost payment 為什麼不在這條 pilot 內處理？
   - attack cost payment 是攻擊宣告內的 cost consumption，不是 standalone attachment action；應等 resource operation template 穩定後另切。

---

## 九、下一步建議

下一步建議進入下一條 use case 規劃，不直接切 `ATTACK` 主流程。

優先順序：

1. `PLAY_CARD`
   - 與 ATTACH_CHEER 一樣是清楚的 standalone action。
   - 可繼續驗證 Action / Validator / Resolver / Event / Trigger / Application 模板。
2. attack cost 前置拆分
   - 先拆 resource payment / cost validation，不直接重寫 attack core。
3. `ATTACK`
   - 等 resource operation pilot 更穩後再進主流程。
