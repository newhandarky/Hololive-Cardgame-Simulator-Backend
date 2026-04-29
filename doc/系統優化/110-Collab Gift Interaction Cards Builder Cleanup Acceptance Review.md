# Collab Gift Interaction Cards Builder Cleanup Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 Collab Gift interaction cards builder cleanup。

範圍包含：

- `CollabEffectResolutionService` 改用共用 `GiftTriggerInteractionCardsBuilder`
- 移除 Collab service 內 duplicated `buildGiftTriggerInteractionCards(...)`
- 移除 Collab service 內只供 duplicated builder 使用的 `asLong(...)`
- 保留 Collab mixed trigger pending context 與 writer input shape

不包含：

- Collab + Gift 合併 pending creator 抽出
- `COLLAB_TRIGGER` payload schema 改動
- Gift pending decision creator 改動
- Gift trigger preview / resolution 規則改動
- pending writer SQL 或 schema 改動

---

## 二、完成條件檢查

### builder delegation

狀態：完成

Collab pending cards 現在委派：

- `GiftTriggerInteractionCardsBuilder.buildGiftTriggerInteractionCards(...)`

source card 與 Gift holder cards 的 fallback / dedupe 規則由共用 builder 統一維護。

### Collab mixed trigger boundary

狀態：完成

本步沒有改：

- `sourceActionType = "COLLAB"`
- `effectType = "COLLAB_TRIGGER"`
- `hasCollabEffect`
- `giftTriggers`
- `giftCount`
- `triggerSections`
- confirm message composition

---

## 三、Allow / Block 清單

### Allow

- 移除 Collab service 內重複 cards builder。
- 新增 `GiftTriggerInteractionCardsBuilder` 欄位。
- 保留既有 Collab focused test 覆蓋 source + gift holder cards。

### Block

- 不把 Collab mixed trigger 改成 Gift pending decision creator。
- 不改 Collab pending action type / effect type。
- 不改 pending context shape。
- 不改 Gift selection context shape。
- 不改 writer SQL 或 schema。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=CollabEffectResolutionServiceTest,GiftTriggerInteractionCardsBuilderTest test`
- `./mvnw -q -DskipTests compile`

commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 Collab mixed trigger pending 是否需要 dedicated creator，但要避免破壞 Collab + Gift 合併 pending shape。
- 繼續清理 MatchAction legacy trigger confirm helper。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

Collab Gift interaction cards builder cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後再往 MatchAction legacy trigger confirm helper cleanup 推進。
