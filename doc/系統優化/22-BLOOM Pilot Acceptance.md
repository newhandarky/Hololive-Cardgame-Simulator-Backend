# BLOOM Pilot Acceptance

更新日期：2026-04-27
定位：`BLOOM` pilot 驗收標準
用途：定義 BLOOM 何時算完成、哪些舊邏輯必須退出主流程、哪些契約不可退步，以及需要通過哪些驗證。

---

## 一、這份文件的角色

這份文件不是設計說明，而是驗收標準。

只有滿足本文件條件，`BLOOM` pilot 才能被視為：

- 第二條可複製 use case
- card action 模板的第一個基準
- 後續 `PLAY_CARD` / `ATTACH` / `COLLAB` 的參考起點

---

## 二、Pilot 完成目標

`BLOOM` pilot 完成後，系統應證明：

1. `Action / Validator / Resolver / Event / Trigger / Application` 模板可套用到 card action。
2. 舊 `MatchActionService.bloom(...)` 已退化成 adapter / facade。
3. target validation 與 state mutation 已分層。
4. effect preview / confirm flow 有可描述的 event / trigger path。
5. focused tests 足以保護後續 refactor。

---

## 三、必須完成的架構條件

至少要有：

- `BloomAction`
- `BloomActionValidator`
- `BloomActionResolver`
- `BloomApplicationService`
- `BloomLegacyResolutionBridge`
- `BloomEventFactory`
- `BloomTriggerDispatcher`
- 對應 trigger handler contract

---

## 四、舊入口 allow / block 清單

### 允許保留

- request DTO 轉 action
- 呼叫 application service
- legacy response payload 組裝
- 暫時的 effect preview / pending interaction compatibility glue
- snapshot / API 相容 glue

### 不允許保留

- 主要 target validation 混在 `MatchActionService.bloom(...)`
- 主要 card movement 混在 `MatchActionService.bloom(...)`
- 主要 holomem stack/top card mutation 混在 `MatchActionService.bloom(...)`
- event 建立直接散在舊方法中
- trigger dispatch 分類直接散在舊方法中

---

## 五、不可改壞的對外契約

### 1. Board state

BLOOM 後必須維持：

- source card 從 hand 移到 stage
- target holomem top card 更新
- stack depth 正確
- damage carry-over 不消失
- level / hp 正確

### 2. Rule rejection

以下拒絕條件不可退步：

- 不是目前玩家
- phase 不允許
- source card 不在 hand
- target 不合法
- 跳級不合法
- target 本回合已 BLOOM
- target 本回合剛進場
- hp 無法承接既有 damage
- 有 blocking pending interaction

### 3. Effect preview / confirm

若 BLOOM 觸發效果：

- preview / confirm 不可少建
- 不可重複建
- pending interaction 必須能被既有 resolve decision path 接續

### 4. Action log / snapshot

前端不可觀察到：

- action log 顯示 BLOOM 成功，但 board state 沒更新
- board state 更新，但 pending interaction 不存在
- response 與 `/state` 的 top card / level / stack 不一致

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

目前建議：

- `BloomActionValidatorTest`
- `BloomActionResolverTest`
- `BloomEventFactoryTest`
- `BloomApplicationServiceTest`

### 3. Focused integration tests

至少覆蓋：

- direct application `resolveState` DB mutation
- existing legacy API rejection path 不退步

目前建議：

- `BloomApplicationServiceIntegrationTest`
- `MatchActionServiceIntegrationTest#bloomShouldRejectSkippingLevelTransition`
- `MatchActionServiceIntegrationTest#bloomShouldRejectTargetEnteredThisTurn`

若跑到既有 legacy effect confirm 測試，需先確認 baseline；不可把非本 pilot 造成的既有不穩定測試混成 BLOOM adapter blocker。

---

## 七、完成後應能回答的問題

Pilot 完成時，團隊應能回答：

1. BLOOM 現在由哪個 validator 負責合法性？
2. BLOOM 現在由哪個 resolver 負責 state mutation？
3. BLOOM 會產生哪些 events？
4. 哪些 trigger 是 sync，哪些是 deferred？
5. action log 與 event 的責任差異是什麼？
6. effect preview / confirm 的暫時 legacy glue 邊界在哪？

---

## 八、允許暫時存在的技術債

可暫時接受：

1. package 結構尚未最終搬到 `actions/validators/resolvers/triggers`。
2. `BloomLegacyResolutionBridge` 仍用既有 SQL / repository 載入 context。
3. effect preview / confirm persistence 暫時留在 legacy glue。
4. idempotency key 尚未完整落到專用 persistence。

不可接受：

1. validate / resolve 再度混回舊方法。
2. resolver 自己建立 pending interaction。
3. event factory 依賴 action log payload。
4. 新流程沒有 focused unit/integration tests。

---

## 九、進入下一條 use case 的條件

只有 BLOOM pilot 同時滿足：

- contract 包完成
- adapter path 完成
- focused tests 通過
- legacy glue 邊界可清楚說明

才建議進入下一條：

- `PLAY_CARD`
- 或較保守地先做 `COLLAB` / `ATTACH_CHEER`

`ATTACK` 應等 card action 模板更穩後再進。
