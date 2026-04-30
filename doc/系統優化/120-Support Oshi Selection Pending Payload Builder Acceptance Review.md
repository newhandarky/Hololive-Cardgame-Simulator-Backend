# Support Oshi Selection Pending Payload Builder Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：MatchAction support / oshi skill card selection pending payload 組裝抽出

---

## 一、目標

本步延續 `SupportOshiEffectPayloadBuilder`，將 support / oshi skill 在建立 card selection pending 後的 action payload 組裝收斂到同一個 builder。

本步只搬移 pending payload 欄位組裝，不改：

- card selection pending decision 建立
- candidate selection 規則
- support / oshi skill 效果解析
- action type
- pending decision schema

---

## 二、完成內容

- `SupportOshiEffectPayloadBuilder` 新增 `buildSupportSelectionPendingPayload(...)`
- `SupportOshiEffectPayloadBuilder` 新增 `buildOshiSkillSelectionPendingPayload(...)`
- MatchAction 的 `SUPPORT_DECISION_PENDING` payload 改為委派 builder
- MatchAction 的 `OSHI_SKILL_DECISION_PENDING` payload 改為委派 builder
- 補 `SupportOshiEffectPayloadBuilderTest` pending payload 案例

---

## 三、Allow / Block 清單

### Allow

- 抽出 card selection pending action payload 組裝。
- 保留 `decisionType = CARD_SELECTION`。
- 保留 support pending payload 的 `cardInstanceId` / `cardId` 欄位。
- 保留 oshi skill pending payload 的 skill / oshi / holopower 欄位。

### Block

- 不改 `createCardSelectionPendingDecision(...)`。
- 不改 candidate count / minSelect / maxSelect 來源。
- 不改 action type。
- 不改 pending decision context。
- 不改 support / oshi skill 產品規則。

---

## 四、驗證重點

`SupportOshiEffectPayloadBuilderTest` 覆蓋：

- support card selection pending payload 欄位。
- oshi skill card selection pending payload 欄位。
- 共用欄位 `decisionId` / `decisionType` / `effectType` / `candidateCount` / `minSelect` / `maxSelect`。

本步亦需通過：

- `SupportOshiEffectPayloadBuilderTest`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 phase/touch/save 重複流程是否可抽成小型 helper。
- 盤點 support attach branch payload 是否也適合納入 builder，但需確認 attach support 是否屬於同一責任。
- full `MatchActionServiceIntegrationTest` 仍有既有廣泛失敗，需獨立穩定化。

---

## 六、結論

Support / oshi selection pending payload builder cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估 phase/touch/save helper 的下一個最小拆分。
