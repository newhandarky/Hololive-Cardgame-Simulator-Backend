# LINE LIFF 整合與授權安全規範

## 概述

本文件定義 LINE LIFF 整合的完整流程，包括前端 LIFF 初始化、後端 ID Token 驗證、授權機制等，確保系統安全性。

**核心原則**：永遠不要只信任前端傳入的 `userId`，必須由後端驗證 LINE ID Token。

---

## 1. 整體架構

```
使用者在 LINE 開啟 LIFF
         ↓
前端：liff.init() → liff.login()
         ↓
前端：取得 ID Token (liff.getIDToken())
         ↓
前端：將 ID Token 傳給後端 API
         ↓
後端：驗證 ID Token（呼叫 LINE API）
         ↓
後端：確認合法後，建立 Session / JWT
         ↓
前端：使用 Session / JWT 存取後續 API
```

---

## 2. 前端：LIFF 初始化與登入

### 2.1 環境變數設定

在 `.env` 檔案中設定 LIFF ID：

```env
VITE_LIFF_ID=1234567890-AbCdEfGh
```

---

### 2.2 LIFF 初始化 Hook（`src/hooks/useLiff.ts`）

```typescript
import { useEffect, useState } from 'react';
import liff from '@line/liff';

const LIFF_ID = import.meta.env.VITE_LIFF_ID;

interface LiffProfile {
  userId: string;
  displayName: string;
  pictureUrl?: string;
  statusMessage?: string;
}

interface UseLiffReturn {
  isReady: boolean;
  isLoggedIn: boolean;
  profile: LiffProfile | null;
  idToken: string | null;
  error: string | null;
  login: () => void;
  logout: () => void;
}

export const useLiff = (): UseLiffReturn => {
  const [isReady, setIsReady] = useState(false);
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [profile, setProfile] = useState<LiffProfile | null>(null);
  const [idToken, setIdToken] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const initLiff = async () => {
      try {
        // 初始化 LIFF
        await liff.init({ liffId: LIFF_ID });
        setIsReady(true);

        // 檢查登入狀態
        if (liff.isLoggedIn()) {
          setIsLoggedIn(true);

          // 取得使用者資料
          const userProfile = await liff.getProfile();
          setProfile({
            userId: userProfile.userId,
            displayName: userProfile.displayName,
            pictureUrl: userProfile.pictureUrl,
            statusMessage: userProfile.statusMessage,
          });

          // ⚠️ 重點：取得 ID Token
          const token = liff.getIDToken();
          setIdToken(token || null);
        } else {
          // 未登入，導向登入
          liff.login();
        }
      } catch (err: any) {
        setError(err.message || 'LIFF 初始化失敗');
        console.error('LIFF 初始化錯誤:', err);
      }
    };

    initLiff();
  }, []);

  const login = () => {
    if (isReady && !isLoggedIn) {
      liff.login();
    }
  };

  const logout = () => {
    if (isReady && isLoggedIn) {
      liff.logout();
      setIsLoggedIn(false);
      setProfile(null);
      setIdToken(null);
    }
  };

  return {
    isReady,
    isLoggedIn,
    profile,
    idToken,
    error,
    login,
    logout,
  };
};
```

---

### 2.3 前端登入流程（`src/pages/Home.tsx`）

```typescript
import React, { useEffect, useState } from 'react';
import { useLiff } from '../hooks/useLiff';
import { api, loginWithLine } from '../services/api';

const Home: React.FC = () => {
  const { isLoggedIn, profile, idToken, error } = useLiff();
  const [authToken, setAuthToken] = useState<string | null>(null);

  useEffect(() => {
    const authenticate = async () => {
      if (isLoggedIn && idToken) {
        try {
          // ⚠️ 將 ID Token 傳給後端驗證
          const response = await loginWithLine(idToken);
          
          // 後端回傳 JWT 或 Session Token
          const { token } = response;
          setAuthToken(token);
          
          // 將 token 存到 localStorage（或用其他方式管理）
          localStorage.setItem('authToken', token);
          
          // 設定 axios 的預設 header
          api.defaults.headers.common['Authorization'] = `Bearer ${token}`;
        } catch (err) {
          console.error('LINE 登入失敗:', err);
        }
      }
    };

    authenticate();
  }, [isLoggedIn, idToken]);

  if (error) {
    return <div>LIFF 錯誤: {error}</div>;
  }

  if (!isLoggedIn) {
    return <div>正在登入...</div>;
  }

  if (!authToken) {
    return <div>正在驗證...</div>;
  }

  return (
    <div style={{ padding: '20px' }}>
      <h1>HOLOLIVE Card Game</h1>
      <p>歡迎, {profile?.displayName}!</p>
      <img 
        src={profile?.pictureUrl} 
        alt="avatar" 
        style={{ width: '100px', borderRadius: '50%' }} 
      />
      <button onClick={() => window.location.href = '/match/create'}>
        建立對戰房間
      </button>
    </div>
  );
};

export default Home;
```

