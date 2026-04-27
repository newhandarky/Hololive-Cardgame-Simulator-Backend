# BLOOM Pilot Acceptance Review

更新日期：2026-04-27
定位：`BLOOM` pilot 驗收 review
用途：對照 `22-BLOOM Pilot Acceptance.md`，確認 BLOOM 是否已達到第二條可複製 use case 的標準，以及哪些技術債允許暫留。

---

## 一、結論

`BLOOM` pilot 目前可視為已通過第二條 use case 的階段性驗收。

理由：

1. contract 包已完成。
2. 舊 `MatchActionService.bloom(...)` 已退化成 adapter / facade。
3. validation、state mutation、effect follow-up、event / trigger dispatch 已有明確分層。
4. focused unit / integration tests 已覆蓋主要 contract。
5. legacy glue 邊界可清楚說明，且不再混在主要 validation / mutation 中。

---

## 二、架構條件對照

### 1. 新流程已存在

已完成：

- `BloomAction`
- `BloomActionValidator`
- `BloomActionResolver`
- `BloomApplicationService`
- `BloomLegacyResolutionBridge`
- `BloomEventFactory`
- `BloomTriggerDispatcher`
- `BloomEffectResolutionService`
- `BloomEffectPreviewHandler`
- `BloomTriggerConfirmHandler`
- `BloomSystemStateFinalizationHandler`

判定：通過。

### 2. 舊入口已橋接

`MatchActionService.bloom(...)` 目前保留：

- request DTO 轉 action
- 呼叫 `BloomApplicationService.validate(...)`
- 呼叫 `BloomApplicationService.resolveState(...)`
- 呼叫 `BloomEffectResolutionService.resolveAfterBloom(...)`
- legacy action payload / response compatibility
- 結束後的既有勝負檢查與 life loss send cheer interaction enqueue

已移出：

- source card / target legality validation
- BLOOM level / same-name / once-per-turn / damage carry validation
- hand -> stage mutation
- stack append
- target holomem top card mutation
- effect preview / confirm pending 建立
- BLOOM event factory / trigger dispatch path

判定：通過。

### 3. 責任已分層

目前責任邊界：

- `BloomLegacyResolutionBridge`：載入 legacy DB context。
- `BloomActionValidator`：判斷 action 是否可執行。
- `BloomActionResolver`：執行 state mutation。
- `BloomEffectResolutionService`：處理 BLOOM 後 effect preview / confirm follow-up。
- `BloomEventFactory`：從 action + resolution result 建立事件。
- `BloomTriggerDispatcher`：依事件分派 handler，固定 sync/deferred 語意。
- `MatchActionService.bloom(...)`：adapter 與相容 payload glue。

判定：通過。

---

## 三、不可退步契約對照

### 1. Board state

由 `BloomActionResolver` 負責：

- source card 從 hand 移到 stage
- stack append
- target holomem top card / card id / level / last bloom turn 更新
- extra bloom allowance consume

`BloomApplicationServiceIntegrationTest` 已驗證 direct application resolve path 的 DB mutation。

判定：通過。

### 2. Rule rejection

由 `BloomActionValidator` 負責：

- stale action
- duplicate action
- not current turn player
- non-MAIN phase
- pending interaction blocked
- source not in hand
- invalid source / target
- stage action locked
- target entered this turn
- target already bloomed without allowance
- same-name mismatch
- invalid level transition
- damage exceeds new hp

`BloomActionValidatorTest` 與既有 `MatchActionServiceIntegrationTest` focused bloom rejection cases 已覆蓋代表性路徑。

判定：通過。

### 3. Effect preview / confirm

由 `BloomEffectResolutionService` 負責：

- passive gift extra bloom allowance apply
- bloom triggered effect preview
- `onHolomemBloom(...)` hook summary
- `BLOOM_EFFECT` confirm pending decision
- trigger resolution order payload

目前 pending decision 仍寫入既有 `match_pending_decisions` table，這符合 `22` 文件允許的 legacy glue 暫留範圍。

