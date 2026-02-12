# REST API 契約規範（最小集）

## 概述

本文件定義 HOLOLIVE 卡牌遊戲的 REST API 最小集合，用於對戰房間管理與基本查詢。**即時動作應透過 WebSocket 進行**。

---

## 1. API 設計原則

### 1.1 RESTful 規範

- 使用 HTTP 動詞：`GET`（查詢）、`POST`（建立／執行）、`PUT`（更新）、`DELETE`（刪除）
- 使用名詞作為資源路徑：`/api/matches`、`/api/users`
- 使用複數形式：`/matches` 而非 `/match`
- 版本控制：`/api/v1/matches`（可選，初期可省略）

---

### 1.2 回應格式統一

所有 API 回應都遵循以下格式：

#### 成功回應

```json
{
  "success": true,
  "data": { /* 實際資料 */ },
  "stateVersion": 1234567890,  // 狀態版本（用於同步）
  "timestamp": "2026-02-12T11:50:00Z"
}
```

#### 失敗回應

```json
{
  "success": false,
  "errorCode": "ERROR_CODE",
  "errorMessage": "錯誤訊息",
  "stateVersion": null,
  "timestamp": "2026-02-12T11:50:00Z"
}
```

---

## 2. 核心 API 端點（最小集）

### 2.1 健康檢查

#### `GET /api/health`

檢查伺服器是否正常運行。

**請求**：無需參數

**回應**：
```json
{
  "success": true,
  "data": {
    "status": "UP",
    "timestamp": "2026-02-12T11:50:00Z"
  }
}
```

---

### 2.2 建立對戰

#### `POST /api/matches/create`

建立新的對戰房間。

**請求 Header**：
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**請求 Body**：
```json
{
  "oshiCardId": "OSHI-001",
  "deckCardIds": [
    "HLM-001", "HLM-002", "HLM-003",
    // ... 共 50 張
  ]
}
```

**回應（成功）**：
```json
{
  "success": true,
  "data": {
    "matchId": 42,
    "roomCode": "ABC123",
    "status": "WAITING",
    "creatorUserId": 101,
    "createdAt": "2026-02-12T11:50:00Z"
  },
  "stateVersion": 1234567890,
  "timestamp": "2026-02-12T11:50:00Z"
}
```

**回應（失敗）**：
```json
{
  "success": false,
  "errorCode": "INVALID_DECK",
  "errorMessage": "牌組必須包含 50 張卡片",
  "timestamp": "2026-02-12T11:50:00Z"
}
```

---

### 2.3 加入對戰

#### `POST /api/matches/join`

使用房間代碼加入對戰。

**請求 Header**：
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**請求 Body**：
```json
{
  "roomCode": "ABC123",
  "oshiCardId": "OSHI-002",
  "deckCardIds": [
    "HLM-010", "HLM-011", "HLM-012",
    // ... 共 50 張
  ]
}
```

**回應（成功）**：
```json
{
  "success": true,
  "data": {
    "matchId": 42,
    "status": "INITIALIZING",
    "playerA": {
      "userId": 101,
      "displayName": "玩家A",
      "oshiCardId": "OSHI-001"
    },
    "playerB": {
      "userId": 102,
      "displayName": "玩家B",
      "oshiCardId": "OSHI-002"
    }
  },
  "stateVersion": 1234567891,
  "timestamp": "2026-02-12T11:50:30Z"
}
```

**回應（失敗）**：
```json
{
  "success": false,
  "errorCode": "ROOM_NOT_FOUND",
  "errorMessage": "找不到房間代碼 ABC123",
  "timestamp": "2026-02-12T11:50:30Z"
}
```

---

### 2.4 取得對戰狀態

#### `GET /api/matches/{matchId}`

取得對戰的完整狀態（用於初次載入或斷線重連）。

**請求 Header**：
```
Authorization: Bearer {jwt_token}
```