---

### 2.4 API 服務更新（`src/services/api.ts`）

```typescript
import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api';

export const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// 從 localStorage 恢復 token（頁面刷新時）
const storedToken = localStorage.getItem('authToken');
if (storedToken) {
  api.defaults.headers.common['Authorization'] = `Bearer ${storedToken}`;
}

// ⚠️ 後端驗證 LINE ID Token 的 API
export const loginWithLine = async (idToken: string) => {
  const response = await api.post('/auth/line-login', { idToken });
  return response.data;
};

// 其他 API 方法...
export const healthCheck = async () => {
  const response = await api.get('/health');
  return response.data;
};

export const createMatch = async () => {
  const response = await api.post('/matches/create');
  return response.data;
};

export const joinMatch = async (roomCode: string) => {
  const response = await api.post('/matches/join', { roomCode });
  return response.data;
};
```

---

## 3. 後端：ID Token 驗證與授權

### 3.1 為什麼需要後端驗證？

**問題情境**：
- 如果只信任前端傳來的 `userId`，惡意使用者可以偽造任意 `userId` 來冒充他人。
- 例如：用戶 A 可以假裝自己是用戶 B，竊取 B 的資料或操作 B 的帳號。

**解決方案**：
- 前端取得 LINE 的 `ID Token`（由 LINE 簽發，無法偽造）
- 後端呼叫 LINE API 驗證 `ID Token` 是否合法
- 驗證通過後，後端才信任該使用者身份

---

### 3.2 後端依賴套件

在 `pom.xml` 中加入：

```xml
<!-- Spring Security（可選，若要用 JWT） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- JWT 套件（用於產生後端 token） -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.11.5</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.11.5</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.11.5</version>
    <scope>runtime</scope>
</dependency>

<!-- HTTP Client（用於呼叫 LINE API） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

---

### 3.3 LINE ID Token 驗證服務

#### 3.3.1 `LineTokenVerifier.java`

```java
package com.hololive.cardgame.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class LineTokenVerifier {
    
    @Value("${line.channel-id}")
    private String channelId;
    
    private final WebClient webClient;
    
    public LineTokenVerifier(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
            .baseUrl("https://api.line.me")
            .build();
    }
    
    /**
     * 驗證 LINE ID Token
     * @param idToken 前端傳來的 ID Token
     * @return LINE User ID（驗證成功時）
     * @throws RuntimeException 驗證失敗時
     */
    public String verifyIdToken(String idToken) {
        try {
            // 呼叫 LINE API 驗證 ID Token
            JsonNode response = webClient.post()
                .uri("/oauth2/v2.1/verify")
                .bodyValue(new IdTokenRequest(idToken, channelId))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
            
            if (response == null) {
                throw new RuntimeException("LINE API 無回應");
            }
            
            // 檢查 channel ID 是否正確（防止 token 被用於其他 app）
            String tokenChannelId = response.get("client_id").asText();
            if (!channelId.equals(tokenChannelId)) {
                throw new RuntimeException("ID Token 不屬於此應用");
            }
            
            // 取得 LINE User ID
            String lineUserId = response.get("sub").asText();
            return lineUserId;
            
        } catch (Exception e) {
            throw new RuntimeException("ID Token 驗證失敗: " + e.getMessage(), e);
        }
    }
    
    // DTO
    private static class IdTokenRequest {
        public String id_token;
        public String client_id;
        
        public IdTokenRequest(String idToken, String clientId) {
            this.id_token = idToken;
            this.client_id = clientId;
        }
    }
}
```

---

### 3.4 JWT Token 產生服務

#### 3.4.1 `JwtTokenProvider.java`

```java
package com.hololive.cardgame.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.util.Date;

@Service
public class JwtTokenProvider {
    
    @Value("${jwt.secret}")
    private String jwtSecret;
    
