# Support Oshi Effect Payload Builder Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：MatchAction support / oshi skill 已結算效果 payload 組裝抽出

---

## 一、目標

本步目標是收斂 MatchAction 內 support / oshi skill 已結算效果的 action payload 組裝責任。

本步只搬移 payload 欄位組裝，不改：

- support / oshi skill 效果結算
- phase / timestamp / save 順序
- followup decision 建立順序
- action append 類型
- pending decision schema

---

## 二、完成內容

- 新增 `SupportOshiEffectPayloadBuilder`
- 抽出直接 `PLAY_SUPPORT` 效果 payload
- 抽出 selection pending resolve 後的 support / oshi skill 效果 payload
- 抽出直接 `USE_OSHI_SKILL` 效果 payload
- MatchAction 改為委派 builder 產生上述 payload
- 新增 `SupportOshiEffectPayloadBuilderTest`

---

## 三、Allow / Block 清單

### Allow

- 抽出已結算效果 action payload 組裝。
- 保留 support payload 的 `cardInstanceId` / `cardId` / `limited` 欄位。
- 保留 oshi payload 的 `oshiCardInstanceId` / `oshiCardId` 欄位。
- 保留 oshi skill 的 `skillType` / `skillName` / `holopowerCost` / `holopowerPayment` 欄位。

### Block

- 不改 card selection pending payload。
- 不改 support / oshi skill 規則判斷。
- 不改 holopower 消耗流程。
- 不改 followup pending decision 建立流程。
- 不改 action payload key。

---

## 四、驗證重點

`SupportOshiEffectPayloadBuilderTest` 覆蓋：

- 直接 `PLAY_SUPPORT` payload 欄位。
- oshi source selection resolve payload 欄位。
- support source selection resolve payload 欄位。
- 直接 `USE_OSHI_SKILL` payload 欄位與 holopower payment。

本步亦需通過：

- `SupportOshiEffectPayloadBuilderTest`
- `FollowupDecisionPayloadAppenderTest`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 support / oshi skill card selection pending payload 是否適合抽出。
- 評估 phase/touch/save 重複流程是否可抽成小型 helper。
- full `MatchActionServiceIntegrationTest` 仍有既有廣泛失敗，需獨立穩定化。

---

## 六、結論

Support / oshi effect payload builder cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估 card selection pending payload builder 的下一個最小拆分。
