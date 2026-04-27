# PLAY_CARD Persistence Contract

更新日期：2026-04-27
定位：`PLAY_CARD` pilot persistence 契約
用途：定義 PLAY_CARD 會讀寫哪些 legacy tables、哪些資料必須保持一致，以及哪些 persistence 技術債可暫留。

---

## 一、Persistence 目標

PLAY_CARD 的第一版 persistence 目標是：

- 保持現有 DB schema
- 將 mutation 責任集中到 resolver
- 將 action log compatibility glue 留在 adapter
- 不引入新 migration

---

## 二、主要讀取資料

`PlayCardLegacyResolutionBridge` 第一版可讀取：

- `matches`
- `match_players`
- `match_cards`
- `member_cards`
- `match_holomems`
- `match_pending_decisions`
- `match_turn_effects`
- `match_actions`

讀取用途：

- match / phase / turn context
- actor 是否在 match 中
- mulligan / opening setup state
- source card snapshot
- member metadata
- BACK occupancy
- pending interaction gate
- action lock
- duplicate action / idempotency

---

## 三、主要寫入資料

`PlayCardActionResolver` 第一版應寫入：

- `match_cards`
- `match_holomems`
- `match_holomem_stack_cards`

`PlayCardEffectResolutionService` 第一版可寫入：

- `match_pending_decisions`

legacy adapter 第一版可寫入：

- `matches`
- `match_actions`

---

## 四、一致性要求

PLAY_CARD 成功後必須同時滿足：

1. source card 已從 `HAND` 移到 `STAGE`。
2. source card `order_index = NULL`。
3. source card `is_face_down = openingReset`。
4. `match_holomems.match_card_id` 指向該 card instance。
5. `match_holomem_stack_cards` 有 stack order 1 的關聯。
6. `entered_turn_number` 等於 action turn number。
7. action log 顯示成功時，board state 已完成 mutation。
8. 若 MAIN 進場觸發 Gift confirm，pending decision context 與 action payload 可互相對應。

---

## 五、防重與 idempotency

第一版最低要求：

- action object 必須有 `idempotencyKey`
- bridge 可先從 `match_actions.payload ->> 'idempotencyKey'` 判斷 duplicate
- 若 legacy action log 還沒有完整保存 key，至少由 source card zone mutation 保護重複放置

後續補強：

- 專用 idempotency table 或欄位
- 成功結果重建
- in-progress action 狀態

---

## 六、交易邊界

第一版應維持：

- `validate`
- `resolveState`
- `resolveFollowup`
- adapter action log

都在可預期交易範圍內執行。

若後續拆出 deferred trigger：

- 必須明確區分 action state mutation 已完成與 pending decision 尚未 resolve 的狀態。

---

## 七、允許暫留技術債

可暫時接受：

1. 使用既有 table schema。
2. `PlayCardLegacyResolutionBridge` 直接使用 repository / `JdbcTemplate`。
3. action log 仍由 `MatchActionService.playToStage(...)` 寫入。
4. Gift pending decision 仍寫入既有 `match_pending_decisions`。
5. idempotency key 尚未完整落到專用 persistence。

不可接受：

1. resolver 寫 action log。
2. event factory 讀 action log payload。
3. Gift follow-up 直接混回 resolver。
4. PLAY_CARD 途中順手改 support card 或 attack core persistence。

---

## 八、完成標準

本 contract 落地後，應能回答：

1. PLAY_CARD 成功後有哪些 table 必須一致？
2. 哪些 writes 屬於 resolver，哪些 writes 暫留 adapter？
3. pending decision persistence 是否與 state mutation 分層？
4. legacy action log 是否仍只是相容 glue？
