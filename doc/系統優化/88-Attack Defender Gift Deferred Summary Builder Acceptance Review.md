# Attack Defender Gift Deferred Summary Builder Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 attack defender Gift deferred summary builder 的接線收斂。

範圍包含：

- `AttackPostTriggerPendingService` defender Gift summary
- `GiftTriggeredEffectDeferredSummaryBuilder` null trigger 容錯補強
- `GiftTriggeredEffectDeferredSummaryBuilderTest`
- `AttackPostTriggerPendingServiceTest`

不包含：

- attack art post-trigger 主 summary
- down event summary
- attack trigger sections builder
- attack post-trigger confirm message builder
- broader attack post-trigger flow
- `MatchActionServiceIntegrationTest` 廣域失敗修復

---

## 二、完成條件檢查

### attack post-trigger 主 summary 邊界

狀態：完成

`AttackPostTriggerPendingService` 的 attack post-trigger 主 summary 保留在原 service。

保留理由：

- `sourceActionType = ATTACK_ART_POST_TRIGGER`
- 需要包含 `downEvent`
- 需要包含 `triggerSections`
- 需要合併 attack gift trigger 與 down event requested effects

這不是純 Gift deferred summary，不納入 `GiftTriggeredEffectDeferredSummaryBuilder`。

### defender Gift summary 接線

狀態：完成

`AttackPostTriggerPendingService` 的 defender Gift summary 已改用 `GiftTriggeredEffectDeferredSummaryBuilder`。

保留：

- defender Gift pending 建立時機
- `createDefenderGiftPending` 呼叫位置
- post-trigger pending 與 defender Gift pending 的建立順序
- attack post-trigger 主 summary

### builder null trigger 容錯

狀態：完成

`GiftTriggeredEffectDeferredSummaryBuilder` 已補上 null trigger 容錯。

原因：

- defender Gift 舊 helper 原本以 `trigger == null ? null : trigger.get(...)` 避免 null trigger 解析失敗。
- 共用 builder 若要承接 defender Gift summary，需要保留這個容錯。
- null trigger 仍保留在 `triggeredGifts` 原 list 內，只是不參與 `requestedEffects` 解析。

---

## 三、Allow / Block 清單

### Allow

- PLAY_CARD / COLLAB / legacy MatchActionService / attack defender Gift summary 共用 `GiftTriggeredEffectDeferredSummaryBuilder`。
- builder 可忽略 null trigger 的 requested effect 解析，同時保留原 trigger list。
- `AttackPostTriggerPendingService` 可保留 private wrapper，降低呼叫點改動面。

### Block

- 不把 attack art post-trigger 主 summary 合併進 Gift deferred summary builder。
- 不把 down event 合併進 Gift deferred summary builder。
- 不把 trigger sections 合併進 Gift deferred summary builder。
- 不改 post-trigger pending 與 defender Gift pending 的建立順序。
- 不改 `AttackPendingDecision`。
- 不改 `match_pending_decisions` SQL 欄位。
- 不在本輪處理完整 `MatchActionServiceIntegrationTest` 廣域失敗。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=GiftTriggeredEffectDeferredSummaryBuilderTest,AttackPostTriggerPendingServiceTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

測試涵蓋：

- defender Gift summary deferred flag
- defender Gift summary `triggeredGifts`
- defender Gift pending decision 建立
- attack post-trigger pending 與 defender Gift pending 建立順序
- builder null trigger 容錯

---

## 五、剩餘缺口

無 blocker。

可後續補強但不阻擋本輪收斂：

- 補 attack defender Gift 代表性 integration smoke。
- 後續若要處理 attack art post-trigger 主 summary，應先規劃獨立 builder，不能直接塞進 Gift deferred summary builder。
- 完整 `MatchActionServiceIntegrationTest` 仍需另開穩定化 slice。

---

## 六、結論

Attack defender Gift deferred summary builder cleanup 通過 acceptance review。

本輪已完成：

1. attack post-trigger 主 summary 邊界確認
2. defender Gift summary 接線
3. builder null trigger 容錯補強
4. focused tests / compile / diff check
5. acceptance review

下一步建議轉向 Gift follow-up message / section builder planning；不要把 attack post-trigger 主 summary 硬併進 Gift deferred summary builder。
