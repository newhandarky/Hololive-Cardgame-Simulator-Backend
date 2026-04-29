# Effect Post Trigger Pending Service Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 down-event pending service extraction。

範圍包含：

- 新增 package-private `EffectPostTriggerPendingService`
- MatchAction down-event pending helper 改為薄委派
- down-event preview extraction 移出 MatchAction
- down-event source card payload 建構移出 MatchAction
- 新增 `EffectPostTriggerPendingServiceTest`

不包含：

- support / oshi skill 效果結算流程改動
- down-event effect summary shape 改動
- pending context shape 改動
- pending writer SQL 或 schema 改動
- attack down-event flow 改動

---

## 二、完成條件檢查

### service extraction

狀態：完成

`EffectPostTriggerPendingService` 負責：

- 從 effect summary 擷取 top-level / nested deferred down-event preview
- 建立 source card cards payload
- 建立 `downEvent` additional context
- 委派 `FollowupTriggerConfirmPendingDecisionCreator`

### MatchAction boundary

狀態：完成

MatchAction 的 `createEffectPostTriggerConfirmPendingInteractionIfNeeded(...)` 現在只保留舊呼叫點相容的薄委派，不再持有 down-event preview extraction 或 source card payload 建構。

---

## 三、Allow / Block 清單

### Allow

- 新增 dedicated down-event pending service。
- 移除 MatchAction 內 down-event pending 建構細節。
- 新增 focused service test 覆蓋 nested down-event 與 no-op。

### Block

- 不改 `sourceActionType = EFFECT_POST_TRIGGER`。
- 不改 `effectType = DOWN_EVENT`。
- 不改 `originSourceActionType` / `downEvent` context keys。
- 不改 Oshi fallback zone `OSHI` 與一般卡片 fallback zone `ARCHIVE`。
- 不改 writer SQL 或 schema。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=EffectPostTriggerPendingServiceTest,FollowupTriggerConfirmPendingDecisionCreatorTest,EffectPostTriggerConfirmMessageBuilderTest test`
- `./mvnw -q -DskipTests compile`

commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 MatchAction support / oshi skill shared followup path 是否可再拆 service。
- 檢查 `AttackDownService` 的 down-event preview extractor 是否應與本 service 共用。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

Effect post-trigger pending service extraction 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估 support / oshi skill followup path 或 down-event preview extractor 共用化。