    @Value("${jwt.expiration:86400000}") // 預設 24 小時
    private long jwtExpiration;
    
    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }
    
    /**
     * 產生 JWT Token
     */
    public String generateToken(Long userId, String lineUserId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpiration);
        
        return Jwts.builder()
            .setSubject(userId.toString())
            .claim("lineUserId", lineUserId)
            .setIssuedAt(now)
            .setExpiration(expiryDate)
            .signWith(getSigningKey(), SignatureAlgorithm.HS512)
            .compact();
    }
    
    /**
     * 從 Token 取得 User ID
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = Jwts.parserBuilder()
            .setSigningKey(getSigningKey())
            .build()
            .parseClaimsJws(token)
            .getBody();
        
        return Long.parseLong(claims.getSubject());
    }
    
    /**
     * 驗證 Token 是否有效
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

---

### 3.5 登入 Controller

#### 3.5.1 `AuthController.java`

```java
package com.hololive.cardgame.controller;

import com.hololive.cardgame.dto.LineLoginRequest;
import com.hololive.cardgame.dto.LineLoginResponse;
import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.repository.UserRepository;
import com.hololive.cardgame.service.JwtTokenProvider;
import com.hololive.cardgame.service.LineTokenVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    
    @Autowired
    private LineTokenVerifier lineTokenVerifier;
    
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    
    @Autowired
    private UserRepository userRepository;
    
    /**
     * LINE 登入端點
     * ⚠️ 後端驗證 ID Token，不直接信任前端傳的 userId
     */
    @PostMapping("/line-login")
    public ResponseEntity<LineLoginResponse> lineLogin(@RequestBody LineLoginRequest request) {
        try {
            // 1. 驗證 LINE ID Token
            String lineUserId = lineTokenVerifier.verifyIdToken(request.getIdToken());
            
            // 2. 查找或建立使用者
            User user = userRepository.findByLineUserId(lineUserId)
                .orElseGet(() -> {
                    // 首次登入，建立新使用者
                    // ⚠️ 這裡需要再次呼叫 LINE API 取得 profile，或要求前端一併傳送
                    // 為了安全，建議後端自己呼叫 LINE Profile API
                    User newUser = new User();
                    newUser.setLineUserId(lineUserId);
                    newUser.setDisplayName("使用者"); // 暫時，稍後可更新
                    return userRepository.save(newUser);
                });
            
            // 3. 產生後端 JWT Token
            String jwtToken = jwtTokenProvider.generateToken(user.getId(), lineUserId);
            
            // 4. 回傳給前端
            return ResponseEntity.ok(new LineLoginResponse(
                jwtToken,
                user.getId(),
                user.getDisplayName()
            ));
            
        } catch (Exception e) {
            return ResponseEntity.status(401)
                .body(new LineLoginResponse(null, null, "登入失敗: " + e.getMessage()));
        }
    }
}
```

---

#### 3.5.2 DTO 定義

```java
// LineLoginRequest.java
package com.hololive.cardgame.dto;

import lombok.Data;

@Data
public class LineLoginRequest {
    private String idToken;  // ⚠️ 前端傳來的 LINE ID Token
}
```

```java
// LineLoginResponse.java
package com.hololive.cardgame.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LineLoginResponse {
    private String token;      // 後端產生的 JWT
    private Long userId;       // 系統內部 User ID
    private String displayName;
}
```

---

### 3.6 後續 API 的授權驗證

#### 3.6.1 JWT 驗證 Filter

```java
package com.hololive.cardgame.security;

import com.hololive.cardgame.service.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    
    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        
        // 從 Header 取得 JWT Token
        String token = getJwtFromRequest(request);
        
        if (token != null && jwtTokenProvider.validateToken(token)) {
            // Token 有效，取得 User ID
            Long userId = jwtTokenProvider.getUserIdFromToken(token);
            
            // 設定 Spring Security Context
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    userId, null, Collections.emptyList()
                );
            authentication.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(request)
            );
            
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        
        filterChain.doFilter(request, response);
    }
    
    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
```

---

#### 3.6.2 Security 配置

```java
package com.hololive.cardgame.config;

import com.hololive.cardgame.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    
    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf().disable()
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()
            .authorizeHttpRequests()
                .requestMatchers("/api/auth/**", "/api/health").permitAll()  // 公開端點
                .anyRequest().authenticated()  // 其他端點需要驗證
            .and()
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        
        return http.build();
    }
}
```

---

### 3.7 在 Controller 中取得當前使用者

```java
@RestController
@RequestMapping("/api/matches")
public class MatchController {
    
    @PostMapping("/create")
    public ResponseEntity<Match> createMatch() {
        // 從 Security Context 取得當前使用者 ID
        Long userId = (Long) SecurityContextHolder.getContext()
            .getAuthentication()
            .getPrincipal();
        
        // 使用 userId 建立對戰...
        // ...
    }
}
```

---

## 4. 環境變數配置

### 4.1 後端 `application.yml`

```yaml
line:
  channel-id: ${LINE_CHANNEL_ID:1234567890}  # LINE Login Channel ID

