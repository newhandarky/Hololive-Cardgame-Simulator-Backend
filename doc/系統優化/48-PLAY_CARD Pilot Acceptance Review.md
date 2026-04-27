# PLAY_CARD Pilot Acceptance Review

更新日期：2026-04-27
定位：`PLAY_CARD` pilot 驗收 review
用途：對照 `47-PLAY_CARD Pilot Acceptance.md`，確認 PLAY_CARD 是否已達到第五條可複製 use case 的標準，以及哪些技術債允許暫留。

---

## 一、結論

`PLAY_CARD` pilot 目前可視為已通過第五條 use case 的階段性驗收。

理由：

1. contract 包已完成。
2. 舊 `MatchActionService.playToStage(...)` 已退化成 adapter / facade。
3. source card validation、stage placement mutation、effect follow-up、event / trigger dispatch 已明確分層。
4. `match_cards` / `match_holomems` / `match_holomem_stack_cards` 一致性已有 focused integration tests 保護。
5. RESET opening setup 與 MAIN placement 的差異已有 validator、resolver、effect follow-up 與 event tests 保護。
6. support card 與 attack cost payment 沒有混入本 pilot。

---

## 二、架構條件對照

### 1. 新流程已存在

已完成：

- `PlayCardAction`
- `PlayCardActionValidator`
- `PlayCardActionResolver`
- `PlayCardApplicationService`
- `PlayCardLegacyResolutionBridge`
- `PlayCardEffectResolutionService`
- `PlayCardEventFactory`
- `PlayCardTriggerDispatcher`
- `PlayCardSystemStateFinalizationHandler`
- `PlayCardEnterHookHandler`
- `PlayCardGiftPreviewHandler`
- `PlayCardGiftConfirmHandler`

判定：通過。

### 2. 舊入口已橋接

`MatchActionService.playToStage(...)` 目前保留：

- request DTO 轉 action
- 呼叫 `PlayCardApplicationService.validate(...)`
- 呼叫 `PlayCardApplicationService.resolveState(...)`
- 呼叫 `PlayCardEffectResolutionService.resolve(...)`
- 呼叫 `PlayCardApplicationService.dispatchResolvedEvents(...)`
- `matches.current_phase` touch 行為
- legacy action log payload
- legacy action type 相容：
  - `OPENING_SET_CENTER`
  - `OPENING_SET_BACK`
  - `PLAY_TO_STAGE`

已移出：

- source card 主要 validation
- target zone / level / opening setup 主要 validation
- `match_cards` zone mutation
- `match_holomems` insert
- `match_holomem_stack_cards` insert
- enter hook / Gift preview / confirm pending decision 建立
- PLAY_CARD event 建立
- PLAY_CARD trigger dispatch 分類

判定：通過。

### 3. 責任已分層

目前責任邊界：

- `PlayCardLegacyResolutionBridge`：載入 legacy DB context。
- `PlayCardActionValidator`：判斷 action 是否可執行。
- `PlayCardActionResolver`：執行 hand member placement state mutation。
- `PlayCardEffectResolutionService`：處理 enter hook、Gift preview、Gift confirm pending decision。
- `PlayCardEventFactory`：從 action + resolution result + effect resolution 建立事件。
- `PlayCardTriggerDispatcher`：依事件分派 handler，固定 sync / deferred 語意。
- `MatchActionService.playToStage(...)`：adapter 與相容 payload glue。

判定：通過。

---

## 三、舊入口 allow / block 清單對照

### 允許保留項

目前仍留在舊入口，且符合 `47` 文件允許範圍：

- request DTO 轉 action
- 呼叫 application service
- legacy response payload 組裝
- 暫時的 action log compatibility glue
- snapshot / API 相容 glue
- `matches.current_phase` touch 行為
- legacy action type 相容

判定：通過。

### 不允許保留項

以下項目已不再位於舊入口主流程：

- source card 主要 validation 混在 `MatchActionService.playToStage(...)`
- target zone / level / opening setup 主要 validation 混在 `MatchActionService.playToStage(...)`
- `match_cards` zone mutation 混在 `MatchActionService.playToStage(...)`
- `match_holomems` insert 混在 `MatchActionService.playToStage(...)`
- `match_holomem_stack_cards` insert 混在 `MatchActionService.playToStage(...)`
- enter hook / Gift follow-up 建立混在主方法中
- event 建立直接散在舊方法中
- trigger dispatch 分類直接散在舊方法中

