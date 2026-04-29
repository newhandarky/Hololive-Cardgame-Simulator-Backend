# Followup Source Card Payload Helper Cleanup Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 `83-Followup Source Card Payload Helper Cleanup Planning.md` 內的 FSCP-1 / FSCP-2 / FSCP-3。

範圍只包含 BLOOM / PLAY_CARD / COLLAB trigger confirm pending context 的 source card payload helper cleanup：

- `FollowupSourceCardPayloadBuilder`
- `BloomEffectResolutionService`
- `PlayCardEffectResolutionService`
- `CollabEffectResolutionService`
- focused unit baseline

不包含：

- `FollowupTriggerConfirmPendingDecisionWriter` input shape
- Gift trigger payload shape
- selection context
- confirm message builder
- legacy API action log payload
- broader follow-up framework

---

## 二、完成條件檢查

### FSCP-1：PLAY_CARD baseline

狀態：完成

已強化 `PlayCardEffectResolutionServiceTest.resolveShouldCreateGiftConfirmPendingDecisionWhenGiftTriggersExist`。

鎖住：

- `source_action_type = GIFT`
- `effect_type = GIFT_TRIGGER`
- pending context `cards[0]`
  - `cardInstanceId = 701`
  - `cardId = hBP01-001`
  - `zone = BACK`

### FSCP-2：COLLAB baseline

狀態：完成

已新增 `CollabEffectResolutionServiceTest.resolveShouldIncludeSourceAndGiftHolderCardsWhenGiftTriggerExists`。

鎖住：

- `source_action_type = COLLAB`
- `effect_type = COLLAB_TRIGGER`
- source card fallback：
  - `cardInstanceId = 701`
  - `cardId = hBP01-001`
  - `zone = STAGE`
- Gift holder fallback：
  - `cardInstanceId = 801`
  - `cardId = hBP06-014`
  - `zone = BACK`

### FSCP-3：small helper port

狀態：完成

已新增 `FollowupSourceCardPayloadBuilder`。

helper 範圍：

- `buildOwnedStageCard(...)`
- `buildOwnedCard(...)`
- 只包裝 `FollowupCardCandidateLoader` 與 fallback zone 傳遞

---

## 三、Allow / Block 清單

### Allow

- BLOOM / PLAY_CARD / COLLAB 可共用 `FollowupSourceCardPayloadBuilder` 建立 pending context `cards` payload。
- fallback zone 仍由呼叫端決定。
- `FollowupCardCandidateLoader` 繼續負責 DB row / fallback payload shape。
- COLLAB 繼續由 `CollabEffectResolutionService` 決定是否加入 source card、Gift holder card，以及 holder 去重。

### Block

- 不把 fallback zone 統一成單一常數。
- 不把 Gift holder card selection 邏輯搬進 builder。
- 不把 trigger payload builder 搬進 builder。
- 不把 selection context 搬進 builder。
- 不把 confirm message builder 搬進 builder。
- 不改 `FollowupTriggerConfirmPendingDecisionInput`。
- 不改 `match_pending_decisions` SQL 欄位。

---

## 四、測試與驗證

已執行：

- `./mvnw -q -Dtest=BloomEffectResolutionServiceTest,PlayCardEffectResolutionServiceTest,CollabEffectResolutionServiceTest,FollowupTriggerConfirmPendingDecisionWriterTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

測試涵蓋：

- BLOOM fallback `STAGE`
- PLAY_CARD fallback target zone
- COLLAB source card fallback `STAGE`
- COLLAB Gift holder fallback holder zone
- 共用 writer selection bounds 與 null context baseline

---

## 五、剩餘缺口

無 blocker。

可後續補強但不阻擋本輪收斂：

- 代表性 legacy API integration smoke 可在下一輪 use case cleanup 補：
  - BLOOM trigger confirm path
  - PLAY_CARD Gift stage enter confirm path
  - COLLAB collab + Gift confirm path
- 若後續要抽 Gift trigger payload helper，需另開規劃，不能和 source card payload helper 混在一起。

---

## 六、結論

Followup source card payload helper cleanup 通過 acceptance review。

本輪已完成：

1. BLOOM baseline
2. PLAY_CARD baseline
3. COLLAB baseline
4. small helper port
5. acceptance review

下一步建議回到系統優化路線，挑下一個低風險 cleanup slice；若要延續 follow-up cleanup，應優先規劃 Gift trigger payload helper，而不是直接抽完整 follow-up framework。
