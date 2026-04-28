# ATTACK_FINISH_CHECK 前置拆分規劃

更新日期：2026-04-28
定位：`ATTACK_ACTION_LOG` 驗收完成後，拆 `attackArt(...)` 尾段 finish evaluator 的前置規劃
用途：把 attack art 結算後的勝負檢查順序定義成 contract，先固定行為，再準備完整 `AttackArtApplicationService`。

---

## 一、為什麼接著拆 finish check

`ATTACK_ACTION_LOG` 已把 `ATTACK_ART` action log 外殼收斂。`attackArt(...)` 尾段剩下的高價值可拆邊界是 finish check：

- card effect 直接定義勝負
- life 減少後檢查 life defeat
- Holomem downed 後檢查 no holomem defeat
- finish 後 touch / save match

這段不應混在完整 attack orchestration 裡，因為它也被 BLOOM / PLAY_CARD / COLLAB 等 use case 用相似順序處理。先拆 attack art 的 finish check，可以替後續共用化鋪路。

---

## 二、目前位置

`MatchActionService.attackArt(...)` 目前在 action log 之後執行：

1. `effectSummaryForChecks = restAndPayloadResult.effectSummaryForChecks()`
2. `evaluateCardEffectMatchFinish(...)`
3. 若未結束，且 `hasLifeReduced(effectSummaryForChecks)`：
   - `evaluateLifeDefeat(...)`
4. 若仍未結束，且 `hasHolomemDowned(effectSummaryForChecks)`：
   - `evaluateNoHolomemDefeat(...)`
5. 若任一 finish evaluator 回傳 true：
   - `touchUpdatedAt(context.match)`
   - `matchRepository.saveAndFlush(context.match)`
6. 最後仍執行 `enqueueLifeLossSendCheerInteractions(...)`

現有 private helper：

- `evaluateCardEffectMatchFinish(...)`
- `evaluateLifeDefeat(...)`
- `evaluateNoHolomemDefeat(...)`
- `hasLifeReduced(...)`
- `hasHolomemDowned(...)`
- `touchUpdatedAt(...)`

---

## 三、第一版目標

第一版只拆 finish check order orchestration，不搬完整 evaluator 規則。

應覆蓋：

1. 先呼叫 card effect finish evaluator。
2. card effect 已 finish 時停止後續 evaluator。
3. life reduced 為 true 時才呼叫 life defeat evaluator。
4. life defeat 已 finish 時停止 no holomem evaluator。
5. holomem downed 為 true 時才呼叫 no holomem defeat evaluator。
6. 任一 evaluator finish 時呼叫 match touch / save adapter。
7. 回傳 finish result，供後續 application service 使用。

建議新增：

- `AttackFinishCheckContext`
- `AttackFinishCheckResult`
- `AttackFinishCheckService`
- `AttackFinishCheckServiceTest`

第一版 service 可透過 adapter 委派既有 private helper：

- `CardEffectFinishEvaluator`
- `LifeDefeatEvaluator`
- `NoHolomemDefeatEvaluator`
- `EffectSummaryPredicate`
- `MatchSaver`

---

## 四、責任邊界

### `AttackFinishCheckService`

應負責：

- 控制 finish evaluator 呼叫順序
- 根據 effect summary predicate 決定是否呼叫 life / no holomem evaluator
- 在 finish 後呼叫 saver adapter
- 回傳 finish check result

不應負責：

- 實際解析 card effect match result
- 實際計算 life count
- 實際計算 stage holomem count
- 修改 action log
- 建立 pending decision
- enqueue life loss send cheer interaction

### `MatchActionService.attackArt(...)`

第一版應只改成：

- 呼叫 `AttackFinishCheckService.resolve(...)`
- 使用既有 private helper 作為 evaluator adapter
- 保留 `enqueueLifeLossSendCheerInteractions(...)` 在 finish check 之後

---

## 五、輸入 / 輸出草案

### Input

`AttackFinishCheckContext` 至少包含：

- `match`
- `actorUserId`
- `turnNumber`
- `effectSummaryForChecks`

若要降低 service 對 entity 的依賴，可第一版只傳 adapter 所需的 opaque match object；但為了接既有 helper，先使用 `MatchEntity` 較務實。

### Output

`AttackFinishCheckResult` 至少包含：

- `finished`
- `finishType`
  - `CARD_EFFECT`
  - `LIFE_DEFEAT`
  - `NO_HOLOMEM_DEFEAT`
  - `NONE`
- `saved`

---

## 六、現有測試基準

Focused tests 應覆蓋：

1. card effect finish 成功時不呼叫 life / no holomem evaluator。
2. 沒有 life reduced 時不呼叫 life evaluator。
3. life reduced 且 life evaluator finish 時不呼叫 no holomem evaluator。
4. 沒有 holomem downed 時不呼叫 no holomem evaluator。
5. holomem downed 且 no holomem evaluator finish 時回傳 `NO_HOLOMEM_DEFEAT`。
6. 任一 evaluator finish 時呼叫 saver。
7. 無 finish 時不呼叫 saver。

Integration baseline 可沿用：

- `attackArtShouldTriggerDownedHolomemExtraLifeLoss`
- `attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention`
- `attackArtShouldTriggerOfficialGiftHbp05028WhenStageBotanDealsSpecialDamageThirtyOrMore`

---

## 七、允許暫留

第一版允許：

- `evaluateCardEffectMatchFinish(...)` 留在 `MatchActionService`。
- `evaluateLifeDefeat(...)` 留在 `MatchActionService`。
- `evaluateNoHolomemDefeat(...)` 留在 `MatchActionService`。
- `hasLifeReduced(...)` / `hasHolomemDowned(...)` 留在 `MatchActionService`。
- `touchUpdatedAt(...)` / `matchRepository.saveAndFlush(...)` 透過 adapter 委派。

第一版不允許：

- 改變 finish check evaluation order。
- 改變 life defeat 只在 life reduced 時檢查的條件。
- 改變 no holomem defeat 只在 holomem downed 時檢查的條件。
- 改變 finish 後 save timing。
- 改變 life loss send cheer enqueue timing。

---

## 八、建議施工順序

### Step ATK-FC-1：contract / service skeleton

- 新增 context / result / service
- focused tests 覆蓋 finish check order
- 不改 `attackArt(...)`

### Step ATK-FC-2：adapter bridge

- `attackArt(...)` 改呼叫 finish check service
- 以 adapter 委派既有 private helper
- 保留 enqueue interaction 在 finish check 後
- 跑 attack finish baseline

### Step ATK-FC-3：acceptance review

- 檢查完成條件
- 檢查 allow / block 清單
- 盤點測試缺口
- 決定是否進入 `ATTACK_EFFECT_FOLLOWUP` 或 `AttackArtApplicationService` 第一版

---

## 九、下一步

建議進入 `ATK-FC-1`：

- `AttackFinishCheckContext`
- `AttackFinishCheckResult`
- `AttackFinishCheckService`
- `AttackFinishCheckServiceTest`
