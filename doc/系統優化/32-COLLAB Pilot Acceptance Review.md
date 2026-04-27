# COLLAB Pilot Acceptance Review

更新日期：2026-04-27
定位：`COLLAB` pilot 驗收 review
用途：對照 `31-COLLAB Pilot Acceptance.md`，確認 COLLAB 是否已達到第三條可複製 use case 的標準，以及哪些技術債允許暫留。

---

## 一、結論

`COLLAB` pilot 目前可視為已通過第三條 use case 的階段性驗收。

理由：

1. contract 包已完成。
2. 舊 `MatchActionService.moveStageHolomem(... targetZone=COLLAB)` 已退化成 adapter / facade。
3. 一般 `MOVE_STAGE_HOLOMEM` 與 `COLLAB` 核心責任已拆開。
4. validation、state mutation、effect follow-up、event / trigger dispatch 已有明確分層。
5. focused unit / integration tests 已覆蓋主要 contract，並補上 direct application `resolveState` DB mutation 測試缺口。

---

## 二、架構條件對照

### 1. 新流程已存在

已完成：

- `CollabAction`
- `CollabActionValidator`
- `CollabActionResolver`
- `CollabApplicationService`
- `CollabLegacyResolutionBridge`
- `CollabEffectResolutionService`
- `CollabEventFactory`
- `CollabTriggerDispatcher`
- `CollabSystemStateFinalizationHandler`
- `CollabEffectPreviewHandler`
- `CollabGiftPreviewHandler`
- `CollabTriggerConfirmHandler`

判定：通過。

### 2. 舊入口已橋接

`MatchActionService.moveStageHolomem(...)` 目前保留：

- request DTO 轉 action
- target zone routing
- 非 COLLAB movement legacy branch
- 呼叫 `CollabApplicationService.validate(...)`
- 呼叫 `CollabApplicationService.resolveState(...)`
- 呼叫 `CollabEffectResolutionService.resolve(...)`
- 呼叫 `CollabApplicationService.dispatchResolvedEvents(...)`
- legacy action payload / response compatibility
- 結束後的既有勝負檢查與 life loss send cheer interaction enqueue

已移出：

- COLLAB 主要 legality validation
- BACK -> COLLAB mutation
- top deck -> holopower mutation
- collab / gift preview 建立
- confirm pending interaction 建立
- COLLAB event 建立
- COLLAB trigger dispatch 分類

判定：通過。

### 3. 責任已分層

目前責任邊界：

- `CollabLegacyResolutionBridge`：載入 legacy DB context。
- `CollabActionValidator`：判斷 action 是否可執行。
- `CollabActionResolver`：執行 state mutation。
- `CollabEffectResolutionService`：處理 COLLAB 後 effect preview / gift preview / confirm follow-up。
- `CollabEventFactory`：從 action + resolution result + effect resolution 建立事件。
- `CollabTriggerDispatcher`：依事件分派 handler，固定 sync/deferred 語意。
- `MatchActionService.moveStageHolomem(...)`：adapter 與相容 payload glue。

判定：通過。

---

## 三、舊入口 allow / block 清單對照

### 允許保留項

目前仍留在舊入口，且符合 `31` 文件允許範圍：

- request DTO 轉 action
- target zone routing
- 非 COLLAB movement legacy branch
- 呼叫 application service
- legacy response payload 組裝
- 暫時的 action log compatibility glue
- 暫時的 post-effect finish/life-loss checks
- snapshot / API 相容 glue

判定：通過。

### 不允許保留項

以下項目已不再位於舊入口主流程：

- COLLAB 主要 validation 混在 `MatchActionService.moveStageHolomem(...)`
- BACK -> COLLAB mutation 混在 `MatchActionService.moveStageHolomem(...)`
- top deck -> holopower mutation 混在 `MatchActionService.moveStageHolomem(...)`
- collab / gift preview 混在主方法中
- confirm pending interaction 建立混在主方法中
- event 建立直接散在舊方法中
- trigger dispatch 分類直接散在舊方法中

判定：通過。

---

## 四、不可退步契約對照

### 1. Board state

由 `CollabActionValidator` 與 `CollabActionResolver` 負責：

- source Holomem 從 BACK 移到 COLLAB
- source 不留在 BACK
- COLLAB zone 不可出現多張
- rested source 不可 collab
- 本回合不可重複 collab

`CollabApplicationServiceIntegrationTest` 已驗證 direct application resolve path 的 DB mutation。

判定：通過。

### 2. Holopower

由 `CollabActionResolver` 負責：

- 牌庫頂 1 張卡移至 HOLOPOWER
- 移入 HOLOPOWER 後轉為 face-up
- `CollabResolutionResult` 回傳 `holopowerCardInstanceId`

`CollabApplicationServiceIntegrationTest` 已直接驗證 top deck card -> HOLOPOWER mutation 與 result id 一致。

判定：通過。

### 3. Rule rejection

由 `CollabActionValidator` 負責：

- 不是目前玩家
- phase 不允許
- source 不存在
- source 不在 BACK
- source rested
- target zone 不是 COLLAB
- COLLAB zone 已被占用
- 本回合已 COLLAB
- 有 blocking pending interaction
- stage action locked
- stale / duplicate action

`CollabActionValidatorTest` 覆蓋 validator 代表性 allow / reject；既有 `MatchActionServiceIntegrationTest` 保留 legacy API rejection smoke。

判定：通過。

### 4. Effect preview / confirm

由 `CollabEffectResolutionService` 負責：

