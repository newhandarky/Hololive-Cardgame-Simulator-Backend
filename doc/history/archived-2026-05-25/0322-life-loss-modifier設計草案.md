# 0322-life-loss-modifier設計草案

## 1. 背景

目前專案已經支援：

- 直接失去 Life
- `DOWN_NO_LIFE`
- `DOWN_EXTRA_LIFE`

這些能力足以處理：

- 一次固定扣 1 點 Life
- 額外再多扣 N 點
- 這次不扣 Life

但還不足以處理像 `HSD09-007` 這種文案：

- `自分の減るライフ-1`

這類效果不是「事件發生後再做一件事」，而是要改寫**同一次 Life Loss 的最終結果**。

## 2. 目前缺口

### 2.1 沒有正式的 Life Loss Context

目前流程比較接近：

1. 某個效果決定要扣幾點 Life
2. 直接執行 `ReduceLifeAction`
3. 再做後續判定

缺少一個中間層來記錄：

- 這次 Life Loss 是誰造成的
- 目標玩家是誰
- 原始應扣多少
- 套用修正後剩多少
- 哪些效果修改了這次結果

### 2.2 沒有 Life Loss Modifier 聚合模型

目前系統比較像只能做：

- `直接扣`
- `額外再扣`
- `完全不扣`

缺少：

- `-1`
- `+1`
- `設為 0`
- `最低為 0`
- 多個修正同時存在時的疊加規則

### 2.3 沒有「先修改、再結算」的時序

`HSD09-007` 這類效果要求：

1. 先知道這次原本會失去多少 Life
2. 套用 Gift / Skill / 其他修正
3. 再真正執行扣 Life

目前流程中，許多路徑是先扣，再跑 trigger。
這對「改寫既有 Life Loss」不夠。

### 2.4 後續判定沒有掛在「最終 Life Loss」

Life Loss 之後通常還會接：

- 勝敗判定
- `SEND_CHEER` 互動
- action log
- trigger summary

如果未來 Life Loss 可以被改寫，這些都必須依照：

- 最終實際扣了幾點

來判斷，而不是依原始預設值判斷。

## 3. 為什麼不能只補單卡特例

如果直接在 `HSD09-007` 的某條路徑裡硬寫：

- `if (cardId == HSD09-007) lifeLoss = Math.max(lifeLoss - 1, 0);`

會有幾個問題：

1. 只會在當前那條流程生效
2. 其他造成 down / lose life 的路徑不一定套得到
3. 後續如果再出現 `減るライフ-1` 類卡，會重複長更多特例
4. defeat / send cheer / log 很容易和真實結果不同步

所以這不是「少一個 if」的問題，而是系統缺一層規則模型。

## 4. 建議新增的模型

### 4.1 `LifeLossContext`

建議建立一個中間模型，至少包含：

- `matchId`
- `targetUserId`
- `sourceActionType`
- `sourceCardInstanceId`
- `baseLifeLoss`
- `resolvedLifeLoss`
- `reasons`
- `modifierSummaries`

用途：

- 先記錄原始應扣值
- 再逐步套用修正
- 最後只執行一次真正的扣 Life

### 4.2 `LifeLossModifier`

建議建立正式 modifier 類型，例如：

- `ADD`
- `REDUCE`
- `SET_ZERO`

每筆 modifier 應至少包含：

- `modifierType`
- `value`
- `sourceCardId`
- `sourceCardInstanceId`
- `reason`

### 4.3 `LifeLossResolutionService`

責任：

1. 收集這次 Life Loss 可套用的 modifier
2. 依固定規則排序
3. 算出最終 `resolvedLifeLoss`
4. 保證結果不小於 0

### 4.4 `LifeLossSummary`

供 action log / 前端 / 測試使用，建議至少包含：

- `baseLifeLoss`
- `resolvedLifeLoss`
- `appliedModifiers`
- `lifeReduced`
- `lostLifeCardInstanceIds`

## 5. 建議的結算流程

### 現況

1. down / effect 發生
2. 直接扣 Life
3. 再跑部分後續判定

### 目標流程

1. 建立 `LifeLossContext`
2. 決定 `baseLifeLoss`
3. 收集相關 modifier
4. 計算 `resolvedLifeLoss`
5. 執行真正扣 Life
6. 依最終結果做：
   - defeat 判定
   - `SEND_CHEER`
   - action log
   - 後續 trigger summary

## 6. 第一批需要吃這個模型的規則

### 6.1 高優先

- `HSD09-007`
- 未來所有 `自分の減るライフ-1`
- 未來所有 `相手の減るライフ+1`

### 6.2 中優先

- 會直接覆寫 Life Loss 的 Oshi Skill
- 會讓這次扣 Life 變成 0 的持續效果

### 6.3 已有能力可部分沿用

- `DOWN_NO_LIFE`
- `DOWN_EXTRA_LIFE`

但它們應該逐步改掛到同一個 `LifeLossResolutionService`，而不是長期維持平行邏輯。

## 7. 建議實作順序

### 第一步

只建立後端模型，不改前端：

- `LifeLossContext`
- `LifeLossModifier`
- `LifeLossResolutionService`

### 第二步

先把一條最單純的扣 Life 路徑改掛上去：

- `attackArt` 導致的標準 down lose life

### 第三步

補 `HSD09-007`

驗證：

- 符合條件時 `-1`
- 不符合條件時不改
- 最低不小於 0

### 第四步

把：

- `DOWN_NO_LIFE`
- `DOWN_EXTRA_LIFE`

也逐步改掛到同一套模型

## 8. 風險

這一段是高風險改動，因為會碰到：

- `attackArt`
- down event
- defeat 判定
- `SEND_CHEER`
- trigger / pending
- action log

因此建議：

1. 先補模型
2. 只改一條主路徑
3. 每一步都跑 runtime 測試
4. 不要一次把所有 Life Loss 相關邏輯大搬家

## 9. 建議測試矩陣

### 單一路徑

- 原始扣 1，modifier `-1`，最終 0
- 原始扣 2，modifier `-1`，最終 1
- 原始扣 1，無 modifier，最終 1

### 多 modifier

- 原始扣 1，`+1` 與 `-1` 同時存在
- 原始扣 2，`SET_ZERO` 與 `+1` 同時存在

### 後續效果

- 最終扣 0 時，不應建立多餘的 lose-life 後續
- 最終扣 > 0 時，`SEND_CHEER` 應依實際扣除量建立
- defeat 判定應依最終實際 Life 結果判斷

## 10. 結論

`HSD09-007` 目前未做，不是單卡漏實作而已。

真正缺的是：

- 正式的 Life Loss 中間模型
- 正式的 modifier 聚合規則
- 「先修改、再結算」的流程

在沒有這一層之前，硬補單卡特例會讓後續規則越來越難維護，也更容易出現流程不一致。
