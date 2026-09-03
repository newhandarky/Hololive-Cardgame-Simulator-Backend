# AGENTS.md

## 後端入口規範

此目錄是 Spring Boot / Java 17 / Maven 後端 repository。代理人進入後端工作時，應把本檔視為第一入口，優先維持遊戲規則正確性、既有對戰流程相容性，以及可分段驗證的重構節奏。

## 溝通與工作節奏

- 回覆使用繁體中文，技術名詞、指令、檔案路徑可保留英文。
- commit 訊息使用繁體中文。
- 修改前先執行 `git status --short --branch`，確認目前工作區狀態。
- 不要回退使用者或其他工作中的變更。
- 一般後端工作以自動完成為預設；涉及資料庫 migration、公開 API 契約、核心對戰規則、安全/登入流程時，先說明影響面、回滾方式與驗證方式。
- 若做到一個適合 commit 的段落，回報時同時提供繁體中文 commit 訊息。

## 必讀文件

依任務範圍優先閱讀下列文件，不要只憑檔名或片段推測：

- 重構總覽：`doc/系統優化/00-系統優化總覽.md`
- 重構進度：`doc/系統優化/05-重構進度追蹤.md`
- 完整歷史：`doc/系統優化/archive/05-重構進度追蹤-完整歷史-2026-05-25.md`，只在需要追溯舊批次細節時閱讀。
- MatchEffect 拆分：`doc/系統優化/01-MatchEffectService拆分路線圖.md`
- MatchAction 拆分：`doc/系統優化/02-MatchActionService拆分路線圖.md`
- REST API：`doc/0212-REST API 契約規範（最小集）.md`
- WebSocket：`doc/0212-WebSocket 通訊規範.md`
- 對戰引擎：`doc/0212-對戰引擎規格文件.md`
- 文件索引：`doc/文件索引與維護規範.md`

目前後端優化方向以 `00-系統優化總覽.md` 與 `05-重構進度追蹤.md` 為主入口；舊 acceptance review 與完整歷史只作為追溯依據。若文件與程式碼不一致，先以現有測試與實際程式行為為準，並在回報中指出文件落差。

## 重構熱點

- `MatchEffectService`、`MatchActionService`、大型 integration test 是主要重構熱點。
- 拆分大型 service 時採小步驟：先用測試鎖定既有行為，再抽出 helper/service，最後更新 `doc/系統優化/`。
- 優先抽出純邏輯、摘要建構、候選清單建構、條件解析、payload 組裝等低副作用區塊。
- 避免一次混合大量重構、行為修改與格式化，除非使用者明確要求。
- 若發現既有測試失敗但與本次變更無直接關係，需保留證據並在回報中說明。

## 後端變更流程

- 先用 `rg` 或精準檔案讀取定位定義、呼叫點與相關測試。
- 宣稱 bug 或行為差異前，需讀到定義、使用處與相關注入/呼叫鏈。
- 行為變更優先新增或調整聚焦測試；重構則需保留既有測試語意。
- 文件更新放在 `doc/系統優化/`，並同步更新總覽與進度追蹤。
- 不要修改與任務無關的 migration、seed data、格式或命名。

## 驗證矩陣

- 文件或註解變更：`git diff --check`
- 一般 Java 編譯檢查：`./mvnw -q -DskipTests compile`
- 聚焦單元測試：`./mvnw -q -Dtest=ClassName test`
- 聚焦 integration test：`./mvnw -q -Dtest=MatchActionServiceIntegrationTest#methodName test`
- 完整測試較耗時且可能需要 Docker / Testcontainers；若沙盒權限不足，依使用者授權提高權限重跑。

回報時列出實際執行過的指令與結果；未執行的驗證需說明原因。

## Commit 規則

- 一個 commit 只包含一個清楚段落，例如「抽 helper + 對應測試 + 文件紀錄」。
- 不要把前端變更提交到後端 repo。
- 建議訊息：
  - `後端：抽出卡牌選擇摘要建構器`
  - `後端：補強搜尋效果重構測試`
  - `文件：完善後端代理人協作規範`
  - `文件：更新後端重構進度`

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **Hololive-Cardgame-Simulator-Backend** (7338 symbols, 19363 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/Hololive-Cardgame-Simulator-Backend/context` | Codebase overview, check index freshness |
| `gitnexus://repo/Hololive-Cardgame-Simulator-Backend/clusters` | All functional areas |
| `gitnexus://repo/Hololive-Cardgame-Simulator-Backend/processes` | All execution flows |
| `gitnexus://repo/Hololive-Cardgame-Simulator-Backend/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
