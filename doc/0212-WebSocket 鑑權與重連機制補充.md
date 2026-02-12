# WebSocket 鑑權與重連機制補充

## 概述

本文件補充 WebSocket 連線的鑑權機制、重連 token 策略，以及相關錯誤處理，確保連線安全性與斷線重連的順暢度。

---

## 1. WebSocket 連線鑑權

### 1.1 鑑權方式選擇

WebSocket 連線有兩種常見鑑權方式：

| 方式 | 優點 | 缺點 | 建議 |
|-----|------|------|------|
| **Authorization Header** | 標準、安全 | 部分瀏覽器不支援 WebSocket Header | ✅ 優先使用 |
| **Query Token** | 相容性高 | Token 可能出現在日誌中 | 備用方案 |

**結論**：優先使用 **Authorization Header**，若不支援則降級使用 **Query Token**。

---

### 1.2 方式一：Authorization Header（推薦）

#### 前端實作

```typescript
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const connectWebSocket = (matchId: number, token: string) => {
  const client = new Client({
    webSocketFactory: () => new SockJS('http://localhost:8080/ws/match'),
    
    // ⚠️ 在 connectHeaders 中帶上 JWT Token
    connectHeaders: {
      Authorization: `Bearer ${token}`,
    },
    
    onConnect: () => {
      console.log('WebSocket 連線成功');
      
      // 訂閱房間
      client.subscribe(`/topic/match/${matchId}/state-update`, (message) => {
        // 處理訊息
      });
    },
    
    onStompError: (frame) => {
      console.error('STOMP 錯誤:', frame);
      handleAuthError(frame);
    },
  });
  
  client.activate();
  return client;
};
```

#### 後端驗證（Spring Boot）

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/match")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new WebSocketAuthInterceptor(jwtTokenProvider))
                .withSockJS();
    }
}

// WebSocket 鑑權攔截器
public class WebSocketAuthInterceptor implements HandshakeInterceptor {
    
    private final JwtTokenProvider jwtTokenProvider;
    
    public WebSocketAuthInterceptor(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }
    
    @Override
    public boolean beforeHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Map<String, Object> attributes
    ) throws Exception {
        
        // 從 Header 取得 Token
        String token = extractToken(request);
        
        if (token == null || !jwtTokenProvider.validateToken(token)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;  // 拒絕連線
        }
        
        // 驗證通過，將 userId 存到 attributes
        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        attributes.put("userId", userId);
        
        return true;
    }
    
    private String extractToken(ServerHttpRequest request) {
        // 從 Authorization Header 取得
        List<String> authHeaders = request.getHeaders().get("Authorization");
        if (authHeaders != null && !authHeaders.isEmpty()) {
            String authHeader = authHeaders.get(0);
            if (authHeader.startsWith("Bearer ")) {
                return authHeader.substring(7);
            }
        }
        return null;
    }
}
```

---

### 1.3 方式二：Query Token（備用）

若瀏覽器不支援 WebSocket Header（例如：舊版 Safari），可使用 Query Token：

#### 前端實作

```typescript
const connectWebSocket = (matchId: number, token: string) => {
  const client = new Client({
    // ⚠️ 將 token 放在 URL 中
    webSocketFactory: () => new SockJS(`http://localhost:8080/ws/match?token=${token}`),
    
    onConnect: () => {
      console.log('WebSocket 連線成功');
    },
  });
  
  client.activate();
  return client;
};
```

#### 後端驗證

```java
@Override
public boolean beforeHandshake(
    ServerHttpRequest request,
    ServerHttpResponse response,
    WebSocketHandler wsHandler,
    Map<String, Object> attributes
) throws Exception {
    
    String token = extractToken(request);
    
    // 若 Header 沒有 token，嘗試從 Query 取得
    if (token == null) {
        token = extractTokenFromQuery(request);
    }
    
    if (token == null || !jwtTokenProvider.validateToken(token)) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }
    
    Long userId = jwtTokenProvider.getUserIdFromToken(token);
    attributes.put("userId", userId);
    
    return true;
}

