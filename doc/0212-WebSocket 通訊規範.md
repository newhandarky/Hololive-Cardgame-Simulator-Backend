# WebSocket 通訊規範

## 概述

本文件定義 HOLOLIVE 卡牌遊戲的即時通訊規範，採用 **WebSocket 單房間頻道模式**，用於雙向事件推送與對戰狀態同步。

---

## 1. 通訊模式選擇

### 1.1 為什麼選擇 WebSocket？

| 方案 | 優點 | 缺點 | 適用情境 |
|-----|------|------|---------|
| **輪詢（Polling）** | 簡單、無需額外配置 | 延遲高、浪費頻寬 | MVP 初期測試 |
| **長輪詢（Long Polling）** | 比輪詢即時 | 連線數高、伺服器壓力大 | 不適合 |
| **Server-Sent Events（SSE）** | 單向推送簡單 | 只能單向、不支援二進制 | 不適合互動遊戲 |
| **WebSocket** ✅ | 雙向、低延遲、全雙工 | 需要額外配置、連線管理 | **即時對戰遊戲** |

**結論**：採用 **WebSocket**，理由：
- 雙向通訊：玩家動作 → 伺服器、伺服器 → 對手
- 低延遲：即時反應（< 100ms）
- 全雙工：同時收發訊息

---

### 1.2 頻道模式：單房間頻道

每個對戰房間有一個獨立的 WebSocket 頻道：

```
/ws/match/{matchId}
```

**運作方式**：
- 玩家 A 連線到 `/ws/match/42`
- 玩家 B 連線到 `/ws/match/42`
- 伺服器維護該房間的所有連線
- 任何玩家的動作 → 伺服器驗證 → 推送給房間內所有玩家

**優點**：
- 簡單：一個房間一個頻道
- 易擴展：未來可加入觀戰者
- 隔離：不同房間互不影響

---

## 2. 連線生命週期

### 2.1 連線流程

```
前端                          後端
  |                            |
  |-- CONNECT /ws/match/42 --->|
  |<-- CONNECT_ACK ------------|  (連線成功)
  |                            |
  |-- SUBSCRIBE {matchId} ---->|
  |<-- SUBSCRIBE_ACK ----------|  (訂閱成功，回傳當前狀態)
  |                            |
  |<-- HEARTBEAT_PING ---------|  (每 30 秒)
  |-- HEARTBEAT_PONG --------->|
  |                            |
  |-- ACTION {type, payload} ->|  (玩家動作)
  |<-- ACTION_RESULT ----------|  (動作結果)
  |<-- STATE_UPDATE -----------|  (推送給對手)
  |                            |
  |-- DISCONNECT ------------->|  (主動斷線)
  |<-- DISCONNECT_ACK ---------|
```

---

### 2.2 訂閱（Subscribe）

當前端連線成功後，必須先訂閱房間：

#### 前端發送

```json
{
  "type": "SUBSCRIBE",
  "matchId": 42,
  "userId": 101,
  "token": "jwt_token_here"
}
```

#### 後端回應（成功）

```json
{
  "type": "SUBSCRIBE_ACK",
  "success": true,
  "matchId": 42,
  "currentState": {
    "matchId": 42,
    "status": "ACTIVE",
    "currentPhase": "MAIN_STEP",
    "turnNumber": 3,
    "currentTurnPlayerId": 101,
    "players": { /* ... */ },
    "holomems": [ /* ... */ ],
    "stateVersion": 1234567890
  }
}
```

#### 後端回應（失敗）

```json
{
  "type": "SUBSCRIBE_ERROR",
  "success": false,
  "errorCode": "INVALID_TOKEN",
  "errorMessage": "JWT Token 無效或已過期"
}
```

---

### 2.3 心跳（Heartbeat）

為了偵測斷線，伺服器每 30 秒發送心跳：

#### 後端發送

```json
{
  "type": "HEARTBEAT_PING",
  "timestamp": "2026-02-12T11:50:00Z"
}
```

#### 前端回應

