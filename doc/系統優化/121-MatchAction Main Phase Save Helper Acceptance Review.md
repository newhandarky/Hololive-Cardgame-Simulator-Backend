# MatchAction Main Phase Save Helper Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：MatchAction MAIN phase transition + touch + save helper 收斂

---

## 一、目標

本步目標是收斂 MatchAction 內完全相同的三行狀態保存流程：

- `setCurrentPhase(MatchPhase.MAIN.name())`
- `touchUpdatedAt(...)`
- `matchRepository.saveAndFlush(...)`

本步只抽出私有 helper，不改 phase 判斷、不改 action append 順序、不改任何規則。

---

## 二、完成內容

- 新增 `transitionMatchToMainAndSave(MatchEntity match)`
- 將完全相同的 MAIN phase transition / touch / save 序列改為呼叫 helper
- 涵蓋 bloom、support、followup interaction resolve、oshi skill、baton touch 等已確認相同語意的位置
- 保留動態 phase、RESET / END phase、以及勝敗判定後單純 touch/save 的原本流程

---

## 三、Allow / Block 清單

### Allow

- 抽出完全相同的 MAIN phase 保存序列。
- 保留 `saveAndFlush` 時機。
- 保留 `touchUpdatedAt` 行為。

### Block

- 不改非 MAIN phase transition。
- 不改動態 phase resolution。
- 不改勝敗判定流程。
- 不改 action append 順序。
- 不改 match repository 寫入策略。

---

## 四、驗證重點

本步需通過：

- `SupportOshiEffectPayloadBuilderTest`
- `FollowupDecisionPayloadAppenderTest`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 盤點 interaction confirmed payload builder 是否適合抽出。
- 評估 attach support branch payload 是否納入 support / oshi payload builder。
- full `MatchActionServiceIntegrationTest` 仍有既有廣泛失敗，需獨立穩定化。

---

## 六、結論

MatchAction MAIN phase save helper cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估 interaction confirmed payload builder 的下一個最小拆分。
