# Opponent Followup Decision Payload Appender Acceptance Review

日期：2026-04-29
狀態：已完成
範圍：MatchAction opponent followup decision payload 欄位寫回 helper 收斂

---

## 一、目標

本步延續 `FollowupDecisionPayloadAppender`，將 MatchAction 內剩餘的 opponent followup decision payload 寫回 helper 收斂到同一個 appender。

本步只搬移欄位寫回責任，不改 advance phase gift followup 建立流程。

---

## 二、完成內容

- `FollowupDecisionPayloadAppender` 新增 `appendOpponent(...)`
- MatchAction 的 advance phase payload 改為委派 appender 寫入 opponent pending decision 欄位
- 移除 MatchAction 內部 `putOpponentFollowupDecisionPayload(...)`
- 補 `FollowupDecisionPayloadAppenderTest` opponent 欄位案例

---

## 三、Allow / Block 清單

### Allow

- 抽出 opponent payload 欄位寫回 helper。
- 保留 `opponentPendingInteractionDecisionId` / `opponentPendingInteractionDecisionType` 欄位。
- 保留 opponent helper 原本只檢查 payload / decision null 的語意。

### Block

- 不改 advance phase gift followup 建立順序。
- 不改 own decision payload 欄位。
- 不改 opponent pending decision type。
- 不改 pending decision schema。
- 不改 phase transition payload shape。

---

## 四、驗證重點

`FollowupDecisionPayloadAppenderTest` 覆蓋：

- opponent decision 寫入 `opponentPendingInteractionDecisionId`
- opponent decision 寫入 `opponentPendingInteractionDecisionType`
- opponent decision id 為 null 時仍維持既有寫入語意
- null payload / null decision 不寫入

本步亦需通過：

- `FollowupDecisionPayloadAppenderTest`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 support / oshi skill payload assembly 是否適合進一步收斂。
- 盤點 MatchAction phase/touch/save 的重複流程是否可抽成小型 coordinator。
- full `MatchActionServiceIntegrationTest` 仍有既有廣泛失敗，需獨立穩定化。

---

## 六、結論

Opponent followup decision payload appender cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估 support / oshi skill payload assembly 的下一個最小拆分。