```json
{
  "type": "HEARTBEAT_PONG",
  "timestamp": "2026-02-12T11:50:00Z"
}
```

**規則**：
- 如果 60 秒內沒有收到 PONG，伺服器視為斷線
- 前端應在收到 PING 後 5 秒內回應 PONG

---

### 2.4 重連（Reconnect）

#### 斷線偵測

前端偵測到斷線時（例如：網路中斷、收不到心跳），應該：

1. 顯示「連線中斷，正在重連...」提示
2. 嘗試重新連線（指數退避策略）
3. 重新訂閱房間
4. 接收最新狀態

#### 重連策略（指數退避）

```typescript
const reconnect = (attempt: number) => {
  const delay = Math.min(1000 * Math.pow(2, attempt), 30000); // 最多 30 秒
  setTimeout(() => {
    console.log(`重連嘗試 #${attempt}，延遲 ${delay}ms`);
    connectWebSocket();
  }, delay);
};
```

#### 重連後的狀態同步

```json
{
  "type": "RECONNECT_SUBSCRIBE",
  "matchId": 42,
  "userId": 101,
  "lastStateVersion": 1234567890,  // 前端最後看到的版本
  "token": "jwt_token_here"
}
```

後端回應：
- 若 `lastStateVersion` 過舊，回傳完整狀態
- 若只差幾個動作，回傳增量更新（`stateChanges`）

---

## 3. 訊息格式規範

### 3.1 玩家動作（Action）

#### 前端發送

```json
{
  "type": "ACTION",
  "matchId": 42,
  "userId": 101,
  "action": {
    "actionType": "USE_ART",
    "payload": {
      "holomemId": 501,
      "artId": 201,
      "targetId": 502
    }
  }
}
```

#### 後端回應（成功）

```json
{
  "type": "ACTION_RESULT",
  "success": true,
  "matchId": 42,
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
  ],
  "stateVersion": 1234567891
}
```

#### 後端回應（失敗）

```json
{
  "type": "ACTION_RESULT",
  "success": false,
  "matchId": 42,
  "errorCode": "INSUFFICIENT_CHEER",
  "errorMessage": "該ホロメン的エール不足以支付此アーツ",
  "stateVersion": 1234567890
}
```

---

### 3.2 狀態更新推送（State Update）

當對手執行動作後，伺服器推送給另一方：

```json
{
  "type": "STATE_UPDATE",
  "matchId": 42,
  "actionId": "act_123456",
  "userId": 101,  // 執行動作的玩家
  "actionType": "USE_ART",
  "stateChanges": [
    {
      "type": "DAMAGE_DEALT",
      "targetId": 502,
      "damage": 50,
      "newDamageTaken": 50
    }
  ],
  "stateVersion": 1234567891
}
```

---

### 3.3 回合切換推送（Turn Change）

```json
{
  "type": "TURN_CHANGE",
  "matchId": 42,
  "fromUserId": 101,
  "toUserId": 102,
  "turnNumber": 4,
  "currentPhase": "RESET_STEP",
  "stateVersion": 1234567892
}
```

---

### 3.4 對戰結束推送（Match End）

```json
{
  "type": "MATCH_END",
  "matchId": 42,
  "status": "FINISHED",
  "winnerId": 101,
  "loserId": 102,
  "reason": "LIFE_ZERO",
  "stateVersion": 1234567900
}
```

---

## 4. 節流與限流規則

### 4.1 動作頻率限制（Rate Limiting）

為了防止惡意操作或誤操作，限制動作頻率：

| 動作類型 | 頻率限制 | 說明 |
|---------|---------|------|
| 一般動作（出場、使用アーツ等） | 10 次 / 秒 | 防止誤點或腳本攻擊 |
| 心跳回應（PONG） | 不限制 | 系統自動處理 |
| 訂閱請求（SUBSCRIBE） | 5 次 / 分鐘 | 防止重複訂閱 |

#### 超過限制時的回應

```json
{
  "type": "RATE_LIMIT_EXCEEDED",
  "errorCode": "TOO_MANY_ACTIONS",
  "errorMessage": "操作過於頻繁，請稍後再試",
  "retryAfter": 5  // 秒
}
```

---

### 4.2 訊息大小限制

- 單一訊息最大：**64 KB**
- 若超過，回傳錯誤：

```json
{
  "type": "MESSAGE_TOO_LARGE",
  "errorCode": "PAYLOAD_TOO_LARGE",
  "errorMessage": "訊息大小超過限制（64 KB）"
}
```

---

### 4.3 連線數量限制

- 單一玩家在同一房間：最多 **2 個連線**（例如：電腦 + 手機）
- 超過時，踢掉最舊的連線

---

## 5. 錯誤處理

### 5.1 錯誤碼表

| 錯誤碼 | 說明 | 處理方式 |
|-------|------|---------|
| `INVALID_TOKEN` | JWT Token 無效或過期 | 前端重新登入 |
| `MATCH_NOT_FOUND` | 對戰不存在 | 返回首頁 |
| `NOT_YOUR_TURN` | 不是你的回合 | 顯示提示，禁用操作 |
| `INVALID_PHASE` | 當前階段不允許此動作 | 顯示提示 |
| `INSUFFICIENT_CHEER` | エール 不足 | 顯示提示 |
| `INSUFFICIENT_HOLOPOWER` | ホロパワー 不足 | 顯示提示 |
| `HOLOMEM_NOT_FOUND` | ホロメン 不存在或不在場上 | 重新整理狀態 |
| `TARGET_INVALID` | 目標不合法 | 顯示提示 |
| `LOCK_CONFLICT` | 併發衝突 | 自動重試 |
| `RATE_LIMIT_EXCEEDED` | 操作過於頻繁 | 等待後重試 |
| `CONNECTION_LOST` | 連線中斷 | 自動重連 |

---

### 5.2 前端錯誤處理範例

```typescript
const handleActionResult = (message: ActionResult) => {
  if (message.success) {
    // 成功：套用狀態變更
    applyStateChanges(message.stateChanges);
  } else {
    // 失敗：顯示錯誤訊息
    switch (message.errorCode) {
      case 'NOT_YOUR_TURN':
        showToast('還沒輪到你！');
        break;
      case 'INSUFFICIENT_CHEER':
        showToast('エール 不足，無法使用此アーツ');
        break;
      case 'LOCK_CONFLICT':
        // 自動重試
        retryAction();
        break;
      default:
        showToast(message.errorMessage);
    }
  }
};
```

---

## 6. 後端實作建議（Spring Boot + WebSocket）

### 6.1 WebSocket 配置

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 啟用簡單訊息代理
        config.enableSimpleBroker("/topic", "/queue");
        // 應用程式目的地前綴
        config.setApplicationDestinationPrefixes("/app");
    }
    
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/match")
                .setAllowedOriginPatterns("*")
                .withSockJS();  // 支援 SockJS fallback
    }
}
```

