# MatchEffect Down Effect Execution Service Extraction Acceptance Review

日期：2026-05-25
狀態：完成

## 背景

本批接續 `RETURN_CHEER_TO_DECK_BOTTOM` 後，處理風險較高但收益明確的 Down 類 execution。`DOWN_NO_LIFE` / `DOWN_EXTRA_LIFE` 牽涉對手 BACK Holomem 查詢、附屬卡歸檔、Holomem stack 歸檔、down event、額外生命扣減與 SEND_CHEER pending，因此本批採 callback 邊界沿用既有共用 helper，避免同批搬動 down event / life / gift follow-up 的深層流程。

## 完成內容

- 新增 `MatchDownEffectExecutionService`。
- 搬移 `executeDownNoLifeEffect(...)` 與 `executeDownExtraLifeEffect(...)` 的 execution flow。
- `MatchEffectService` 的 support / gift dispatch 改為委派新 service。
- `MatchBloomEffectDispatcher` 與 `MatchCollabEffectDispatcher` 對 `DOWN_NO_LIFE` / `DOWN_EXTRA_LIFE` 改為直接委派新 service。
- 新增 `MatchDownEffectExecutionServiceTest`，鎖住 dice no-op、no-life down、extra-life down 三條核心路徑。

## 責任邊界

`MatchDownEffectExecutionService` 負責：

- dice condition no-op。
- 對手 BACK Holomem 目標查詢與 `HPが40以上減っている` 條件。
- Down 目標卡片 id 查詢。
- 呼叫既有附屬 Cheer / Support / Holomem stack 歸檔 callback。
- 刪除 downed `match_holomems` row。
- stack 為空時將目標 match card 移入 `ARCHIVE`。
- 呼叫既有 down event callback。
- `DOWN_EXTRA_LIFE` 的額外生命扣減與 summary payload。

本批不處理：

- `DAMAGE`
- `HEAL`
- `MOVE_ZONE`
- down event / gift follow-up 內部拆分
- 公開 API、DB migration、seed data

## 行數變化

- `MatchEffectService.java`：`9,343` 行 -> `9,148` 行，淨減 `195` 行。
- 新增 `MatchDownEffectExecutionService.java`：`379` 行。
- 新增 `MatchDownEffectExecutionServiceTest.java`：`157` 行。

## 驗證結果

已執行：

```bash
./mvnw -q -Dtest=MatchDownEffectExecutionServiceTest test
./mvnw -q -DskipTests compile
./mvnw -q -Dtest=MatchBloomEffectIntegrationTest#playSupportDownExtraLifeShouldReduceAdditionalLifeAndCreateSendCheerInteraction test
```

整合測試第一次在 sandbox 內因 Docker / PostgreSQL socket 權限失敗，已依授權提高權限重跑並通過。

## 下一步建議

下一批建議不要立刻把所有戰鬥類效果一起搬。若要延續 production god class 拆分，可先規劃 `HEAL` 或 `MOVE_ZONE` 這類相對較集中的 effect；若要處理高收益區塊，則開始拆 `DAMAGE`，但必須先補更完整 focused tests，涵蓋傷害修正、特殊傷害、down event、life loss 與 gift follow-up。