判定：通過。

---

## 四、不可退步契約對照

### 1. Board state

由 `PlayCardActionValidator` 與 `PlayCardActionResolver` 負責：

- source card 從 `HAND` 移到 `STAGE`
- source card 不留在 `HAND`
- `match_holomems` 可看見新 Holomem
- `match_holomem_stack_cards` 有 base stack relation
- RESET opening setup 放置為 face-down
- MAIN placement 放置為 face-up
- BACK 最多 5 張

`PlayCardApplicationServiceIntegrationTest` 已驗證 MAIN back placement、RESET opening back placement 與 BACK full rejection。

判定：通過。

### 2. Rule rejection

由 `PlayCardActionValidator` 負責：

- stale action
- 不是目前玩家
- phase 不允許
- 有 blocking pending interaction
- source card 不存在
- source zone 不是 `HAND`
- source card 不是 MEMBER
- target zone 不是 `CENTER` / `BACK`
- RESET opening center 前只能 DEBUT -> CENTER
- RESET opening center 後只能 DEBUT / SPOT -> BACK
- MAIN 只能 DEBUT / SPOT -> BACK
- FIRST / SECOND / BUZZ 從手牌直上要拒絕並提示 BLOOM
- BACK 已滿
- action locked

`PlayCardActionValidatorTest` 覆蓋代表性 allow / reject；`PlayCardApplicationServiceIntegrationTest` 與既有 `MatchActionServiceIntegrationTest` 補 direct application 與 legacy adapter regression。

判定：通過。

### 3. Follow-up / pending decision

由 `PlayCardEffectResolutionService` 負責：

- RESET opening placement 不立即執行 enter hook
- RESET opening placement 不建立 Gift preview / pending confirm
- MAIN placement 呼叫 enter hook
- MAIN placement preview stage enter Gift triggers
- Gift triggers 存在時建立 `TRIGGER_EFFECT_CONFIRM`
- pending context 保留 source card payload、Gift triggers 與 turn number

`PlayCardEffectResolutionServiceTest` 已覆蓋 RESET deferred、MAIN hook / Gift preview、Gift pending decision 建立。

判定：通過。

### 4. Action log / event

目前：

- action log 仍由 `MatchActionService.playToStage(...)` 寫入，維持既有前端 payload 與 legacy action type。
- event 由 `PlayCardEventFactory` 根據 `PlayCardResolutionResult` 與 `PlayCardEffectResolution` 產生，不依賴 action log payload。
- trigger dispatch 由 `PlayCardApplicationService.dispatchResolvedEvents(...)` 進入 `PlayCardTriggerDispatcher`。

判定：通過。

---

## 五、驗證結果

已通過：

- `git diff --check`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=PlayCardActionValidatorTest,PlayCardActionResolverTest,PlayCardApplicationServiceTest,PlayCardEffectResolutionServiceTest,PlayCardEventFactoryTest test`
- `./mvnw -q -Dtest=PlayCardApplicationServiceIntegrationTest test`
- `./mvnw -q -Dtest=PlayCardApplicationServiceIntegrationTest,MatchActionServiceIntegrationTest#playToStageShouldKeepResetPhaseUntilOpeningSetupFinished+playToStageShouldRejectFirstSecondBuzzFromHand+playToStageShouldTriggerGiftWhenQualifiedHolomemEntersStage test`

補充：

- `PlayCardApplicationServiceIntegrationTest` 與 `MatchActionServiceIntegrationTest` 需要 Testcontainers / Docker socket 權限。
- sandbox 內直接執行 integration test 會因 Docker socket 被拒而失敗；升權後通過。

---

## 六、測試缺口檢查

`47-PLAY_CARD Pilot Acceptance.md` 要求的 focused integration coverage 對照：

1. direct application `resolveState` DB mutation
   - 已補 `PlayCardApplicationServiceIntegrationTest.resolveStateShouldMoveHandMemberToStageAndCreateHolomemStack`
2. RESET opening center placement
   - 由既有 `MatchActionServiceIntegrationTest#playToStageShouldKeepResetPhaseUntilOpeningSetupFinished` 保護