---

### 6.2 訊息處理器

```java
@Controller
public class MatchWebSocketController {
    
    @Autowired
    private MatchEngine matchEngine;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    /**
     * 訂閱房間
     */
    @MessageMapping("/match/{matchId}/subscribe")
    @SendToUser("/queue/subscribe-ack")
    public SubscribeAck subscribe(@DestinationVariable Long matchId, 
                                    @Payload SubscribeRequest request,
                                    Principal principal) {
        // 驗證 token、權限等
        // ...
        
        // 取得當前對戰狀態
        MatchState state = matchEngine.getMatchState(matchId);
        
        return new SubscribeAck(true, matchId, state);
    }
    
    /**
     * 執行動作
     */
    @MessageMapping("/match/{matchId}/action")
    public void action(@DestinationVariable Long matchId,
                       @Payload ActionRequest request,
                       Principal principal) {
        // 執行動作
        ActionResult result = matchEngine.executeAction(matchId, request.getAction());
        
        // 回傳給執行者
        messagingTemplate.convertAndSendToUser(
            principal.getName(),
            "/queue/action-result",
            result
        );
        
        // 若成功，推送給房間內所有人
        if (result.isSuccess()) {
            messagingTemplate.convertAndSend(
                "/topic/match/" + matchId + "/state-update",
                new StateUpdate(matchId, result.getStateChanges())
            );
        }
    }
}
```