**回應（成功）**：
```json
{
  "success": true,
  "data": {
    "matchId": 42,
    "status": "ACTIVE",
    "currentPhase": "MAIN_STEP",
    "turnNumber": 3,
    "currentTurnPlayerId": 101,
    "players": {
      "101": {
        "userId": 101,
        "displayName": "玩家A",
        "oshiCardId": "OSHI-001",
        "currentLife": 4,
        "handCount": 5,
        "deckCount": 35,
        "holopowerCount": 3,
        "cheerDeckCount": 20
      },
      "102": {
        "userId": 102,
        "displayName": "玩家B",
        "oshiCardId": "OSHI-002",
        "currentLife": 5,
        "handCount": 6,
        "deckCount": 38,
        "holopowerCount": 2,
        "cheerDeckCount": 22
      }
    },
    "holomems": [
      {
        "holomemId": 501,
        "ownerId": 101,
        "cardId": "HLM-001",
        "zone": "CENTER",
        "isRested": false,
        "damageTaken": 20,
        "currentLevel": "DEBUT",
        "attachedCheerCount": 3
      },
      // ... 其他場上ホロメン
    ]
  },
  "stateVersion": 1234567895,
  "timestamp": "2026-02-12T11:55:00Z"
}
```

**回應（失敗）**：
```json
{
  "success": false,
  "errorCode": "MATCH_NOT_FOUND",
  "errorMessage": "找不到對戰 ID 42",
  "timestamp": "2026-02-12T11:55:00Z"
}
```

---

### 2.5 執行動作（備用，建議用 WebSocket）

#### `POST /api/matches/{matchId}/action`

⚠️ **不建議使用**：即時動作應透過 WebSocket 執行，此端點僅作為備用（例如：WebSocket 連線失敗時）。

**請求 Header**：
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**請求 Body**：
```json
{
  "actionType": "USE_ART",
  "payload": {
    "holomemId": 501,
    "artId": 201,
    "targetId": 502
  }
}
```

**回應（成功）**：
```json
{
  "success": true,
  "data": {
    "actionId": "act_123456",
    "stateChanges": [
      {
        "type": "CHEER_CONSUMED",
        "holomemId": 501,
        "cheerIds": [301, 302]
      },
      {
        "type": "HOLOMEM_SET_RESTED",
        "holomemId": 501,
        "isRested": true
      },
      {
        "type": "DAMAGE_DEALT",
        "targetId": 502,
        "damage": 50,
        "newDamageTaken": 50
      }
    ]
  },
  "stateVersion": 1234567896,
  "timestamp": "2026-02-12T11:56:00Z"
}
```

**回應（失敗）**：
```json
{
  "success": false,
  "errorCode": "INSUFFICIENT_CHEER",
  "errorMessage": "該ホロメン的エール不足以支付此アーツ",
  "stateVersion": 1234567895,
  "timestamp": "2026-02-12T11:56:00Z"
}
```

---

### 2.6 投降

#### `POST /api/matches/{matchId}/surrender`

主動投降。

**請求 Header**：
```
Authorization: Bearer {jwt_token}
```

**回應（成功）**：
```json
{
  "success": true,
  "data": {
    "matchId": 42,
    "status": "FINISHED",
    "winnerId": 102,
    "loserId": 101,
    "reason": "SURRENDER"
  },
  "stateVersion": 1234567900,
  "timestamp": "2026-02-12T11:58:00Z"
}
```

---

### 2.7 取得動作歷史（斷線重連用）

#### `GET /api/matches/{matchId}/actions`

取得對戰的所有動作記錄，用於斷線重連後的狀態重建。

**請求 Header**：
```
Authorization: Bearer {jwt_token}
```

**查詢參數**（可選）：
- `fromVersion`：從哪個版本開始（用於增量更新）
- `limit`：最多回傳幾筆（預設 100）

**範例**：
```
GET /api/matches/42/actions?fromVersion=1234567890&limit=50
```

**回應（成功）**：
```json
{
  "success": true,
  "data": {
    "matchId": 42,
    "actions": [
      {
        "actionId": "act_001",
        "turnNumber": 1,
        "actionOrder": 1,
        "userId": 101,
        "actionType": "PLAY_MEMBER",
        "payload": { "cardId": "HLM-001", "zone": "CENTER" },
        "stateChanges": [
          { "type": "CARD_ZONE_CHANGED", "cardId": "HLM-001", "fromZone": "HAND", "toZone": "STAGE" }
        ],
        "stateVersion": 1234567891,
        "timestamp": "2026-02-12T11:50:35Z"
      },
      // ... 更多動作
    ],
    "totalCount": 45,
    "hasMore": false
  },
  "stateVersion": 1234567895,
  "timestamp": "2026-02-12T11:59:00Z"
}
```

---