3. RESET opening back placement
   - 已補 `PlayCardApplicationServiceIntegrationTest.resolveStateShouldPlaceOpeningBackFaceDownAfterOpeningCenterExists`
4. MAIN back placement
   - 已補 `PlayCardApplicationServiceIntegrationTest.resolveStateShouldMoveHandMemberToStageAndCreateHolomemStack`
5. FIRST / SECOND / BUZZ rejection path
   - 由既有 `MatchActionServiceIntegrationTest#playToStageShouldRejectFirstSecondBuzzFromHand` 保護
6. BACK full rejection path
   - 已補 `PlayCardApplicationServiceIntegrationTest.validateShouldRejectBackPlacementWhenBackIsFull`
7. existing legacy API Gift stage enter path 不退步
   - 由既有 `MatchActionServiceIntegrationTest#playToStageShouldTriggerGiftWhenQualifiedHolomemEntersStage` 保護

判定：目前沒有 blocker。

---

## 七、可接受的暫留技術債

以下項目允許暫留到下一輪：

1. package 尚未搬到正式 `actions/validators/resolvers/triggers` 目錄。
2. `PlayCardLegacyResolutionBridge` 仍直接用 repository / `JdbcTemplate` 載入 context。
3. `PlayCardEffectResolutionService` 仍直接呼叫既有 enter hook / Gift trigger services。
4. idempotency key 尚未完整落到專用 persistence。
5. `MatchActionService.playToStage(...)` 仍負責 legacy action log payload。
6. PLAY_CARD trigger handlers 仍是 thin/no-op。

以下項目已不再存在：

1. 舊 PLAY_CARD 主方法內混寫主要 validation。
2. 舊 PLAY_CARD 主方法內混寫主要 state mutation。
3. 舊 PLAY_CARD 主方法內建立 Gift pending decision。
4. resolver 直接建立 action log。
5. event factory 依賴 action log payload。
6. trigger dispatch 分類散在舊方法中。

---

## 八、完成後可回答的問題

1. PLAY_CARD 現在由哪個 validator 負責合法性？
   - `PlayCardActionValidator`

2. PLAY_CARD 現在由哪個 resolver 負責 state mutation？
   - `PlayCardActionResolver`

3. PLAY_CARD enter hook / Gift follow-up 由哪個 service 負責？
   - `PlayCardEffectResolutionService`

4. PLAY_CARD 會產生哪些 events？
   - `PLAY_CARD_REQUEST_ACCEPTED`
   - `PLAY_CARD_RESOLVED`
   - `PLAY_CARD_ENTER_HOOK_RESOLVED`
   - `PLAY_CARD_GIFT_PREVIEW_CREATED`
   - `PLAY_CARD_GIFT_CONFIRM_REQUIRED`

5. 哪些 trigger 是 sync，哪些是 deferred？
   - sync：request accepted、resolved、enter hook resolved、Gift preview created
   - deferred：Gift confirm required

6. action log 與 event 的責任差異是什麼？
   - action log 是外部相容與除錯紀錄。
   - event 是內部 trigger orchestration 事實。

7. `PLAY_CARD` 與 support card 使用流程的責任邊界在哪？
   - `PLAY_CARD` 只處理 hand MEMBER placement 到 stage。
   - support card 使用流程仍屬 `PlaySupportActionRequest` / support effect pipeline，不在本 pilot 內。

8. attack cost payment 為什麼不在這條 pilot 內處理？
   - attack cost payment 是攻擊宣告內的 resource consumption，不是 hand member placement；應先拆 resource payment / cost validation，再碰 ATTACK 主流程。

---

## 九、下一步建議

下一步建議不要直接切 `ATTACK` 主流程，先進 attack cost 前置拆分。

優先順序：

1. attack cost 前置拆分
   - 抽出 cost validation / payment context / resource mutation。
   - 先讓 attack resource consumption 有和 PLAY_CARD / ATTACH_CHEER 類似的可測邊界。
2. support card use case
   - 可作為下一條 hand card action pilot，但會碰到更多效果解析與 pending decision。
3. `ATTACK`
   - 等 cost operation 和 resource mutation template 穩定後再進主流程。
