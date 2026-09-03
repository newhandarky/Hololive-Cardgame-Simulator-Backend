# BE-001：建立 MatchCommandGateway

狀態：DONE（完成）
風險：中
Repository：`hololive-cardgame-backend`
建議 commit：`後端：建立對戰指令應用層入口`
前置工作：無

## 一、目標

建立 controller 與既有 action services 之間的 application seam，先用 `CONCEDE` 作 pilot。完成後：

- controller 只處理 authentication、request mapping 與 HTTP response。
- command 的 transaction orchestration、handler selection 與結果型別有固定入口。
- 舊 `MatchActionService.concede(...)` 行為不變，暫時作 legacy adapter。
- 後續 action 可逐條遷移，不需要先重寫 God Class。

## 二、必讀

- `AGENTS.md`
- `doc/系統優化/next/00-架構現況與決策.md`
- `doc/系統優化/next/01-目標模組架構.md`
- `src/main/java/com/hololive/cardgame/controller/MatchController.java`
- `MatchActionService.concede(...)` 定義、呼叫點與相關測試

修改任何 symbol 前依 `AGENTS.md` 執行 GitNexus upstream impact。

## 三、範圍內

新增：

- `MatchCommand`
- `ConcedeMatchCommand`
- `MatchCommandContext`
- `MatchCommandResult`
- `MatchCommandHandler<C>`
- `MatchCommandGateway`
- `LegacyConcedeCommandHandler`

調整：

- `MatchController.concede(...)` 改呼叫 gateway。
- 必要的 focused unit/integration test。

可以暫放現有 `service` package，但類別命名與 package-private/public 邊界要能在後續搬至 `match.application.command`。

## 四、非目標

- 不遷移其他 action。
- 不加 DB `state_version` 或 command receipt table；那是 BE-005。
- 不改 REST path、request body、response DTO 或 WebSocket payload。
- 不重寫 `MatchActionService.concede(...)` 規則。
- 不建立通用反射式 handler registry。
- 不改 NPC。

## 五、設計約束

建議最小介面：

```java
public sealed interface MatchCommand permits ConcedeMatchCommand {}

public record ConcedeMatchCommand(long matchId, long actorUserId) implements MatchCommand {}

public interface MatchCommandHandler<C extends MatchCommand> {
    Class<C> commandType();
    MatchCommandResult handle(C command);
}
```

`MatchCommandGateway`：

- 依明確 map 找 handler。
- 未註冊 command fail fast。
- 不解析 HTTP DTO。
- 不直接依賴 controller。
- pilot 可以回傳 `LobbyMatchResponse` 所需的 domain/application result，但不可讓 handler 回傳 `ResponseEntity`。

publish 相容策略：

- 本工作包可暫時保留 controller 的 `publish(...)`，避免同批改 transaction/event 語意。
- gateway result 必須保留足夠資訊讓 controller 取得目前 `LobbyMatchResponse`。
- commit 後 publish 統一移入 BE-005。

## 六、執行步驟

1. 以既有 concede integration test 鎖定：
   - actor membership。
   - finished status/winner。
   - duplicate/invalid state 現況。
   - REST status mapping。
2. 新增 typed command/result/handler/gateway。
3. handler 只委派 legacy service 與必要的 match lookup。
4. controller concede endpoint 切換到 gateway。
5. 確認其他 endpoint 無 diff。
6. 以 architecture note 記錄後續 handler package 方向，不做全量搬移。

## 七、驗收

- `POST /api/matches/{matchId}/actions/concede` 契約不變。
- controller 的 concede method 不含規則與 transaction 細節。
- gateway 可在 unit test 中以 fake handler 驗證 dispatch。
- 未註冊 command 有明確例外。
- legacy service 測試與 REST focused test 通過。
- 沒有新增 `Map<String, Object>` command payload。
- GitNexus detect changes 只列出預期 concede flow。

## 八、驗證

```bash
./mvnw -q -Dtest=<MatchCommandGatewayTest> test
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#<concede-case> test
./mvnw -q -DskipTests compile
git diff --check
```

若現有 concede 測試名稱不同，先搜尋實際方法，不可填造名稱後宣稱已執行。

## 九、回滾

- controller 改回呼叫 `MatchActionService.concede(...)`。
- 刪除尚未被其他 action 使用的新 gateway/handler。
- 無 DB/API migration，回滾不需資料處理。

## 十、完成回報

列出：

- 新增 command 型別。
- controller diff。
- impact/detect changes 摘要。
- 測試命令與結果。
- 下一個適合遷移的 action 建議，但不要在本工作包順便實作。