判定：通過，允許暫留。

### 4. Action log / event

目前：

- action log 仍由 `MatchActionService.bloom(...)` 寫入，維持既有前端 payload。
- event 由 `BloomEventFactory` 根據 `BloomResolutionResult` 產生，不依賴 action log payload。
- trigger dispatch 由 `BloomApplicationService.dispatchResolvedEvents(...)` 進入 `BloomTriggerDispatcher`。

判定：通過。

---

## 四、驗證結果

已通過：

- `git diff --check`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=BloomActionValidatorTest,BloomActionResolverTest,BloomEventFactoryTest,BloomApplicationServiceTest,BloomEffectResolutionServiceTest test`
- `./mvnw -q -Dtest=BloomApplicationServiceIntegrationTest test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomShouldRejectSkippingLevelTransition+bloomShouldRejectTargetEnteredThisTurn test`

補充：

- `BloomApplicationServiceIntegrationTest` 與 `MatchActionServiceIntegrationTest` 需要 Testcontainers / Docker socket 權限。
- 曾觀察到既有 `bloomShouldUpgradeHolomemAndKeepStack` 受 legacy effect confirm flow 影響而失敗；該狀況在 BLOOM adapter diff 外也可重現，不列為本 pilot blocker。

---

## 五、可接受的暫留技術債

以下項目允許暫留到下一輪：

1. package 尚未搬到正式 `actions/validators/resolvers/triggers` 目錄。
2. `BloomLegacyResolutionBridge` 仍直接用 repository / `JdbcTemplate` 載入 context。
3. `BloomEffectResolutionService` 仍直接寫 legacy `match_pending_decisions`。
4. idempotency key 尚未完整落到專用 persistence。
5. `MatchActionService.bloom(...)` 仍負責 legacy action log payload 與既有勝負檢查 glue。

以下項目已不再存在：

1. 舊 bloom 主方法內混寫主要 validation。
2. 舊 bloom 主方法內混寫主要 state mutation。
3. resolver 直接建立 pending interaction。
4. event factory 依賴 action log payload。

---

## 六、完成後可回答的問題

1. BLOOM 現在由哪個 validator 負責合法性？
   - `BloomActionValidator`

2. BLOOM 現在由哪個 resolver 負責 state mutation？
   - `BloomActionResolver`

3. BLOOM 會產生哪些 events？
   - `BLOOM_REQUEST_ACCEPTED`
   - `BLOOM_RESOLVED`
   - `BLOOM_EFFECT_PREVIEW_CREATED`
   - `BLOOM_TRIGGER_CONFIRM_REQUIRED`

4. 哪些 trigger 是 sync，哪些是 deferred？
   - sync：request accepted、resolved、effect preview created
   - deferred：trigger confirm required

5. action log 與 event 的責任差異是什麼？
   - action log 是外部相容與除錯紀錄。
   - event 是內部 trigger orchestration 事實。

6. effect preview / confirm 的暫時 legacy glue 邊界在哪？
   - 集中在 `BloomEffectResolutionService`，不再位於 `MatchActionService.bloom(...)` 主流程。

---

## 七、下一步建議

`BLOOM` pilot 已可作為第二條 use case 模板。

下一條 use case 不建議直接進 `ATTACK`。建議順序：

1. 優先評估 `COLLAB`
   - 和 BLOOM 一樣有 effect preview / confirm
   - 範圍比 `PLAY_CARD` 小
   - 可驗證 card action 模板是否能處理另一個 stage action
2. 次選 `ATTACH_CHEER`
   - 可驗證費用/資源移動型 action
   - 風險低於 attack core
3. 暫不建議 `PLAY_CARD`
   - 規則分支包含 MEMBER / SUPPORT，容易把第三條 pilot 拉太寬
4. 暫不建議 `ATTACK`
   - battle chain、damage/down/life loss 仍是最高風險區

建議下一份文件：

- `COLLAB Pilot 啟動規劃`

或如果想先補通用性：

- `Card Action Pilot 共性整理`
