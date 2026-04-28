# MatchActionService Legacy Helper Cleanup Planning

更新日期：2026-04-28
結論：先拆共用 serializer / timestamp helper，不直接擴大 action writer

---

## 一、背景

ATTACK pilot cleanup 已完成，`AttackArtApplicationAdapterFactory` 不再依賴 `MatchActionService` private helper bridge，attack action log writer 也已搬成 `AttackActionWriterAdapter`。

下一輪不建議直接把所有 action writer 搬出，因為 `MatchActionService.appendAction(...)` 同時服務多個 use case；若沒有跨 use case baseline，容易改動 action order 或 payload timing。

本文件先盤點仍留在 `MatchActionService` 的共用 helper，決定下一步安全切法。

---

## 二、目前 helper 使用量

盤點命令：

- `rg -c "toJson\\(" src/main/java/com/hololive/cardgame/service/MatchActionService.java`
- `rg -c "touchUpdatedAt\\(" src/main/java/com/hololive/cardgame/service/MatchActionService.java`
- `rg -c "appendGiftTriggerActionsIfPresent\\(" src/main/java/com/hololive/cardgame/service/MatchActionService.java`

目前結果：

| helper | 出現次數 | 判讀 |
| --- | ---: | --- |
| `toJson(...)` | 36 | 橫跨多個 action payload / pending payload，可先抽成共用 serializer。 |
| `touchUpdatedAt(...)` | 33 | 多個 use case 共用 timestamp mutation，可沿用 `MatchTimestampService` 擴大替換。 |
| `appendGiftTriggerActionsIfPresent(...)` | 2 | 目前只在 play support gift trigger 路徑使用，需先確認 event payload shape。 |

---

## 三、建議切法

### Step LHC-1：共用 JSON serializer

目標：

- 新增或擴充共用 `MatchPayloadJsonService`
- 先讓 `MatchActionService.toJson(...)` 委派到共用 service
- 再分批替換低風險 use case 呼叫點

不做：

- 不改 payload key / shape
- 不改 exception message 的對外語意
- 不一次替換所有 service 內的 private serializer

驗證：

- focused serializer unit test
- compile
- 代表性 action payload integration smoke

### Step LHC-2：共用 timestamp helper

目標：

- 將 non-attack `touchUpdatedAt(...)` 分批改用既有 `MatchTimestampService`
- 第一刀只替換單一 use case 或單一區塊

不做：

- 不改 match save timing
- 不改 phase transition timing
- 不改 transaction boundary

驗證：

- focused timestamp unit test 已存在
- 代表性 use case integration smoke

### Step LHC-3：Gift trigger action helper 評估

目標：

- 盤點 `appendGiftTriggerActionsIfPresent(...)` 的 payload shape
- 確認 play support / stage enter / triggered gift confirm 是否能共用 writer

不做：

- 不直接把 `appendAction(...)` 全域搬出
- 不先改 action order 計算

驗證：

- `GIFT_TRIGGER` action payload snapshot
- action order regression

---

## 四、下一步建議

下一步先做 LHC-1 的前置拆分：

1. 建立共用 JSON serializer 小 service。
2. 讓 `MatchActionService.toJson(...)` 先委派，不直接替換所有 call sites。
3. 補 focused unit test。
4. 跑 compile 與一個低風險 action payload smoke。

這個切法能先移除 private helper 的實作責任，同時避免一次碰 36 個 call site。
