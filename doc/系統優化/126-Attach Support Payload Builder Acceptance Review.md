# Attach Support Payload Builder Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：MatchAction attach support PLAY_SUPPORT payload 組裝抽出

---

## 一、目標

本步目標是收斂 MatchAction 內 attachable support branch 的 `PLAY_SUPPORT` action payload 組裝。

本步只搬移 payload 欄位與固定 attach support effect summary，不改：

- attachable support 判斷
- target holomem 驗證
- support limit 驗證
- card zone update SQL
- `match_holomem_supports` insert
- action append 順序

---

## 二、完成內容

- `SupportOshiEffectPayloadBuilder` 新增 `buildAttachSupportPayload(...)`
- MatchAction attach support branch 改為委派 builder 組裝 payload
- 保留 `ATTACH_SUPPORT` effect summary 欄位
- 補 `SupportOshiEffectPayloadBuilderTest` attach support payload 案例

---

## 三、Allow / Block 清單

### Allow

- 抽出 attach support action payload 組裝。
- 保留 `attached = true`。
- 保留 `selectedCardInstanceIds = []`。
- 保留固定 effect summary note。

### Block

- 不改 support attach SQL。
- 不改 support limit 檢查。
- 不改 attachable support type 判斷。
- 不改 action type。
- 不改 target id 正規化。

---

## 四、驗證重點

`SupportOshiEffectPayloadBuilderTest` 覆蓋：

- attach support 基本欄位。
- `ATTACH_SUPPORT` effect summary。
- `selectedCardInstanceIds` 空列表。

本步亦需通過：

- `SupportOshiEffectPayloadBuilderTest`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 盤點 MatchAction 剩餘 `PLAY_SUPPORT` / `USE_OSHI_SKILL` 分支是否仍有可抽 payload。
- 評估是否進入下一份 cleanup acceptance review，收斂這一批 pending / payload helper work。
- full `MatchActionServiceIntegrationTest` 仍有既有廣泛失敗，需獨立穩定化。

---

## 六、結論

Attach support payload builder cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估是否進入本批 MatchAction pending / payload cleanup acceptance review。