private String extractTokenFromQuery(ServerHttpRequest request) {
    if (request instanceof ServletServerHttpRequest) {
        ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
        return servletRequest.getServletRequest().getParameter("token");
    }
    return null;
}
```

---

## 2. 重連 Token 策略

### 2.1 方案比較

| 方案 | 說明 | 優點 | 缺點 | 建議 |
|-----|------|------|------|------|
| **沿用同一 JWT** | 重連時繼續使用原本的 JWT | 簡單、無需額外邏輯 | JWT 過期後無法重連 | ✅ 推薦（短期對戰） |
| **發臨時 Reconnect Token** | 首次連線後發給前端，重連時使用 | 可控制重連時間窗口 | 實作複雜 | 長期對戰才需要 |
| **Refresh Token** | JWT 過期前自動換發新 Token | 可長時間維持連線 | 需要額外端點 | 可選 |

**結論**：對於「單局對戰時間 < 30 分鐘」的卡牌遊戲，**沿用同一 JWT** 即可。

---

### 2.2 方案一：沿用同一 JWT（推薦）

#### 流程

```
1. 玩家登入 → 取得 JWT（有效期 24 小時）
2. 連線 WebSocket → 使用 JWT 鑑權
3. 斷線 → 前端偵測到斷線
4. 重連 → 使用同一個 JWT 再次連線
5. 若 JWT 已過期 → 401 錯誤 → 前端導向登入頁
```

#### 前端實作

```typescript
const [jwtToken, setJwtToken] = useState<string | null>(null);
const clientRef = useRef<Client | null>(null);
const reconnectAttemptsRef = useRef(0);

const connectWebSocket = useCallback(() => {
  if (!jwtToken) return;
  
  const client = new Client({
    webSocketFactory: () => new SockJS('http://localhost:8080/ws/match'),
    connectHeaders: {
      Authorization: `Bearer ${jwtToken}`,  // ⚠️ 沿用同一個 JWT
    },
    
    onConnect: () => {
      console.log('WebSocket 連線成功');
      reconnectAttemptsRef.current = 0;  // 重置重連次數
    },
    
    onDisconnect: () => {
      console.log('WebSocket 斷線');
      handleReconnect();
    },
    
    onStompError: (frame) => {
      console.error('STOMP 錯誤:', frame);
      
      // 若是 401 錯誤（Token 過期），導向登入頁
      if (frame.headers['message']?.includes('401')) {
        console.error('JWT Token 已過期，請重新登入');
        localStorage.removeItem('authToken');
        window.location.href = '/login';
      }
    },
  });
  
  client.activate();
  clientRef.current = client;
}, [jwtToken]);

const handleReconnect = () => {
  reconnectAttemptsRef.current += 1;
  const delay = Math.min(1000 * Math.pow(2, reconnectAttemptsRef.current), 30000);
  
  console.log(`重連嘗試 #${reconnectAttemptsRef.current}，延遲 ${delay}ms`);
  
  setTimeout(() => {
    if (reconnectAttemptsRef.current <= 5) {
      connectWebSocket();  // 使用同一個 JWT 重連
    } else {
      console.error('重連失敗次數過多，請重新整理頁面');
    }
  }, delay);
};
```

---

### 2.3 方案二：發臨時 Reconnect Token（進階）

若需要更長時間的對戰（例如：1 小時以上），可以在首次連線後發給前端一個臨時 Reconnect Token。

#### 流程

```
1. 玩家登入 → 取得 JWT（有效期 24 小時）
2. 連線 WebSocket → 使用 JWT 鑑權
3. 連線成功 → 後端發送 Reconnect Token（有效期 2 小時）
4. 斷線 → 前端偵測到斷線
5. 重連 → 使用 Reconnect Token 再次連線
6. 重連成功 → 後端再發新的 Reconnect Token
```

#### 後端實作

```java
@Controller
public class MatchWebSocketController {
    
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @MessageMapping("/match/{matchId}/subscribe")
    @SendToUser("/queue/subscribe-ack")
    public SubscribeAck subscribe(
        @DestinationVariable Long matchId,
        @Payload SubscribeRequest request,
        Principal principal
    ) {
        Long userId = Long.parseLong(principal.getName());
        
        // 取得對戰狀態
        MatchState state = matchEngine.getMatchState(matchId);
        
        // ⚠️ 發給前端一個臨時 Reconnect Token（有效期 2 小時）
        String reconnectToken = jwtTokenProvider.generateReconnectToken(userId, matchId, 7200000);
        
        return new SubscribeAck(true, matchId, state, reconnectToken);
    }
}
```

#### 前端實作

```typescript
const [reconnectToken, setReconnectToken] = useState<string | null>(null);

