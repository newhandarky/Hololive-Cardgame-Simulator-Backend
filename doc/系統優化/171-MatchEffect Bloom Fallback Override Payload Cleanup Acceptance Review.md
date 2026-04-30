# MatchEffect Bloom Fallback Override Payload Cleanup Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本步承接 `170-MatchEffect Bloom Plan Factory Cleanup Acceptance Review.md`，繼續 Phase E1，將 Bloom fallback card override 的 payload mutation 拆成具名 helper。

本步只處理：

- `HSD02-007` fallback payload
- `HSD13-011` fallback payload
- `HSD07-007` fallback payload
- `HBP04-059` fallback payload
- `HBP02-016` fallback payload
- `HBP06-081` fallback payload

不包含：

- 卡號命中條件搬移
- runtime condition 判斷搬移
- dice 規則
- fallback 文案推斷
- effect executor

## 二、完成內容

- 新增 `applyHsd02007BloomFallbackPayload(...)`。
- 新增 `applyHsd13011BloomFallbackPayload(...)`。
- 新增 `applyHsd07007BloomFallbackPayload(...)`。
- 新增 `applyHbp04059BloomFallbackPayload(...)`。
- 新增 `applyHbp02016BloomFallbackPayload(...)`。
- 新增 `applyHbp06081BloomFallbackPayload(...)`。
- `resolveBloomEffectPlan(...)` 保留原條件判斷，只改為委派 payload mutation。

## 三、Allow / Block 對照

### Allow

- 把每張卡的 fallback payload 欄位集中到具名 helper。
- 保留每張卡 override 後回傳的 `effectTypes`。
- 保留 `searchCriteria` 的原始欄位結構。

### Block

- 不改任何卡號 prefix 判斷。
- 不改 `HBP04-059` 的 `ownHandCount` gate。
- 不改 `HBP02-016` 的 source level gate。
- 不改 `HBP06-081` 的 oshi 名稱與 stage cheer gate。
- 不改最後才補入 `diceRoll` 的既有流程。

## 四、測試結果

已通過：

- `./mvnw -q -DskipTests compile`

## 五、大檔尺寸變化

- `MatchEffectService.java`：`11,973` -> `12,003` 行，增加 `30` 行。

## 六、判讀

本步不是行數下降型 cleanup，而是把 Bloom fallback override payload schema 從流程判斷中拆出來。

目前沒有本步 blocker。

## 七、結論

MatchEffect Bloom fallback override payload cleanup 可視為完成。

本步只搬 payload mutation，不調整卡號命中、runtime gate 或 effect plan 解析語意。

## 八、下一步

進入 code review / commit checkpoint。

commit 後建議評估是否把這組 fallback payload helper 搬成 package-private builder 類；若不急著新增類，則轉向 Search / Return / Look family 的 candidate reader cleanup。
