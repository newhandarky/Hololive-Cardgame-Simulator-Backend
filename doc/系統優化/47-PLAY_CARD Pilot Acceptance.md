# PLAY_CARD Pilot Acceptance

更新日期：2026-04-27
定位：`PLAY_CARD` pilot 驗收標準
用途：定義 PLAY_CARD 何時算完成、哪些舊邏輯必須退出主流程、哪些契約不可退步，以及需要通過哪些驗證。

---

## 一、這份文件的角色

這份文件不是設計說明，而是驗收標準。

只有滿足本文件條件，`PLAY_CARD` pilot 才能被視為：

- 第五條可複製 use case
- hand member placement action 模板
- 後續 support card / attack cost 拆分前的穩定基準

---

## 二、Pilot 完成目標

`PLAY_CARD` pilot 完成後，系統應證明：

1. `Action / Validator / Resolver / EffectResolution / Event / Trigger / Application` 模板可套用到 hand member placement action。
2. 舊 `MatchActionService.playToStage(...)` 已退化成 adapter / facade。
3. source card validation、stage placement mutation、enter follow-up 已拆開。
4. `match_cards` / `match_holomems` / `match_holomem_stack_cards` 的一致性有 focused tests 保護。
5. RESET opening setup 與 MAIN placement 的差異有明確 contract。
6. 後續 `ATTACK` 不需要直接依賴舊 playToStage 主方法內的 validation / mutation / follow-up。

---

## 三、必須完成的架構條件

至少要有：

- `PlayCardAction`
- `PlayCardActionValidator`
- `PlayCardActionResolver`
- `PlayCardApplicationService`
- `PlayCardLegacyResolutionBridge`
- `PlayCardEffectResolutionService`
- `PlayCardEventFactory`
- `PlayCardTriggerDispatcher`
- 對應 thin trigger handler contract

第一版不要求：

- support card 使用流程
- 通用 hand card framework
- attack cost payment framework

---

## 四、舊入口 allow / block 清單

### 允許保留

- request DTO 轉 action
- 呼叫 application service
- legacy response payload 組裝
- 暫時的 action log compatibility glue
- snapshot / API 相容 glue
- `matches.current_phase` touch 行為，直到確認可移除
- legacy action type 相容：`OPENING_SET_CENTER` / `OPENING_SET_BACK` / `PLAY_TO_STAGE`

### 不允許保留

- source card 主要 validation 混在 `MatchActionService.playToStage(...)`
- target zone / level / opening setup 主要 validation 混在 `MatchActionService.playToStage(...)`
- `match_cards` zone mutation 混在 `MatchActionService.playToStage(...)`
- `match_holomems` insert 混在 `MatchActionService.playToStage(...)`
- `match_holomem_stack_cards` insert 混在 `MatchActionService.playToStage(...)`
- enter hook / Gift follow-up 建立混在主方法中
- event 建立直接散在舊方法中
- trigger dispatch 分類直接散在舊方法中

---

## 五、不可改壞的對外契約

### 1. Board state

PLAY_CARD 後必須維持：

- source card 從 `HAND` 移到 `STAGE`
- source card 不應留在 `HAND`
- `match_holomems` 可看見新 Holomem
- `match_holomem_stack_cards` 有 base stack relation
- RESET opening setup 放置為 face-down
- MAIN placement 放置為 face-up
- BACK 最多 5 張

### 2. Rule rejection

以下拒絕條件不可退步：

- 不是目前玩家
- phase 不允許
- 有 blocking pending interaction
- source card 不存在
- source card 不屬於 actor
- source zone 不是 `HAND`
- source card 不是 MEMBER
- target zone 不是 `CENTER` / `BACK`
- RESET opening center 前只能 DEBUT -> CENTER
- RESET opening center 後只能 DEBUT / SPOT -> BACK
- MAIN 只能 DEBUT / SPOT -> BACK
- FIRST / SECOND / BUZZ 從手牌直上要拒絕並提示 BLOOM
- BACK 已滿
- action locked

### 3. Follow-up / pending decision

MAIN placement 後必須維持：

- enter hook summary 可建立
- stage enter Gift preview 可建立
- 需要 confirm 時建立 `TRIGGER_EFFECT_CONFIRM`
- pending decision resolve 後 Gift action log / state 不退步

RESET opening setup 後必須維持：

- 不立即建立 Gift confirm
- follow-up deferred until live start

### 4. Action log / snapshot

前端不可觀察到：

- action log 顯示 PLAY_TO_STAGE 成功，但 board state 沒更新
- board state 更新，但 stack relation 缺失
- Gift pending decision 建立，但 source card / trigger context 不一致
- response 與 `/state` 的 stage Holomem 不一致

---

## 六、必跑驗證

### 1. Static / compile

- `git diff --check`
- `./mvnw -q -DskipTests compile`

### 2. Focused unit tests

至少覆蓋：

- validator allow / reject
- resolver result shape
- effect resolution summary / pending decision handoff
- event factory order
- application orchestration

建議：

- `PlayCardActionValidatorTest`
- `PlayCardActionResolverTest`
- `PlayCardEffectResolutionServiceTest`
- `PlayCardEventFactoryTest`
- `PlayCardApplicationServiceTest`

### 3. Focused integration tests

至少覆蓋：

- direct application `resolveState` DB mutation
- RESET opening center placement
- RESET opening back placement
- MAIN back placement
- FIRST / SECOND / BUZZ rejection path
- BACK full rejection path
- existing legacy API Gift stage enter path 不退步

目前可沿用或新增代表性測試：

- 新增 `PlayCardApplicationServiceIntegrationTest`
- 既有 `MatchActionServiceIntegrationTest` 中直接呼叫 `playToStage(...)` 的 smoke cases

---

## 七、完成後應能回答的問題

Pilot 完成時，團隊應能回答：

1. PLAY_CARD 現在由哪個 validator 負責合法性？
2. PLAY_CARD 現在由哪個 resolver 負責 state mutation？
3. PLAY_CARD enter hook / Gift follow-up 由哪個 service 負責？
4. PLAY_CARD 會產生哪些 events？
5. 哪些 trigger 是 sync，哪些是 deferred？
6. action log 與 event 的責任差異是什麼？
7. `PLAY_CARD` 與 support card 使用流程的責任邊界在哪？
8. attack cost payment 為什麼不在這條 pilot 內處理？

---

## 八、允許暫時存在的技術債

可暫時接受：

1. package 結構尚未最終搬到 `actions/validators/resolvers/triggers`。
2. `PlayCardLegacyResolutionBridge` 仍用既有 SQL / repository 載入 context。
3. `PlayCardEffectResolutionService` 仍直接呼叫既有 enter hook / Gift trigger services。
4. idempotency key 尚未完整落到專用 persistence。
5. `MatchActionService.playToStage(...)` 仍負責 legacy action log payload。
6. trigger handlers 仍是 thin/no-op。

不可接受：

1. PLAY_CARD validate / resolve 再度混回舊方法。
2. resolver 自己建立 action log。
3. resolver 自己建立 Gift pending decision。
4. event factory 依賴 action log payload。
5. 新流程沒有 focused unit/integration tests。
6. 這條 pilot 途中順手重寫 support card 或 attack core。

---

## 九、進入下一條 use case 的條件

只有 PLAY_CARD pilot 同時滿足：

- contract 包完成
- adapter path 完成
- follow-up path 完成
- focused tests 通過
- legacy glue 邊界可清楚說明

才建議進入下一條：

- support card use case
- 或 attack cost 的前置拆分

`ATTACK` 主流程仍應等 hand card / resource / cost operation 都有穩定模板後再進。