const connectWebSocket = useCallback(() => {
  // 優先使用 Reconnect Token，若沒有則使用 JWT
  const token = reconnectToken || jwtToken;
  
  const client = new Client({
    webSocketFactory: () => new SockJS('http://localhost:8080/ws/match'),
    connectHeaders: {
      Authorization: `Bearer ${token}`,
    },
    
    onConnect: () => {
      console.log('WebSocket 連線成功');
      
      // 訂閱後會收到新的 Reconnect Token
      client.subscribe('/user/queue/subscribe-ack', (message) => {
        const ack = JSON.parse(message.body);
        setReconnectToken(ack.reconnectToken);  // 儲存新 Token
      });
    },
  });
  
  client.activate();
}, [reconnectToken, jwtToken]);
```

---

## 3. 錯誤碼處理

### 3.1 WebSocket 錯誤碼

| 錯誤碼 | HTTP 狀態 | 說明 | 前端處理 |
|-------|----------|------|---------|
| `401 Unauthorized` | 401 | JWT Token 無效或過期 | 導向登入頁 |
| `403 Forbidden` | 403 | 無權限加入此對戰 | 顯示錯誤訊息 |
| `440 Login Timeout` | 440 | Session 過期（長時間未活動） | 導向登入頁 |
| `429 Too Many Requests` | 429 | 連線嘗試過於頻繁 | 延遲後重試 |
| `503 Service Unavailable` | 503 | 伺服器過載 | 顯示「伺服器忙碌」 |

---

### 3.2 前端錯誤處理

```typescript
const handleWebSocketError = (frame: any) => {
  const errorCode = frame.headers['error-code'];
  const message = frame.headers['message'];
  
  switch (errorCode) {
    case '401':
      // JWT Token 過期
      console.error('Token 過期，導向登入頁');
      localStorage.removeItem('authToken');
      window.location.href = '/login';
      break;
      
    case '440':
      // Session 過期
      console.error('Session 過期，導向登入頁');
      localStorage.removeItem('authToken');
      window.location.href = '/login';
      break;
      
    case '403':
      // 無權限
      alert('無權限加入此對戰');
      window.location.href = '/';
      break;
      
    case '429':
      // 重連過於頻繁
      console.warn('重連過於頻繁，等待 10 秒後重試');
      setTimeout(() => connectWebSocket(), 10000);
      break;
      
    case '503':
      // 伺服器過載
      alert('伺服器忙碌中，請稍後再試');
      break;
      
    default:
      console.error('WebSocket 錯誤:', message);
  }
};

const client = new Client({
  // ...
  onStompError: (frame) => {
    handleWebSocketError(frame);
  },
});
```

---

### 3.3 後端錯誤回應

```java
@Override
public boolean beforeHandshake(
    ServerHttpRequest request,
    ServerHttpResponse response,
    WebSocketHandler wsHandler,
    Map<String, Object> attributes
) throws Exception {
    
    String token = extractToken(request);
    
    // 未提供 Token
    if (token == null) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("error-code", "401");
        response.getHeaders().add("message", "未提供 JWT Token");
        return false;
    }
    
    // Token 無效
    if (!jwtTokenProvider.validateToken(token)) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("error-code", "401");
        response.getHeaders().add("message", "JWT Token 無效或已過期");
        return false;
    }
    
    Long userId = jwtTokenProvider.getUserIdFromToken(token);
    attributes.put("userId", userId);
    
    return true;
}
```

---

## 4. 重連流程完整範例

### 4.1 前端完整實作

```typescript
import { useEffect, useRef, useState, useCallback } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

