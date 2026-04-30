# MatchEffect Bloom Plan Factory Cleanup Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本步承接 `169-MatchEffect Structured Plan Payload Cleanup Acceptance Review.md`，繼續 Phase E1，先把 Bloom / Collab effect plan 的純建立邏輯集中。

本步只處理：

- empty `BloomEffectPlan` 建立
- active `BloomEffectPlan` 建立
- fallback effect payload 基底建立

不包含：

- 卡號特例邏輯搬移
- runtime condition 判斷
- fallback 文案推斷
- dice 規則
- effect executor

## 二、完成內容

- 新增 `emptyBloomEffectPlan(rawText, diceRoll)`。
- 新增 `activeBloomEffectPlan(effectTypes, payload, rawText, diceRoll)`。
- 新增 `buildFallbackEffectPayload(effectTypes, rawText, diceRoll)`。
- `resolveBloomEffectPlan(...)` 改用 helper 建立 empty plan 與 active plan。
- `resolveCollabEffectPlan(...)` 改用 helper 建立 fallback payload 與 active plan。

## 三、Allow / Block 對照

### Allow

- 收斂 `new BloomEffectPlan(...)` 與 `objectMapper.createObjectNode()` 的重複建立樣板。
- 收斂 fallback payload 的 `type` / `effects` / `rawText` / `diceRoll` 基底欄位。
- 保留 Bloom fallback 的卡號特例仍在原流程中。

### Block

- 不搬移 `HSD02-007` / `HSD13-011` / `HSD07-007` / `HBP04-059` / `HBP02-016` / `HBP06-081` 特例。
- 不改 `resolveBloomDiceRoll(...)`。
- 不改 structured plan helper。
- 不改 `resolveCollabEffectPlan(..., runtimeContext)` 的場況修正。

## 四、測試結果

已通過：

- `./mvnw -q -DskipTests compile`

## 五、大檔尺寸變化

- `MatchEffectService.java`：`11,966` -> `11,973` 行，增加 `7` 行。

## 六、判讀

本步不是以行數下降為主要目的，而是建立可重用 plan factory 小邊界，讓後續 fallback Bloom / Collab payload builder 能再往外抽，不必每次碰到 `BloomEffectPlan` 建構樣板。

目前沒有本步 blocker。

## 七、結論

MatchEffect Bloom / Collab plan factory cleanup 可視為完成。

本步只集中建立樣板，不調整 effect plan 的判斷條件或 payload schema。

## 八、下一步

進入 code review / commit checkpoint。

commit 後建議繼續 Phase E1，優先抽出 Bloom fallback card override payload builder；該步需要保留卡號特例原規則，只搬 payload mutation。
