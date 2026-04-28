# AttackArtApplicationAdapter Acceptance Review

更新日期：2026-04-28
結論：通過，無 blocker

---

## 一、Review 範圍

本次 review 對照：

- `74-AttackArtApplicationAdapter 搬移評估.md`
- `AttackArtApplicationAdapterDependencies`
- `AttackArtApplicationAdapterFactory`
- `HoloxSlotRevealSummary`
- `FollowupInteractionDecision`
- `MatchActionService` constructor / `attackArt(...)`

目標是確認 AAA-7 是否已完成：

- 建立 adapter dependencies port
- 建立 adapter factory
- 將 stage result / resolver 從 `MatchActionService` 搬出
- 保持 `AttackArtApplicationService` contract 不變
- 保持 production attack art payload / stage order / finish check 行為不變

---

## 二、完成條件檢查

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| dependencies port | PASS | `AttackArtApplicationAdapterDependencies` 已集中 adapter 需要的 private helper bridge。 |
| adapter factory | PASS | `AttackArtApplicationAdapterFactory.create()` 建立同一條 13 stage pipeline。 |
| resolver 搬移 | PASS | 13 個 attack application resolver 已從 `MatchActionService` 搬入 factory。 |
| stage result 搬移 | PASS | attack application stage result records 已從 `MatchActionService` 搬入 factory。 |
| shared value object | PASS | `HoloxSlotRevealSummary` / `FollowupInteractionDecision` 已抽成 package-private record。 |
| `MatchActionService` construction | PASS | constructor 改由 factory 建立 `attackArtApplicationService`。 |
| `attackArt(...)` 行為 | PASS | `attackArt(...)` 仍只取 rest/payload stage 後 enqueue life loss send cheer。 |
| application contract | PASS | 未改 `AttackArtApplicationService` public contract。 |

---

## 三、Allow / Block 清單

### 已允許暫留

- `MatchActionService` 保留 transaction boundary。
- `MatchActionService` 保留 attack legality / loading 外殼。
- `MatchActionService` 保留 life loss send cheer enqueue。
- `MatchActionService` 透過 anonymous dependencies bridge 提供 private helper。
- `AttackArtApplicationAdapterFactory` 仍直接使用 `JdbcTemplate` rest SQL。
- `AttackArtApplicationAdapterFactory` 仍直接使用 `MatchRepository` 做 phase save。
- `AttackArtApplicationAdapterFactory` constructor 注入多個 attack domain services。
- `AttackApplicationRestPayloadStage` 以 package-private nested record 暴露給 `MatchActionService.attackArt(...)` 讀取 finish summary。

### 已確認未做

- 未改 stage order。
- 未改 payload key / payload shape。
- 未改 `GIFT_TRIGGER` timing。
- 未改 `ATTACK_ART` action log writer / action type。
- 未改 finish check timing。
- 未改 rest SQL 條件。
- 未改 phase transition timing。
- 未把 `AttackArtApplicationService` 改成 Spring bean。
- 未重寫任何單卡效果規則。

---

## 四、測試覆蓋

Focused unit：

- `AttackArtApplicationServiceTest`
  - stage 呼叫順序
  - previous stage result 傳遞
  - payload / action log / finish result 回傳
  - null context guard

Integration：

- `attackArtShouldApplyDamageToOpponentHolomemAndRestAttacker`
  - damage / rest behavior
  - `ATTACK_ART` payload snapshot / compatibility keys

Compile：

- `./mvnw -q -DskipTests compile`

---

## 五、測試缺口

目前沒有 blocker。

可後續補強：

1. adapter factory 層級的 rest / phase / action log order assertion。
2. broad attack integration baseline 重跑，覆蓋特殊效果 path。
3. direct assertion：life loss send cheer enqueue 仍留在 application service 外。
4. dependencies bridge 下沉後，補對應 helper-level tests。

上述缺口不阻擋本階段，因為 focused unit 已鎖 application stage order，payload snapshot integration 已確認 production bridge 仍輸出相容 payload。

---

## 六、結論

`AttackArtApplicationAdapterFactory` 第一版搬移完成。

下一步建議：

- 先做 code review / commit checkpoint。
- 後續方向：
  - 下沉 `hasAvailableArtAttacker` 到專用 attack helper。
  - 下沉 self-downed fan snapshot loading 到 gift follow-up context helper。
  - 或先補 adapter-level order assertion，再繼續拆 dependencies bridge。