## 3. 錯誤碼完整清單

### 3.1 通用錯誤碼

| 錯誤碼 | HTTP 狀態 | 說明 | 前端處理建議 |
|-------|----------|------|-------------|
| `INVALID_TOKEN` | 401 | JWT Token 無效或過期 | 重新登入 |
| `UNAUTHORIZED` | 403 | 無權限存取此資源 | 顯示錯誤訊息 |
| `NOT_FOUND` | 404 | 資源不存在 | 返回首頁 |
| `INTERNAL_ERROR` | 500 | 伺服器內部錯誤 | 顯示通用錯誤訊息 |
| `RATE_LIMIT_EXCEEDED` | 429 | 請求過於頻繁 | 等待後重試 |

---

### 3.2 對戰相關錯誤碼

| 錯誤碼 | HTTP 狀態 | 說明 | 前端處理建議 |
|-------|----------|------|-------------|
| `MATCH_NOT_FOUND` | 404 | 對戰不存在 | 返回首頁 |
| `ROOM_NOT_FOUND` | 404 | 房間代碼不存在 | 顯示提示 |
| `ROOM_FULL` | 400 | 房間已滿 | 顯示提示 |
| `INVALID_DECK` | 400 | 牌組不合法（數量錯誤） | 顯示錯誤訊息 |
| `MATCH_ALREADY_STARTED` | 400 | 對戰已開始，無法加入 | 返回首頁 |
| `MATCH_FINISHED` | 400 | 對戰已結束 | 顯示結果 |

---

### 3.3 動作相關錯誤碼

| 錯誤碼 | HTTP 狀態 | 說明 | 前端處理建議 |
|-------|----------|------|-------------|
| `NOT_YOUR_TURN` | 400 | 不是你的回合 | 禁用操作 |
| `INVALID_PHASE` | 400 | 當前階段不允許此動作 | 顯示提示 |
| `INSUFFICIENT_CHEER` | 400 | エール 不足 | 顯示提示 |
| `INSUFFICIENT_HOLOPOWER` | 400 | ホロパワー 不足 | 顯示提示 |
| `HOLOMEM_NOT_FOUND` | 400 | ホロメン 不存在或不在場上 | 重新整理狀態 |
| `TARGET_INVALID` | 400 | 目標不合法 | 顯示提示 |
| `CARD_NOT_IN_HAND` | 400 | 卡片不在手牌中 | 重新整理狀態 |
| `ZONE_FULL` | 400 | 位置已滿（例如：センター已有ホロメン） | 顯示提示 |
| `LOCK_CONFLICT` | 409 | 併發衝突，請重試 | 自動重試 |

---

### 3.4 驗證相關錯誤碼

| 錯誤碼 | HTTP 狀態 | 說明 | 前端處理建議 |
|-------|----------|------|-------------|
| `VALIDATION_ERROR` | 400 | 請求參數驗證失敗 | 顯示錯誤訊息 |
| `MISSING_REQUIRED_FIELD` | 400 | 缺少必填欄位 | 檢查請求格式 |
| `INVALID_FIELD_VALUE` | 400 | 欄位值不合法 | 檢查請求格式 |

---

## 4. 狀態版本（State Version）機制

### 4.1 概念

每次對戰狀態變更時，`stateVersion` 遞增，用於：
- **樂觀鎖**：避免併發衝突
- **增量更新**：只取得變更的部分
- **斷線重連**：判斷是否需要重建狀態

---

### 4.2 使用範例

#### 前端快取狀態版本

```typescript
let currentStateVersion = 1234567890;

// 收到狀態更新時
const handleStateUpdate = (update: StateUpdate) => {
  if (update.stateVersion > currentStateVersion) {
    // 套用更新
    applyStateChanges(update.stateChanges);
    currentStateVersion = update.stateVersion;
  } else {
    // 版本過舊，忽略（可能是網路延遲導致的重複訊息）
    console.warn('收到過舊的狀態更新，忽略');
  }
};
```

#### 斷線重連時的增量更新

```typescript
// 重連後，檢查是否需要更新
const reconnect = async () => {
  const response = await api.get(`/api/matches/${matchId}/actions`, {
    params: { fromVersion: currentStateVersion }
  });
  
  if (response.data.actions.length > 0) {
    // 有新動作，套用
    response.data.actions.forEach(action => {
      applyStateChanges(action.stateChanges);
    });
    currentStateVersion = response.data.stateVersion;
  }
};
```

