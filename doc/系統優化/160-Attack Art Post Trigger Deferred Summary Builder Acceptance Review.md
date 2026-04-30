# Attack Art Post Trigger Deferred Summary Builder Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本步延續 attack 前置小切片路線，處理 `AttackPostTriggerPendingService` 內仍殘留的 attack art post-trigger 主 summary 組裝。

本步只抽出：

- `ATTACK_ART_POST_TRIGGER` deferred summary 欄位組裝
- down event section 組裝
- Gift trigger section 組裝
- requested effects normalize / dedupe

不包含：

- defender Gift summary；該段已由 `GiftTriggeredEffectDeferredSummaryBuilder` 負責
- pending decision 建立時機
- pending context JSON shape
- `ATTACK_ART_POST_TRIGGER` action type / effect type
- down event 規則或 life loss 套用
- attack application stage order

## 二、完成內容

- 新增 `AttackArtPostTriggerDeferredSummaryBuilder`。
- `AttackPostTriggerPendingService` 改委派 builder 建立 `postTriggerEffectSummary`。
- 保留 `AttackPostTriggerPendingService` 的 pending orchestration 責任：
  - 判斷是否需要 attacker-side post-trigger pending
  - 判斷是否需要 defender Gift pending
  - 維持 attacker-side pending 先於 defender Gift pending 建立
- 新增 `AttackArtPostTriggerDeferredSummaryBuilderTest`，鎖定 builder 欄位 shape。

## 三、Allow / Block 對照

### Allow

- 把 attack art post-trigger 主 summary 從 pending service 移出。
- builder 可處理 `downEvent` 與 Gift trigger sections。
- builder 可保留原 trigger list 並輸出 normalized requested effects。
- pending service 可繼續持有 builder instance，避免擴大 DI 與建構子變更。

### Block

- 不把 attack art post-trigger 主 summary 併入 `GiftTriggeredEffectDeferredSummaryBuilder`。
- 不改 defender Gift summary path。
- 不改 pending decision writer / SQL。
- 不改 pending interaction type。
- 不改 `AttackPostTriggerPendingResult`。
- 不改 attack application stage order。
- 不補或修改廣域 `MatchActionServiceIntegrationTest` 既有不穩定案例。

## 四、測試結果

已通過：

- `./mvnw -q -Dtest=AttackArtPostTriggerDeferredSummaryBuilderTest,AttackPostTriggerPendingServiceTest test`

測試覆蓋：

- 無 Gift / 無 down event 時輸出 non-deferred summary
- Gift + down event 同時存在時輸出兩段 trigger sections
- requested effects normalize / dedupe
- `DOWN_EVENT` requested effect 不重複
- 既有 pending service 建立順序與 decision 條件

## 五、大檔尺寸變化

- `AttackPostTriggerPendingService.java`：`183` -> `82` 行，減少 `101` 行。
- 新增 `AttackArtPostTriggerDeferredSummaryBuilder.java`：`115` 行。
- 新增 `AttackArtPostTriggerDeferredSummaryBuilderTest.java`：`100` 行。
- `MatchActionService.java`：未變更。

## 六、剩餘缺口

目前沒有本步 blocker。

保留缺口：

- 未跑完整 `MatchActionServiceIntegrationTest`；完整 suite 仍有既有廣域不穩定。
- 可在後續補一支 attack post-trigger 代表性 integration smoke，但不阻擋本次 builder extraction。

## 七、結論

attack art post-trigger 主 deferred summary builder 可視為完成。

本步將 summary shape 組裝從 pending orchestration 拆出，且未改 attack 規則、pending timing 或 action type。

## 八、下一步

進入 code review / commit checkpoint。

commit 後建議繼續盤點 attack 相關殘留 adapter / resolver glue；優先看 `AttackArtApplicationAdapterFactory` 內可否再拆出獨立 stage resolver factory，而不是回頭改 attack 規則本體。
