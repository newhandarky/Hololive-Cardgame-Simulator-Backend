# Main Step Gift Followup Helper Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：MatchAction main-step Gift followup payload / pending decision helper 收斂

---

## 一、目標

本步目標是收斂 MatchAction 內重複的 main-step Gift followup 處理片段。

本步只抽成 MatchAction 私有 helper，不搬移到新 service，避免改動 Gift trigger side effect 邊界。

---

## 二、完成內容

- 新增 `appendMainStepGiftFollowupPayload(...)` 私有 helper
- 將 draw reveal resolve 後不需要 turn cheer 時的 main-step Gift followup 片段改為 helper
- 將 TURN_CHEER send cheer resolve 後的 main-step Gift followup 片段改為 helper
- 保留 `previewGiftTriggeredEffectsOnOwnMainStep(...)`
- 保留 `buildGiftTriggeredEffectDeferredSummary(...)`
- 保留 `createGiftTriggerDecisionWithoutSourceCard(...)`
- 保留 `followupDecisionPayloadAppender.append(...)`

---

## 三、Allow / Block 清單

### Allow

- 抽出重複的 main-step Gift followup payload append。
- 保留原呼叫條件與呼叫位置。
- 保留無 Gift effect 時仍寫入 `mainStepGiftEffects` summary 的行為。

### Block

- 不改 Gift trigger preview。
- 不改 Gift pending decision 建立。
- 不改 draw reveal / send cheer action append 順序。
- 不改 Gift deferred summary shape。
- 不把 helper 升級為獨立 service。

---

## 四、驗證重點

本步需通過：

- `SendCheerInteractionPayloadBuilderTest`
- `GiftTriggeredEffectDeferredSummaryBuilderTest`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 attach support branch payload 是否納入 `SupportOshiEffectPayloadBuilder`。
- 盤點 draw reveal / live start resolve payload 是否有下一個低風險 builder。
- full `MatchActionServiceIntegrationTest` 仍有既有廣泛失敗，需獨立穩定化。

---

## 六、結論

Main-step Gift followup helper cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估 attach support payload builder 的下一個最小拆分。
