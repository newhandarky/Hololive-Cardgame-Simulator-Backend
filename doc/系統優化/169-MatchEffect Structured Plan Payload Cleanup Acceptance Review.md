# MatchEffect Structured Plan Payload Cleanup Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本步回到 `MatchEffectService` Phase E1 cleanup，先處理結構化 Bloom / Collab effect plan 的 payload builder 重複邏輯。

本步只處理：

- `resolveStructuredBloomEffectPlan(...)`
- `resolveStructuredCollabEffectPlan(...)`
- 兩者共用的 structured effect payload 組裝

不包含：

- fallback 文案推斷
- 卡號特例分支
- dice 規則
- effect executor
- pending decision payload schema

## 二、完成內容

- 新增 `resolveStructuredEffectPlan(passiveNode, effectFieldName)` private helper。
- `resolveStructuredBloomEffectPlan(...)` 改委派 `bloomEffect` 欄位。
- `resolveStructuredCollabEffectPlan(...)` 改委派 `collabEffect` 欄位。
- 保留既有 `BloomEffectPlan` record 與外部呼叫面。

## 三、Allow / Block 對照

### Allow

- 合併 Bloom / Collab structured payload builder 重複程式碼。
- 保留原本 `type` / `effects` / `rawText` / `searchCriteria` / `value` / `cards` / `amount` / `diceCondition` / `effectDiceConditions` / `diceRoll` 欄位輸出。
- 保留 structured JSON 優先、fallback 文案推斷次之的流程。

### Block

- 不改 `resolveBloomEffectPlan(...)` 的卡號特例。
- 不改 `resolveCollabEffectPlan(..., runtimeContext)` 的場況修正。
- 不改 `inferBloomEffectTypes(...)`。
- 不改 `resolveDiceRoll(...)`。
- 不新增 effect family service，避免在同一批擴大搬移範圍。

## 四、測試結果

已通過：

- `./mvnw -q -DskipTests compile`

## 五、大檔尺寸變化

- `MatchEffectService.java`：`12,009` -> `11,966` 行，減少 `43` 行。

## 六、剩餘缺口

目前沒有本步 blocker。

保留缺口：

- fallback Bloom payload builder 仍在 `resolveBloomEffectPlan(...)` 內，包含多個卡號特例，不適合同批搬移。
- fallback Collab payload builder 仍在 `resolveCollabEffectPlan(...)` 內，後續可再抽成小 helper。
- Search / return / look family 仍未進入 Phase E3 服務拆分。

## 七、結論

MatchEffect structured plan payload cleanup 可視為完成。

本步只消除 Bloom / Collab structured JSON plan builder 的重複，不改任何 effect 執行或條件判斷。

## 八、下一步

進入 code review / commit checkpoint。

commit 後建議繼續 Phase E1，優先抽出 fallback Bloom / Collab plan payload builder 的純組裝 helper；再評估是否進入 Search / Return / Look family planning。