---

## 5. API 實作建議（Spring Boot）

### 5.1 Controller 範例

```java
@RestController
@RequestMapping("/api/matches")
public class MatchController {
    
    @Autowired
    private MatchService matchService;
    
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<MatchCreateResult>> createMatch(
        @RequestBody CreateMatchRequest request,
        @AuthenticationPrincipal Long userId
    ) {
        try {
            MatchCreateResult result = matchService.createMatch(userId, request);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (ValidationException e) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_DECK", e.getMessage()));
        }
    }
    
    @PostMapping("/join")
    public ResponseEntity<ApiResponse<MatchJoinResult>> joinMatch(
        @RequestBody JoinMatchRequest request,
        @AuthenticationPrincipal Long userId
    ) {
        try {
            MatchJoinResult result = matchService.joinMatch(userId, request);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (MatchNotFoundException e) {
            return ResponseEntity.status(404)
                .body(ApiResponse.error("ROOM_NOT_FOUND", e.getMessage()));
        }
    }
    
    @GetMapping("/{matchId}")
    public ResponseEntity<ApiResponse<MatchState>> getMatchState(
        @PathVariable Long matchId,
        @AuthenticationPrincipal Long userId
    ) {
        try {
            MatchState state = matchService.getMatchState(matchId, userId);
            return ResponseEntity.ok(ApiResponse.success(state));
        } catch (MatchNotFoundException e) {
            return ResponseEntity.status(404)
                .body(ApiResponse.error("MATCH_NOT_FOUND", e.getMessage()));
        }
    }
}
```

---

### 5.2 統一回應格式

```java
@Data
@AllArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private T data;
    private String errorCode;
    private String errorMessage;
    private Long stateVersion;
    private Instant timestamp;
    
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
            true, data, null, null, 
            System.currentTimeMillis(), Instant.now()
        );
    }
    
    public static <T> ApiResponse<T> error(String errorCode, String errorMessage) {
        return new ApiResponse<>(
            false, null, errorCode, errorMessage, 
            null, Instant.now()
        );
    }
}
```

---

### 5.3 全域異常處理

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(MatchNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleMatchNotFound(MatchNotFoundException e) {
        return ResponseEntity.status(404)
            .body(ApiResponse.error("MATCH_NOT_FOUND", e.getMessage()));
    }
    
    @ExceptionHandler(InvalidActionException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidAction(InvalidActionException e) {
        return ResponseEntity.badRequest()
            .body(ApiResponse.error(e.getErrorCode(), e.getMessage()));
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericError(Exception e) {
        return ResponseEntity.status(500)
            .body(ApiResponse.error("INTERNAL_ERROR", "伺服器內部錯誤"));
    }
}
```

---

## 6. 前端 API 封裝建議

```typescript
// src/services/api.ts
import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL;

export const api = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
});

// 自動帶上 token
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('authToken');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// 統一錯誤處理
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      // Token 過期，重新登入
      localStorage.removeItem('authToken');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

// API 方法
export const createMatch = async (oshiCardId: string, deckCardIds: string[]) => {
  const response = await api.post('/matches/create', { oshiCardId, deckCardIds });
  return response.data;
};

export const joinMatch = async (roomCode: string, oshiCardId: string, deckCardIds: string[]) => {
  const response = await api.post('/matches/join', { roomCode, oshiCardId, deckCardIds });
  return response.data;
};

export const getMatchState = async (matchId: number) => {
  const response = await api.get(`/matches/${matchId}`);
  return response.data;
};

export const getMatchActions = async (matchId: number, fromVersion?: number) => {
  const response = await api.get(`/matches/${matchId}/actions`, {
    params: { fromVersion }
  });
  return response.data;
};
```

---

## 總結

本文件定義了 REST API 的最小集合，包括：

1. **核心端點**：建立對戰、加入對戰、取得狀態、執行動作（備用）
2. **統一回應格式**：包含 `success`、`data`、`errorCode`、`stateVersion`
3. **完整錯誤碼表**：涵蓋所有可能的錯誤情境
4. **狀態版本機制**：用於樂觀鎖與增量更新
5. **實作建議**：Spring Boot + React 範例

遵循本規範可確保 API 的一致性與可維護性。