- collab triggered effect preview
- own Holomem gift triggered preview
- `COLLAB_EFFECT` confirm pending decision
- collab section 與 gift section 同時出現在 confirm context
- trigger resolution order payload

目前 pending decision 仍寫入既有 `match_pending_decisions` table，這符合 `31` 文件允許的 legacy glue 暫留範圍。

判定：通過，允許暫留。

### 5. Action log / event

目前：

- action log 仍由 `MatchActionService.moveStageHolomem(...)` 寫入，維持既有前端 payload。
- event 由 `CollabEventFactory` 根據 `CollabResolutionResult` 與 `CollabEffectResolution` 產生，不依賴 action log payload。
- trigger dispatch 由 `CollabApplicationService.dispatchResolvedEvents(...)` 進入 `CollabTriggerDispatcher`。

判定：通過。

---

## 五、驗證結果

已通過：

- `git diff --check`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=CollabActionValidatorTest,CollabActionResolverTest,CollabApplicationServiceTest,CollabEffectResolutionServiceTest,CollabEventFactoryTest test`
- `./mvnw -q -Dtest=CollabApplicationServiceIntegrationTest test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#collabShouldIncludeResolutionOrderWithPriority+collabShouldCreateGiftConfirmWhenGiftTriggeredByOwnHolomemCollab test`

補充：

- `CollabApplicationServiceIntegrationTest` 與 `MatchActionServiceIntegrationTest` 需要 Testcontainers / Docker socket 權限。
- sandbox 內直接執行 integration test 會因 Docker socket / datasource 連線被拒而失敗；升權後通過。

---

## 六、測試缺口檢查

`31-COLLAB Pilot Acceptance.md` 要求的 focused integration coverage 對照：

1. direct application `resolveState` DB mutation
   - 已補 `CollabApplicationServiceIntegrationTest`
2. existing legacy API rejection path 不退步
   - 由既有 `MatchActionServiceIntegrationTest` COLLAB rejection cases 保護
3. collab effect confirm path
   - 由 `collabShouldIncludeResolutionOrderWithPriority` 等既有 smoke 保護
4. collab gift confirm path
   - 由 `collabShouldCreateGiftConfirmWhenGiftTriggeredByOwnHolomemCollab` 保護

判定：目前沒有 blocker。

---

## 七、可接受的暫留技術債

以下項目允許暫留到下一輪：

1. package 尚未搬到正式 `actions/validators/resolvers/triggers` 目錄。
2. `CollabLegacyResolutionBridge` 仍直接用 repository / `JdbcTemplate` 載入 context。
3. `CollabEffectResolutionService` 仍直接寫 legacy `match_pending_decisions`。
4. idempotency key 尚未完整落到專用 persistence。
5. `MatchActionService.moveStageHolomem(...)` 仍負責 legacy action log payload 與既有勝負檢查 glue。
6. COLLAB trigger handlers 仍是 thin handler，主要固定事件分類與 sync/deferred 邊界。

以下項目已不再存在：

1. 舊 COLLAB 主方法內混寫主要 validation。
2. 舊 COLLAB 主方法內混寫主要 state mutation。
3. resolver 直接建立 pending interaction。
4. event factory 依賴 action log payload。
5. trigger dispatch 分類散在舊方法中。

---

## 八、完成後可回答的問題

1. COLLAB 現在由哪個 validator 負責合法性？
   - `CollabActionValidator`

2. COLLAB 現在由哪個 resolver 負責 state mutation？
   - `CollabActionResolver`

3. COLLAB effect / gift preview 由哪個 service 負責？
   - `CollabEffectResolutionService`

4. COLLAB 會產生哪些 events？
   - `COLLAB_REQUEST_ACCEPTED`
   - `COLLAB_RESOLVED`
   - `COLLAB_EFFECT_PREVIEW_CREATED`
   - `COLLAB_GIFT_PREVIEW_CREATED`
   - `COLLAB_TRIGGER_CONFIRM_REQUIRED`

5. 哪些 trigger 是 sync，哪些是 deferred？
   - sync：request accepted、resolved、effect preview created、gift preview created
   - deferred：trigger confirm required

6. action log 與 event 的責任差異是什麼？
   - action log 是外部相容與除錯紀錄。
   - event 是內部 trigger orchestration 事實。

7. `moveStageHolomem(... targetZone=CENTER)` 與 `targetZone=COLLAB` 的責任邊界在哪？
   - `CENTER` 仍走非 COLLAB movement legacy branch。
   - `COLLAB` 先 routing 到 `executeCollabAction(...)`，再委派 COLLAB application / effect / event path。

---

## 九、下一步建議

`COLLAB` pilot 已可作為第三條 use case 模板。

下一條 use case 不建議直接進 `ATTACK`。建議順序：

1. 優先評估 `ATTACH_CHEER`
   - 可驗證資源移動型 action 模板
   - 範圍低於 `PLAY_CARD` 與 `ATTACK`
   - 有助於補足 stage action 以外的 resource operation 範例
2. 次選 `PLAY_CARD`
   - 可驗證 MEMBER / SUPPORT 的分支型 action
   - 但規則面較寬，應等 `ATTACH_CHEER` 或共用 action shell 更穩後再進
3. 暫不建議 `ATTACK`
   - battle chain、damage/down/life loss 仍是最高風險區
   - 應等 BLOOM + COLLAB + 至少一條資源操作 use case 穩定後再進

建議下一份文件：

- `ATTACH_CHEER 第四條 Pilot 啟動規劃`