---

### 6.3 心跳機制

```java
@Component
public class HeartbeatScheduler {
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Scheduled(fixedRate = 30000)  // 每 30 秒
    public void sendHeartbeat() {
        // 向所有連線的客戶端發送心跳
        messagingTemplate.convertAndSend(
            "/topic/heartbeat",
            new HeartbeatPing(Instant.now())
        );
    }
}
```

---

## 7. 前端實作建議（React + STOMP）

### 7.1 WebSocket Hook

```typescript
import { useEffect, useRef, useState } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

export const useMatchWebSocket = (matchId: number, token: string) => {
  const [isConnected, setIsConnected] = useState(false);
  const [matchState, setMatchState] = useState<MatchState | null>(null);
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    // 建立 STOMP 客戶端
    const client = new Client({
      webSocketFactory: () => new SockJS('http://localhost:8080/ws/match'),
      connectHeaders: {
        Authorization: `Bearer ${token}`,
      },
      onConnect: () => {
        console.log('WebSocket 連線成功');
        setIsConnected(true);

        // 訂閱房間
        client.subscribe(`/topic/match/${matchId}/state-update`, (message: IMessage) => {
          const update = JSON.parse(message.body);
          handleStateUpdate(update);
        });

        client.subscribe(`/user/queue/action-result`, (message: IMessage) => {
          const result = JSON.parse(message.body);
          handleActionResult(result);
        });

        // 發送訂閱請求
        client.publish({
          destination: `/app/match/${matchId}/subscribe`,
          body: JSON.stringify({ matchId, token }),
        });
      },
      onDisconnect: () => {
        console.log('WebSocket 斷線');
        setIsConnected(false);
      },
    });

    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
    };
  }, [matchId, token]);

  const sendAction = (action: Action) => {
    if (clientRef.current && isConnected) {
      clientRef.current.publish({
        destination: `/app/match/${matchId}/action`,
        body: JSON.stringify({ action }),
      });
    }
  };

  return { isConnected, matchState, sendAction };
};
```

---

## 8. 測試建議

### 8.1 連線測試

```typescript
test('WebSocket 應該能成功連線並訂閱房間', async () => {
  const { isConnected, matchState } = useMatchWebSocket(42, 'valid_token');
  
  await waitFor(() => expect(isConnected).toBe(true));
  expect(matchState).not.toBeNull();
});
```

### 8.2 動作測試

```typescript
test('應該能發送動作並收到結果', async () => {
  const { sendAction } = useMatchWebSocket(42, 'valid_token');
  
  sendAction({
    actionType: 'USE_ART',
    payload: { holomemId: 501, artId: 201, targetId: 502 },
  });
  
  // 驗證收到 ACTION_RESULT
  await waitFor(() => {
    expect(mockActionResult).toHaveBeenCalled();
  });
});
```

### 8.3 斷線重連測試

```typescript
test('斷線後應該自動重連', async () => {
  const { isConnected } = useMatchWebSocket(42, 'valid_token');
  
  // 模擬斷線
  mockWebSocket.close();
  
  await waitFor(() => expect(isConnected).toBe(false));
  
  // 等待重連
  await waitFor(() => expect(isConnected).toBe(true), { timeout: 5000 });
});
```

---

## 總結

本文件定義了 WebSocket 通訊的完整規範，包括：

1. **連線生命週期**：訂閱、心跳、重連
2. **訊息格式**：動作、狀態更新、回合切換、對戰結束
3. **節流限流**：防止惡意操作
4. **錯誤處理**：完整的錯誤碼表
5. **實作建議**：Spring Boot + React 範例

遵循本規範可確保即時通訊的穩定性與效能。
