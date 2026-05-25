# MatchEffect Heal Effect Execution Service Extraction Acceptance Review

日期：2026-05-25
狀態：已完成
commit 建議：`後端：抽出 Heal 效果執行服務`

---

## 一、變更摘要

本批將 `HEAL` execution 從 `MatchEffectService` 抽出為 package-private service：

- 新增 `MatchHealEffectExecutionService`。
- 新增 `MatchHealEffectExecutionServiceTest`。
- `MatchEffectService` 的 support / gift dispatch 改為委派新 service。
- `MatchBloomEffectDispatcher`、`MatchCollabEffectDispatcher` 對 `HEAL` 改為直接委派新 service。
- 移除 `MatchEffectService` 內原本的 `executeHealEffect(...)`、`resolveHealValue(...)` 與 `HEAL_PATTERN`。

---

## 二、責任邊界

`MatchHealEffectExecutionService` 負責：

- 解析 `value` / `amount` / `heal` 與 raw text 內的 `HP...回復` 數值。
- 透過 callback 沿用既有 Holomem 目標解析。
- 透過 callback 沿用既有 Holomem owner 與 card instance 解析。
- 透過 callback 沿用既有 HP change block 判斷。
- 更新 `match_holomems.damage_taken`，並限制不低於 0。
- 回傳既有 summary payload：`healRequested`、`healApplied`、`damageBefore`、`damageAfter`、`targetHolomemId`、`targetHolomemCardInstanceId`。

本批未處理：

- `DAMAGE`
- `MOVE_ZONE`
- `BUFF` / `DEBUFF`
- gift follow-up orchestration
- 公開 API、DB migration、seed data

---

## 三、行數變化

- `MatchEffectService.java`：`9,148` 行 -> `9,058` 行，淨減 `90` 行。
- `MatchHealEffectExecutionService.java`：新增 `169` 行。
- `MatchHealEffectExecutionServiceTest.java`：新增 `169` 行。

這批減少的是 HEAL execution 區塊本身與專屬解析 helper，主 service 仍保留高階 orchestration 與其他高風險 effect family。

---

## 四、驗證結果

已執行：

```bash
./mvnw -q -Dtest=MatchHealEffectExecutionServiceTest test
./mvnw -q -DskipTests compile
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportHealShouldRecoverTargetHolomemDamage test
git diff --check
```

結果：

- unit test：通過。
- compile：通過。
- focused integration：第一次在 sandbox 內因 Docker / PostgreSQL socket `Operation not permitted` 失敗；提高權限重跑後通過。
- diff check：通過。

---

## 五、下一步建議

下一批建議抽 `MOVE_ZONE`：

- 它仍屬 stage / zone movement 類效果，範圍比 `DAMAGE` 更集中。
- 可先補 focused unit test 鎖住目標解析、zone update 與 summary payload。
- 不建議在同批碰 `DAMAGE`，因為 `DAMAGE` 牽涉傷害修正、down event、life loss 與 gift follow-up。

建議下一個 commit：

```text
後端：抽出區域移動效果執行服務
```
