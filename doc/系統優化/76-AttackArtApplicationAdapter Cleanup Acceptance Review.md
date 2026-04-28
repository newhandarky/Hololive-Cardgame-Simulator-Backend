# AttackArtApplicationAdapter Cleanup Acceptance Review

更新日期：2026-04-28
結論：通過，無 blocker

---

## 一、Review 範圍

本次 review 對照：

- `74-AttackArtApplicationAdapter 搬移評估.md`
- `75-AttackArtApplicationAdapter Acceptance Review.md`
- `AttackArtApplicationAdapterFactory`
- `AttackActionLogService`
- `MatchTimestampService`
- `AttackPayloadJsonService`
- `AttackPendingDecisionConversionService`
- `AttackEffectSummaryExtractor`
- `MatchActionService` constructor / `attackArt(...)`

目標是確認 adapter 搬移後的 cleanup 是否已完成：

- 移除 `AttackArtApplicationAdapterDependencies`
- 移除 `MatchActionService` anonymous dependencies bridge
- 下沉 adapter 所需 private helper
- 保持 `AttackArtApplicationService` contract 不變
- 保持 attack art payload / action order / finish check 行為不變

---

## 二、完成條件檢查

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| dependencies port 移除 | PASS | `AttackArtApplicationAdapterDependencies` 已刪除，production / test 無引用。 |
| factory constructor cleanup | PASS | `AttackArtApplicationAdapterFactory` 不再接收 dependencies port。 |
| JSON serializer 下沉 | PASS | attack adapter 已改用 `AttackPayloadJsonService`。 |
| pending decision 轉型下沉 | PASS | attack adapter 已改用 `AttackPendingDecisionConversionService`。 |
| effect summary extraction 下沉 | PASS | attack follow-up / rest payload 已改用 `AttackEffectSummaryExtractor`。 |
| timestamp helper 下沉 | PASS | rest / phase stage 已改用 `MatchTimestampService`。 |
| availability / snapshot 下沉 | PASS | rest availability 走 `AttackPerformanceAvailabilityService`，self-downed fan snapshot 走 `MatchGiftTriggerService`。 |
| `GIFT_TRIGGER` action log | PASS | defender damage prevention 的 `GIFT_TRIGGER` 改由 `AttackActionLogService.appendGiftTrigger(...)` 寫入。 |
| `MatchActionService` construction | PASS | constructor 只建立 factory，不再建立 dependencies bridge。 |
| application contract | PASS | 未改 `AttackArtApplicationService` public contract。 |

---

## 三、Allow / Block 清單

### 已允許暫留

- `MatchActionService` 保留 transaction boundary。
- `MatchActionService` 保留 ActionContext loading / pending guard。
- `MatchActionService` 保留 attack legality / attacker loading / art metadata loading。
- `MatchActionService` 保留 life loss send cheer enqueue。
- `MatchActionService` 保留其他 use case 使用的 `toJson(...)` / `touchUpdatedAt(...)` / `appendGiftTriggerActionsIfPresent(...)`。
- `MatchActionService.AttackArtActionWriter` 仍委派既有 private `appendAction(...)`，避免改變 action order 寫入規則。
- `AttackArtApplicationAdapterFactory` 仍直接使用 `JdbcTemplate` rest SQL。
- `AttackArtApplicationAdapterFactory` 仍直接使用 `MatchRepository` 做 phase save。
- `AttackApplicationRestPayloadStage` 仍以 nested record 暴露給 `MatchActionService.attackArt(...)` 讀取 finish summary。

### 已確認未做

- 未改 stage order。
- 未改 payload key / payload shape。
- 未改 `ATTACK_ART` action log action type。
- 未改 `GIFT_TRIGGER` action log timing。
- 未改 pending interaction timing。
- 未改 finish check timing。
- 未改 rest SQL 條件。
- 未改 phase transition timing。
- 未重寫單卡效果規則。
- 未把 `AttackArtApplicationService` 改成 Spring bean。

---

## 四、測試覆蓋

Focused unit：

- `AttackActionLogServiceTest`
  - `ATTACK_ART` action type
  - `GIFT_TRIGGER` action type
  - null context guard
- `AttackArtApplicationAdapterFactoryTest`
  - factory 建立 13 stage pipeline
  - rest / availability / timestamp / action log / finish order
  - factory constructor wiring

Compile：

- `./mvnw -q -DskipTests compile`

---

## 五、測試缺口

目前沒有 blocker。

可後續補強：

1. broad attack integration baseline 重跑，覆蓋特殊效果 path。
2. direct integration assertion：defender damage prevention `GIFT_TRIGGER` 仍維持原 action order。
3. direct assertion：life loss send cheer enqueue 仍留在 application service 外。
4. 若後續要搬 `AttackArtActionWriter`，需先補 action order persistence test。

上述缺口不阻擋本階段，因為 cleanup 僅移除 adapter dependencies bridge；focused unit 已鎖住 writer action type 與 factory stage order，compile 已確認 production wiring。

---

## 六、結論

`AttackArtApplicationAdapter` cleanup 已完成。

下一步建議：

- 先做 code review / commit checkpoint。
- 後續進入 attack pilot 收尾：
  - 補 broad attack integration baseline
  - 評估是否把 `AttackArtActionWriter` 搬成獨立 action writer adapter
  - 或開始下一條較小的 legacy boundary cleanup
