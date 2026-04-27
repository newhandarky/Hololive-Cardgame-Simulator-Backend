# COLLAB Pilot Acceptance

更新日期：2026-04-27
定位：`COLLAB` pilot 驗收標準
用途：定義 COLLAB 何時算完成、哪些舊邏輯必須退出主流程、哪些契約不可退步，以及需要通過哪些驗證。

---

## 一、這份文件的角色

這份文件不是設計說明，而是驗收標準。

只有滿足本文件條件，`COLLAB` pilot 才能被視為：

- 第三條可複製 use case
- stage action 模板的第一個基準
- 後續 `ATTACH_CHEER` / `PLAY_CARD` / `ATTACK` 的參考起點

---

## 二、Pilot 完成目標

`COLLAB` pilot 完成後，系統應證明：

1. `Action / Validator / Resolver / EffectResolution / Event / Trigger / Application` 模板可套用到 stage action。
2. 舊 `MatchActionService.moveStageHolomem(... targetZone=COLLAB)` 已退化成 adapter / facade。
3. 一般 `MOVE_STAGE_HOLOMEM` 與 `COLLAB` 的核心責任已拆開。
4. collab effect / gift preview / confirm flow 有可描述的 event / trigger path。
5. focused tests 足以保護後續 refactor。

---

## 三、必須完成的架構條件

至少要有：

- `CollabAction`
- `CollabActionValidator`
- `CollabActionResolver`
- `CollabApplicationService`
- `CollabLegacyResolutionBridge`
- `CollabEffectResolutionService`
- `CollabEventFactory`
- `CollabTriggerDispatcher`
- 對應 trigger handler contract

---

## 四、舊入口 allow / block 清單

### 允許保留

- request DTO 轉 action
- target zone routing
- 非 COLLAB movement legacy branch
- 呼叫 application service
- legacy response payload 組裝
- 暫時的 action log compatibility glue
- 暫時的 post-effect finish/life-loss checks
- snapshot / API 相容 glue

### 不允許保留

- COLLAB 主要 validation 混在 `MatchActionService.moveStageHolomem(...)`
- BACK -> COLLAB mutation 混在 `MatchActionService.moveStageHolomem(...)`
- top deck -> holopower mutation 混在 `MatchActionService.moveStageHolomem(...)`
- collab / gift preview 混在主方法中
- confirm pending interaction 建立混在主方法中
- event 建立直接散在舊方法中
- trigger dispatch 分類直接散在舊方法中

---

## 五、不可改壞的對外契約

### 1. Board state

COLLAB 後必須維持：

- source Holomem 從 BACK 移到 COLLAB
- source 不應留在 BACK
- COLLAB zone 不可出現多張
- rested source 不可 collab
- 本回合不可重複 collab

### 2. Holopower

COLLAB 後必須維持：

- 牌庫頂 1 張卡進 holopower
- payload 中可追蹤 `holopowerCardInstanceId`
- `/state` 可看見 holopower 變化

### 3. Rule rejection

以下拒絕條件不可退步：

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

### 4. Effect preview / confirm

若 COLLAB 觸發效果或 gift：

- preview / confirm 不可少建
- 不可重複建
- pending interaction 必須能被既有 resolve decision path 接續
- collab section 與 gift section 需能同時出現在 confirm context

### 5. Action log / snapshot

前端不可觀察到：

- action log 顯示 COLLAB 成功，但 board state 沒更新
- board state 更新，但 holopower 沒更新
- pending interaction 已存在，但 state 尚未更新
- response 與 `/state` 的 zone / holopower / pending interaction 不一致

---

## 六、必跑驗證

### 1. Static / compile

- `git diff --check`
- `./mvnw -q -DskipTests compile`

### 2. Focused unit tests

至少覆蓋：

- validator allow / reject
- resolver result shape
- effect resolution service
- event factory order
- application orchestration

建議：

- `CollabActionValidatorTest`
- `CollabActionResolverTest`
- `CollabEffectResolutionServiceTest`
- `CollabEventFactoryTest`
- `CollabApplicationServiceTest`

### 3. Focused integration tests

至少覆蓋：

- direct application `resolveState` DB mutation
- existing legacy API rejection path 不退步
- collab effect confirm path
- collab gift confirm path

目前可沿用或新增代表性測試：

- `collabShouldIncludeResolutionOrderWithPriority`
- `collabShouldCreateGiftConfirmWhenGiftTriggeredByOwnHolomemCollab`
- `collabShouldTriggerOfficialEffectHsd08007UsingArchiveCheerOnTaggedSecondHolomem`
- `collabShouldNotTriggerOfficialEffectHsd08007WhenNoTaggedSecondHolomemExists`

---

## 七、完成後應能回答的問題

Pilot 完成時，團隊應能回答：

1. COLLAB 現在由哪個 validator 負責合法性？
2. COLLAB 現在由哪個 resolver 負責 state mutation？
3. COLLAB effect / gift preview 由哪個 service 負責？
4. COLLAB 會產生哪些 events？
5. 哪些 trigger 是 sync，哪些是 deferred？
6. action log 與 event 的責任差異是什麼？
7. `moveStageHolomem(... targetZone=CENTER)` 與 `targetZone=COLLAB` 的責任邊界在哪？

---

## 八、允許暫時存在的技術債

可暫時接受：

1. package 結構尚未最終搬到 `actions/validators/resolvers/triggers`。
2. `CollabLegacyResolutionBridge` 仍用既有 SQL / repository 載入 context。
3. `CollabEffectResolutionService` 仍用 legacy `match_pending_decisions` 寫入 confirm。
4. idempotency key 尚未完整落到專用 persistence。
5. `MatchActionService.moveStageHolomem(...)` 仍保留非-COLLAB movement legacy branch。

不可接受：

1. COLLAB validate / resolve 再度混回舊方法。
2. resolver 自己建立 pending interaction。
3. event factory 依賴 action log payload。
4. 新流程沒有 focused unit/integration tests。
5. 這條 pilot 途中順手重寫 attack core。

---

## 九、進入下一條 use case 的條件

只有 COLLAB pilot 同時滿足：

- contract 包完成
- adapter path 完成
- focused tests 通過
- legacy glue 邊界可清楚說明

才建議進入下一條：

- `ATTACH_CHEER`
- 或 `PLAY_CARD`

`ATTACK` 應等 `BLOOM` + `COLLAB` + 至少一條資源操作 use case 穩定後再進。
