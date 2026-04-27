# ATTACH_CHEER Pilot Acceptance

更新日期：2026-04-27
定位：`ATTACH_CHEER` pilot 驗收標準
用途：定義 ATTACH_CHEER 何時算完成、哪些舊邏輯必須退出主流程、哪些契約不可退步，以及需要通過哪些驗證。

---

## 一、這份文件的角色

這份文件不是設計說明，而是驗收標準。

只有滿足本文件條件，`ATTACH_CHEER` pilot 才能被視為：

- 第四條可複製 use case
- resource attachment action 模板的第一個基準
- 後續 attack cost / resource movement 重整的參考起點

---

## 二、Pilot 完成目標

`ATTACH_CHEER` pilot 完成後，系統應證明：

1. `Action / Validator / Resolver / Event / Trigger / Application` 模板可套用到 resource attachment action。
2. 舊 `MatchActionService.attachCheer(...)` 已退化成 adapter / facade。
3. source cheer validation 與 attachment mutation 已拆開。
4. `match_cards` 與 `match_holomem_cheers` 的一致性有 focused tests 保護。
5. 後續 `ATTACK` 不需要直接依賴舊 attach cheer 主方法內的 validation / mutation。

---

## 三、必須完成的架構條件

至少要有：

- `AttachCheerAction`
- `AttachCheerActionValidator`
- `AttachCheerActionResolver`
- `AttachCheerApplicationService`
- `AttachCheerLegacyResolutionBridge`
- `AttachCheerEventFactory`
- `AttachCheerTriggerDispatcher`
- 對應 thin trigger handler contract

第一版不要求：

- effect follow-up service
- pending interaction builder
- 通用 resource movement framework

---

## 四、舊入口 allow / block 清單

### 允許保留

- request DTO 轉 action
- 呼叫 application service
- legacy response payload 組裝
- 暫時的 action log compatibility glue
- snapshot / API 相容 glue
- `matches.current_phase = MAIN` touch 行為，直到確認可移除

### 不允許保留

- source cheer card 主要 validation 混在 `MatchActionService.attachCheer(...)`
- target Holomem 主要 validation 混在 `MatchActionService.attachCheer(...)`
- `match_cards` zone mutation 混在 `MatchActionService.attachCheer(...)`
- `match_holomem_cheers` insert 混在 `MatchActionService.attachCheer(...)`
- event 建立直接散在舊方法中
- trigger dispatch 分類直接散在舊方法中

---

## 五、不可改壞的對外契約

### 1. Board state

ATTACH_CHEER 後必須維持：

- source Cheer card 從 `HAND` 或 `CHEER_DECK` 移到 `STAGE`
- source Cheer card 不應留在來源 zone
- target Holomem 可在 `/state` 看見 attached cheer
- attached cheer face-up

### 2. Rule rejection

以下拒絕條件不可退步：

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

### 3. Action log / snapshot

前端不可觀察到：

- action log 顯示 ATTACH_CHEER 成功，但 board state 沒更新
- board state 更新，但 attachment row 缺失
- response 與 `/state` 的 attached cheer 不一致

---

## 六、必跑驗證

### 1. Static / compile

- `git diff --check`
- `./mvnw -q -DskipTests compile`

### 2. Focused unit tests

至少覆蓋：

- validator allow / reject
- resolver result shape
- event factory order
- application orchestration

建議：

- `AttachCheerActionValidatorTest`
- `AttachCheerActionResolverTest`
- `AttachCheerEventFactoryTest`
- `AttachCheerApplicationServiceTest`

### 3. Focused integration tests

至少覆蓋：

- direct application `resolveState` DB mutation
- existing legacy API success path 不退步
- source zone rejection path
- non-cheer source rejection path
- missing target rejection path

目前可沿用或新增代表性測試：

- 新增 `AttachCheerApplicationServiceIntegrationTest`
- 既有 `MatchActionServiceIntegrationTest` 中直接呼叫 `attachCheer(...)` 的 smoke cases

---

## 七、完成後應能回答的問題

Pilot 完成時，團隊應能回答：

1. ATTACH_CHEER 現在由哪個 validator 負責合法性？
2. ATTACH_CHEER 現在由哪個 resolver 負責 state mutation？
3. ATTACH_CHEER 會產生哪些 events？
4. 哪些 trigger 是 sync，哪些是 deferred？
5. action log 與 event 的責任差異是什麼？
6. `SEND_CHEER` 與 `ATTACH_CHEER` 的責任邊界在哪？
7. attack cost payment 為什麼不在這條 pilot 內處理？

---

## 八、允許暫時存在的技術債

可暫時接受：

1. package 結構尚未最終搬到 `actions/validators/resolvers/triggers`。
2. `AttachCheerLegacyResolutionBridge` 仍用既有 SQL / repository 載入 context。
3. idempotency key 尚未完整落到專用 persistence。
4. `MatchActionService.attachCheer(...)` 仍負責 legacy action log payload。
5. trigger handlers 仍是 thin/no-op。

不可接受：

1. ATTACH_CHEER validate / resolve 再度混回舊方法。
2. resolver 自己建立 action log。
3. event factory 依賴 action log payload。
4. 新流程沒有 focused unit/integration tests。
5. 這條 pilot 途中順手重寫 attack core 或 SEND_CHEER。

---

## 九、進入下一條 use case 的條件

只有 ATTACH_CHEER pilot 同時滿足：

- contract 包完成
- adapter path 完成
- focused tests 通過
- legacy glue 邊界可清楚說明

才建議進入下一條：

- `PLAY_CARD`
- 或 attack cost 的前置拆分

`ATTACK` 主流程仍應等 resource operation pilot 穩定後再進。
