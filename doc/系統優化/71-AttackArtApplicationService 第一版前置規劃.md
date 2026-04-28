# AttackArtApplicationService 第一版前置規劃

更新日期：2026-04-28
定位：`ATTACK_EFFECT_FOLLOWUP` 驗收完成後，進入完整 `ATTACK` pilot application service 前的施工規劃
用途：盤點 `MatchActionService.attackArt(...)` 目前已拆出的 service contract，定義第一版 application service 的可搬移邊界與暫留項，避免一次搬動過多副作用。

---

## 一、目前已穩定的 attack 子 contract

`attackArt(...)` 目前已串接以下 contract：

1. `AttackEffectFollowupService`
   - pre-damage follow-up
   - damage prevention follow-up
   - post-damage official follow-up
2. `AttackCostService`
   - cost parse
   - passive cost reduction
   - payment preview / application
3. `AttackTargetService`
   - target resolve
   - target restriction
   - damage redirect
4. `AttackDamageService`
   - damage resolve
   - Holox bonus input
5. `AttackDamageApplicationService`
   - damage / life loss application
   - art summary
6. `AttackDownService`
   - down event
   - attacker side Gift trigger preview
   - art down triggered effects
7. `AttackDefenderGiftFollowupService`
   - defender self-downed / ally-downed Gift follow-up
   - defender official Oshi self-downed follow-up
8. `AttackPostTriggerPendingService`
   - attacker post-trigger pending
   - defender Gift pending
9. `AttackRestAndPayloadService`
   - attacker rest payload
   - `ATTACK_ART` payload
   - `effectSummaryForChecks`
10. `AttackActionLogService`
    - `ATTACK_ART` action append
11. `AttackFinishCheckService`
    - card effect finish
    - life defeat
    - no holomem defeat

這些 contract 已讓 `attackArt(...)` 的規則流程具備可搬移的骨架。

---

## 二、`attackArt(...)` 仍留在主服務的責任

目前仍直接留在 `MatchActionService.attackArt(...)`：

- transaction boundary
- `ActionContext` loading
- pending interaction guard
- turn required action validation
- phase / first-turn legality
- attacker row loading
- attacker zone / rest / per-turn usage legality
- art metadata loading
- defender self-downed holder snapshot loading
- defender Fan support snapshot loading
- attacker rest DB update
- match phase transition / save
- life loss send cheer interaction enqueue
- 各 adapter inner class

其中只有部分適合第一版搬入 application service。

---

## 三、第一版目標

第一版 `AttackArtApplicationService` 的目標不是讓 `MatchActionService.attackArt(...)` 立刻變成完全薄 adapter。

第一版應先搬移「已 contract 化的 orchestration」：

1. pre-damage follow-up
2. cost
3. target
4. damage
5. damage prevention
6. damage apply
7. post-damage official follow-up
8. down
9. defender Gift follow-up
10. post-trigger pending
11. rest / payload
12. action log
13. finish check

`MatchActionService.attackArt(...)` 第一版可暫時保留：

- context / legality loading
- attacker row / art metadata query
- snapshot loading
- rest DB update
- phase transition / save
- life loss send cheer enqueue

這樣可以先收斂主流程順序，但不碰最容易產生 transaction / persistence 行為差異的外殼。

---

## 四、建議新增 contract

### `AttackArtApplicationContext`

第一版建議接收已載入的資料，而不是由 application service 自己 query：

- `MatchEntity match`
- `matchId`
- `attackerUserId`
- `defenderUserId`
- `turnNumber`
- `attackerCardInstanceId`
- `targetCardInstanceId`
- attacker row fields：
  - holomem id
  - zone
  - card id
  - current level
  - main color
- art fields：
  - name
  - order index
  - cost cheer json text
  - effect json text
- defender snapshots：
  - self-downed holder snapshot
  - Fan support snapshots

### `AttackArtApplicationResult`

第一版應回傳 application service 已完成的輸出與後續外殼所需資料：

- `payload`
- `effectSummaryForChecks`
- `lostLifeCardInstanceId`
- `postTriggerConfirmDecision`
- `defenderGiftConfirmDecision`
- `hasLifeLossSendCheerInteractionCandidate`
- `matchFinished`
- `hasNextPerformanceAction`

若第一版仍由 `MatchActionService` 處理 rest / phase / action log / finish check，result 可先保持較小；但若 service 內已呼叫 `AttackRestAndPayloadService` / `AttackActionLogService` / `AttackFinishCheckService`，result 應回傳 action metadata 與 finish result。

### `AttackArtApplicationService`

第一版只負責 orchestration，不重寫規則：

- 呼叫既有 attack 子 service
- 保持既有呼叫順序
- 回傳後續外殼需要的結果
- 透過 adapter 委派 `MatchActionService` 內仍暫留的 helper

---

## 五、第一版 allow / block 清單

### Allow

- 允許 `MatchActionService` 保留 transaction boundary。
- 允許 `MatchActionService` 保留 legality / loading query。
- 允許 `MatchActionService` 保留 attacker rest DB update。
- 允許 `MatchActionService` 保留 phase transition / save。
- 允許 `MatchActionService` 保留 life loss send cheer enqueue。
- 允許 application service 透過 constructor 注入既有 attack 子 service。
- 允許 application service 透過 adapter 委派仍留在 `MatchActionService` 的 helper。

### Block

- 不在第一版改 payload key / payload shape。
- 不在第一版改 pending interaction timing。
- 不在第一版改 `ATTACK_ART` action log timing / action order。
- 不在第一版改 finish check 順序。
- 不在第一版改 rest timing。
- 不在第一版改 phase transition timing。
- 不在第一版重寫單卡效果規則。
- 不在第一版把所有 SQL loading 搬入 application service。

---

## 六、分段施工建議

### Step AAA-1：application service skeleton

- 新增 `AttackArtApplicationContext`
- 新增 `AttackArtApplicationResult`
- 新增 `AttackArtApplicationService`
- 新增 `AttackArtApplicationServiceTest`
- 第一版只固定 orchestration order，不接 production path

### Step AAA-2：接上 middle orchestration

- 將 `attackArt(...)` 中已 contract 化的 middle pipeline 改由 application service 呼叫
- `MatchActionService` 仍保留 legality / loading / rest / phase / enqueue
- 跑 broad attack integration baseline

### Step AAA-3：收斂 rest / phase 外殼評估

- 評估 attacker rest DB update 是否要包成 service 或 adapter
- 評估 phase transition / save 是否要包成 service 或 adapter
- 若風險偏高，保留在 `MatchActionService`，只補 acceptance review

### Step AAA-4：acceptance review

- 檢查 application service 第一版是否真的降低 `attackArt(...)` 主流程複雜度
- 檢查 allow / block 清單
- 盤點測試缺口

---

## 七、測試基準

Focused tests 應覆蓋：

1. application service 呼叫順序。
2. pre / prevention / post follow-up result 傳入 downstream service。
3. `AttackRestAndPayloadService` payload result 傳入 action log。
4. pending decisions 傳入 payload result。
5. finish check 使用 `effectSummaryForChecks`。
6. null context guard。

Integration baseline 建議至少覆蓋：

- vanilla attack damage / rest
- Holox / HBP02 pre-damage
- HBP01-027 damage prevention
- HBP01-087 official art extra
- HBP01-007 / HBP01-008 Oshi reactive
- down + defender Gift pending
- post-trigger pending
- finish check / life loss send cheer

---

## 八、下一步

建議進入 `AAA-1`：

- 建立 application service skeleton
- 先用 focused unit test 固定 orchestration order
- 不改 production path
