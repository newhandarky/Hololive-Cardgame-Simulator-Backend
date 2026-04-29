# Gift Pending Decision Creator Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 Gift pending decision creator cleanup。

範圍包含：

- 新增 package-private `GiftPendingDecisionCreator`
- MatchAction Gift pending local adapter 改為委派 creator
- PlayCard Gift pending creation 改為委派 creator
- 保留 PlayCard 原本 cards payload shape
- 補 `GiftPendingDecisionCreatorTest`
- 搬移舊 MatchAction reflection 測試責任到 creator / builder 擁有者

不包含：

- Gift pending payload schema 改動
- pending writer SQL 改動
- Gift trigger preview / resolution 規則改動
- Collab mixed trigger pending flow 改動
- Attack post-trigger pending flow 改動

---

## 二、完成條件檢查

### creator extraction

狀態：完成

`GiftPendingDecisionCreator` 提供兩個入口：

- `createWithGiftTriggerInteractionCards(...)`
- `createWithCards(...)`

前者給 MatchAction 這類需要 source card + Gift holder cards builder 的路徑使用；後者給 PlayCard 這類已明確持有 cards shape 的路徑使用。

### MatchAction cleanup

狀態：完成

MatchAction 移除底層 Gift pending input / writer helper：

- `createGiftTriggeredEffectConfirmPendingInteraction(...)`
- `buildGiftTriggerInteractionCards(...)`

保留語意 adapter：

- `createGiftTriggerDecisionWithoutSourceCard(...)`
- `createBatonTouchGiftTriggerDecision(...)`

### PlayCard compatibility

狀態：完成

PlayCard 仍使用原本的 source card list：

- `followupSourceCardPayloadBuilder.buildOwnedCard(...)`
- `resolutionResult.targetZone()`

只把 pending input / writer creation 移到 creator，沒有改為 Gift trigger interaction cards builder，避免擴大 cards payload 行為。

---

## 三、Allow / Block 清單

### Allow

- 新增 package-private creator。
- MatchAction / PlayCard 共用 Gift pending input + writer creation。
- 移除 MatchAction 私有底層 Gift pending helper。
- 測試改對新擁有者驗證。

### Block

- 不改 Gift pending action type、effect type、title、message。
- 不改 Gift selection context shape。
- 不改 cards payload shape。
- 不改 writer SQL 或 pending decision schema。
- 不改 Gift trigger preview / resolve 規則。
- 不改 Collab mixed trigger flow。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=GiftPendingDecisionCreatorTest,MatchActionServiceTest,PlayCardEffectResolutionServiceTest,GiftTriggerInteractionCardsBuilderTest test`
- `./mvnw -q -DskipTests compile`

commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 Collab mixed trigger pending flow 是否要拆出 dedicated creator，但需要保留 Collab + Gift 合併 pending 的特殊 shape。
- 繼續清理 MatchAction legacy trigger confirm helper。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

Gift pending decision creator cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後再評估 Collab mixed trigger pending 是否適合拆分，或回到 MatchAction legacy trigger confirm helper cleanup。