jwt:
  secret: ${JWT_SECRET:your-secret-key-min-256-bits-long}  # 至少 256 bits
  expiration: 86400000  # 24 小時（毫秒）

spring:
  security:
    oauth2:
      client:
        registration:
          line:
            client-id: ${LINE_CHANNEL_ID}
            client-secret: ${LINE_CHANNEL_SECRET}
```

**⚠️ 生產環境注意事項**：
- `JWT_SECRET` 必須是強隨機字串，至少 256 bits
- 不要把敏感資訊寫在 `application.yml` 裡，應該用環境變數

---

## 5. 完整流程圖

```
┌─────────────┐
│ 使用者在 LINE │
│ 點擊 LIFF URL│
└──────┬──────┘
       │
       ▼
┌─────────────────────┐
│ 前端：liff.init()    │
│ liff.login()        │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│ 前端：取得 ID Token  │
│ (liff.getIDToken()) │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────────────────┐
│ 前端：POST /api/auth/line-login │
│ Body: { idToken: "..." }        │
└──────┬──────────────────────────┘
       │
       ▼
┌──────────────────────────────────┐
│ 後端：呼叫 LINE API 驗證 ID Token│
│ https://api.line.me/oauth2/...   │
└──────┬───────────────────────────┘
       │
       ▼ (驗證成功)
┌──────────────────────────┐
│ 後端：查找或建立使用者    │
│ (userRepository.find...) │
└──────┬───────────────────┘
       │
       ▼
┌──────────────────────┐
│ 後端：產生 JWT Token │
│ (jwtTokenProvider)   │
└──────┬───────────────┘
       │
       ▼
┌────────────────────────────┐
│ 後端：回傳 { token, userId }│
└──────┬─────────────────────┘
       │
       ▼
┌───────────────────────────────┐
│ 前端：儲存 token 到 localStorage│
│ 設定 axios Authorization header│
└──────┬────────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│ 前端：後續 API 都帶上 token   │
│ Header: Authorization: Bearer │
└──────┬───────────────────────┘
       │
       ▼
┌─────────────────────────────┐
│ 後端：JwtAuthenticationFilter│
│ 驗證 token → 取得 userId     │
└──────┬──────────────────────┘
       │
       ▼
┌──────────────────┐
│ 執行業務邏輯     │
└──────────────────┘
```

---

## 6. 安全性檢查清單

### ✅ 前端安全

- [ ] 不要在前端儲存敏感資訊（如密碼、API Secret）
- [ ] 不要只信任前端傳來的 `userId`
- [ ] 使用 HTTPS（生產環境）
- [ ] ID Token 不要長時間存放，應該用完即丟

### ✅ 後端安全

- [ ] 一定要驗證 LINE ID Token（不能只信任前端傳的 userId）
- [ ] JWT Secret 必須夠長、夠隨機（至少 256 bits）
- [ ] JWT 要設定過期時間（建議 24 小時內）
- [ ] 敏感操作（如刪除帳號）應該要求重新驗證
- [ ] API 應設定 Rate Limiting（防止暴力破解）

### ✅ LIFF 配置安全

- [ ] LIFF Endpoint URL 必須是 HTTPS（生產環境）
- [ ] 檢查 Channel ID 是否正確綁定
- [ ] 定期檢查 LINE Developers Console 的存取記錄

---

## 7. 測試建議

### 7.1 前端測試

```typescript
// 測試 LIFF 初始化
test('useLiff should initialize and get profile', async () => {
  // Mock liff.init(), liff.getProfile(), liff.getIDToken()
  // ...
});
```

### 7.2 後端測試

```java
@SpringBootTest
public class AuthControllerTest {
    
    @Test
    public void testLineLogin_ValidToken_ShouldReturnJwt() {
        // Given: 有效的 LINE ID Token
        // When: POST /api/auth/line-login
        // Then: 應回傳 JWT Token 與 userId
    }
    
    @Test
    public void testLineLogin_InvalidToken_ShouldReturn401() {
        // Given: 無效的 ID Token
        // When: POST /api/auth/line-login
        // Then: 應回傳 401 Unauthorized
    }
}
```

---

## 總結

本文件定義了完整的 LINE LIFF 整合與授權安全流程，核心重點：

1. **前端取得 ID Token**：使用 `liff.getIDToken()`
2. **後端驗證 ID Token**：呼叫 LINE API 驗證，不信任前端傳的 userId
3. **產生後端 JWT**：驗證通過後產生自己的 JWT Token
4. **後續 API 授權**：用 JWT 驗證所有後續請求

遵循本文件的流程，可確保系統安全性，防止身份偽造攻擊。
