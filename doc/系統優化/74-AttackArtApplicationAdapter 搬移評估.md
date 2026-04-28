# AttackArtApplicationAdapter 搬移評估

更新日期：2026-04-28
結論：先拆 dependencies port，再搬 adapter factory

---

## 一、評估背景

`AttackArtApplicationService` 第一版已接上 production bridge，但目前 13 個 stage adapter 仍以 inner class 留在 `MatchActionService`。

AAA-4 acceptance review 允許此狀態暫留，AAA-5 已補上 `ATTACK_ART` payload snapshot integration test。下一步可評估是否將 adapter 搬出 `MatchActionService`，降低 `MatchActionService` 對 attack pipeline 細節的擁有量。

---

## 二、目前 adapter 範圍

目前 `MatchActionService` 內的 attack application adapter 包含：

- stage result 型別：
  - `AttackApplicationPreDamageStage`
  - `AttackApplicationCostStage`
  - `AttackApplicationTargetStage`
  - `AttackApplicationDamageStage`
  - `AttackApplicationDamagePreventionStage`
  - `AttackApplicationDamageApplicationStage`
  - `AttackApplicationPostDamageStage`
  - `AttackApplicationDownStage`
  - `AttackApplicationDefenderGiftStage`
  - `AttackApplicationPendingStage`
  - `AttackApplicationRestPayloadStage`
- stage resolver：
  - pre damage follow-up
  - cost
  - target
  - damage
  - damage prevention
  - damage application
  - post damage follow-up
  - down
  - defender gift follow-up
  - post trigger pending
  - rest and payload
  - action log
  - finish check
- shared helper：
  - `requireAttackStage(...)`
  - `requireAttackStageResult(...)`

---

## 三、搬移阻力

直接把 inner class 搬成獨立 class 會遇到下列依賴：

| 依賴 | 目前來源 | 風險 |
| --- | --- | --- |
| `appendAction(...)` | `MatchActionService` private helper | 搬出後若直接公開，會擴大 `MatchActionService` API。 |
| `toJson(...)` | `MatchActionService` private helper | 需要改成注入 serializer 或共用 mapper。 |
| `hasAvailableArtAttacker(...)` | `MatchActionService` private helper | rest / payload stage 需要判斷下一次 performance action。 |
| `touchUpdatedAt(...)` | `MatchActionService` private helper | phase transition 儲存 match 時使用。 |
| `loadSelfDownedFanSupportSnapshots(...)` | `MatchActionService` private helper | target stage 需要 defender self-downed fan snapshot。 |
| `toHoloxSlotRevealSummary(...)` | `MatchActionService` private helper | pre damage follow-up stage 需要舊 summary 相容轉型。 |
| `extractExecutedEffectSummaries(...)` | `MatchActionService` private helper | rest / payload stage 合併 finish check summary。 |
| `toAttackPendingDecision(...)` / `toFollowupInteractionDecision(...)` | `MatchActionService` private helper | pending decision 型別在 service 之間轉接。 |
| `jdbcTemplate` / `matchRepository` | `MatchActionService` field | rest stage 直接寫 rest 狀態與 phase。 |

判讀：

- adapter 的核心 orchestration 已經可搬。
- 阻力集中在 private helper 與 rest/action-log/finish stage 的基礎設施依賴。
- 若一次搬出，容易產生一個過肥的 constructor 或把大量 helper 改 public/package-private。

---

## 四、建議切法

### 第一階段：建立 dependencies port

新增 `AttackArtApplicationAdapterDependencies`，只暴露 adapter 需要的最小能力：

- `appendGiftTriggerAction(...)`
- `toJson(...)`
- `hasAvailableArtAttacker(...)`
- `touchUpdatedAt(...)`
- `loadSelfDownedFanSupportSnapshots(...)`
- `toHoloxSlotRevealSummary(...)`
- `extractExecutedEffectSummaries(...)`
- pending decision 轉型

此 port 先可由 `MatchActionService` 內部 anonymous implementation 提供，避免一次移動所有 helper。

### 第二階段：搬 stage result / resolver 到 factory

新增 `AttackArtApplicationAdapterFactory`：

- constructor 注入 attack domain services
- constructor 注入 `JdbcTemplate` / `MatchRepository`
- constructor 注入 `AttackArtApplicationAdapterDependencies`
- 提供 `create()` 回傳 `AttackArtApplicationService`

`MatchActionService` constructor 只保留：

```java
this.attackArtApplicationService = new AttackArtApplicationAdapterFactory(...).create();
```

### 第三階段：收斂 private helper

當 factory 搬出後，再檢查哪些 dependencies 可以下沉：

- JSON serialization 可改用專用 serializer。
- rest / phase 可延後抽成 `AttackRestStateAdapter`。
- self-downed fan snapshot 可評估移到 gift follow-up context service。

---

## 五、暫不建議

暫不建議一次做到：

- 把 rest / phase / action log / finish check 重新拆成多個 production service。
- 把所有 SQL loading 從 `MatchActionService` 搬出。
- 把 `MatchActionService` private helper 全部改成 package-private。
- 改 `AttackArtApplicationService` 的 public contract。

原因：

- AAA-5 已先鎖 payload contract，但 broad attack path 還是主要保護網。
- 目前風險最高的是搬移過程改變 stage timing，而不是 adapter class 位置本身。
- 先用 dependencies port 可讓下一步 diff 小、可 review、可 rollback。

---

## 六、Acceptance 條件

若進入 implementation，完成條件如下：

- `MatchActionService` 不再宣告 13 個 attack application resolver inner class。
- `MatchActionService` 不再宣告 attack application stage result record。
- `MatchActionService` constructor 仍建立同一條 `AttackArtApplicationService` pipeline。
- `attackArt(...)` 行為不變。
- `ATTACK_ART` payload snapshot integration test 通過。
- `AttackArtApplicationServiceTest` 通過。
- `./mvnw -q -DskipTests compile` 通過。

---

## 七、下一步

建議下一步實作 AAA-6：

1. 新增 `AttackArtApplicationAdapterDependencies`
2. 新增 `AttackArtApplicationAdapterFactory`
3. 將 stage result record / resolver 搬入 factory
4. `MatchActionService` 改成透過 factory 建立 `attackArtApplicationService`
5. 跑 focused unit + payload snapshot integration + compile