export const useMatchWebSocket = (matchId: number) => {
  const [isConnected, setIsConnected] = useState(false);
  const [matchState, setMatchState] = useState<MatchState | null>(null);
  const clientRef = useRef<Client | null>(null);
  const reconnectAttemptsRef = useRef(0);
  const maxReconnectAttempts = 5;

  // 從 localStorage 取得 JWT Token
  const jwtToken = localStorage.getItem('authToken');

  const connectWebSocket = useCallback(() => {
    if (!jwtToken) {
      console.error('未找到 JWT Token，無法連線 WebSocket');
      return;
    }

    const client = new Client({
      webSocketFactory: () => new SockJS('http://localhost:8080/ws/match'),
      
      connectHeaders: {
        Authorization: `Bearer ${jwtToken}`,
      },
      
      onConnect: () => {
        console.log('✅ WebSocket 連線成功');
        setIsConnected(true);
        reconnectAttemptsRef.current = 0;

        // 訂閱房間
        client.subscribe(`/topic/match/${matchId}/state-update`, (message: IMessage) => {
          const update = JSON.parse(message.body);
          handleStateUpdate(update);
        });

        // 訂閱個人動作結果
        client.subscribe('/user/queue/action-result', (message: IMessage) => {
          const result = JSON.parse(message.body);
          handleActionResult(result);
        });

        // 發送訂閱請求
        client.publish({
          destination: `/app/match/${matchId}/subscribe`,
          body: JSON.stringify({ matchId }),
        });
      },
      
      onDisconnect: () => {
        console.log('⚠️ WebSocket 斷線');
        setIsConnected(false);
        handleReconnect();
      },
      
      onStompError: (frame) => {
        console.error('❌ STOMP 錯誤:', frame);
        
        const errorCode = frame.headers['error-code'];
        
        if (errorCode === '401' || errorCode === '440') {
          // Token 過期或 Session 過期
          console.error('Token 過期，導向登入頁');
          localStorage.removeItem('authToken');
          window.location.href = '/login';
        } else if (errorCode === '429') {
          // 重連過於頻繁
          console.warn('重連過於頻繁，等待 10 秒後重試');
          setTimeout(() => connectWebSocket(), 10000);
        }
      },
    });

    client.activate();
    clientRef.current = client;
  }, [jwtToken, matchId]);

  const handleReconnect = useCallback(() => {
    if (reconnectAttemptsRef.current >= maxReconnectAttempts) {
      console.error('重連失敗次數過多，請重新整理頁面');
      alert('連線中斷，請重新整理頁面');
      return;
    }

    reconnectAttemptsRef.current += 1;
    const delay = Math.min(1000 * Math.pow(2, reconnectAttemptsRef.current), 30000);

    console.log(`🔄 重連嘗試 #${reconnectAttemptsRef.current}，延遲 ${delay}ms`);

    setTimeout(() => {
      connectWebSocket();
    }, delay);
  }, [connectWebSocket]);

  useEffect(() => {
    connectWebSocket();

    return () => {
      if (clientRef.current) {
        clientRef.current.deactivate();
      }
    };
  }, [connectWebSocket]);

  const sendAction = useCallback((action: Action) => {
    if (clientRef.current && isConnected) {
      clientRef.current.publish({
        destination: `/app/match/${matchId}/action`,
        body: JSON.stringify({ action }),
      });
    } else {
      console.error('WebSocket 未連線，無法發送動作');
    }
  }, [isConnected, matchId]);

  const handleStateUpdate = (update: StateUpdate) => {
    console.log('收到狀態更新:', update);
    // 套用狀態變更
    setMatchState((prevState) => applyStateChanges(prevState, update.stateChanges));
  };

  const handleActionResult = (result: ActionResult) => {
    console.log('收到動作結果:', result);
    if (result.success) {
      setMatchState((prevState) => applyStateChanges(prevState, result.stateChanges));
    } else {
      alert(`動作失敗: ${result.errorMessage}`);
    }
  };

  return { isConnected, matchState, sendAction };
};
```

---

## 5. 最佳實踐總結

### 5.1 鑑權建議

✅ **推薦做法**：
- 使用 `Authorization: Bearer {token}` Header
- JWT 有效期設為 24 小時（對於單局對戰已足夠）
- 重連時沿用同一個 JWT

❌ **不推薦做法**：
- 將 Token 放在 URL（會出現在伺服器日誌中）
- 使用過短的 JWT 有效期（< 1 小時），會頻繁斷線

---

### 5.2 重連建議

✅ **推薦做法**：
- 使用指數退避策略（1s, 2s, 4s, 8s, 16s, 30s）
- 最多重連 5 次
- 重連失敗後提示使用者重新整理

❌ **不推薦做法**：
- 無限重連（會造成伺服器壓力）
- 固定間隔重連（容易在網路不穩時造成連線風暴）

---

### 5.3 錯誤處理建議

✅ **推薦做法**：
- 401/440 錯誤 → 導向登入頁
- 403 錯誤 → 顯示「無權限」訊息
- 429 錯誤 → 延遲後重試
- 503 錯誤 → 顯示「伺服器忙碌」

❌ **不推薦做法**：
- 忽略錯誤訊息
- 所有錯誤都導向登入頁

---

## 總結

本文件補充了 WebSocket 連線的完整鑑權與重連機制，包括：

1. **鑑權方式**：優先使用 `Authorization Header`，備用 `Query Token`
2. **重連策略**：沿用同一 JWT（推薦）或發臨時 Reconnect Token
3. **錯誤處理**：401/440/403/429/503 錯誤碼與對應處理方式
4. **完整範例**：前端 React Hook 與後端 Spring Boot 實作

遵循這些規範可確保 WebSocket 連線的安全性與穩定性。
