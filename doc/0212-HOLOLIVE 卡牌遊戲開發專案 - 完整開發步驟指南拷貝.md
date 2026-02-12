# HOLOLIVE 卡牌遊戲開發專案 - 完整開發步驟指南

## 專案概述

本專案目標是開發一個基於 LINE OA 的 HOLOLIVE Official Card Game 線上對戰系統。

### 技術堆疊
- **前端**：React + TypeScript + LIFF SDK
- **後端**：Java 17 + Spring Boot
- **資料庫**：PostgreSQL
- **開發環境**：Antigravity（雲端開發環境）
- **部署平台**：Render（後端 + DB）、Vercel/Cloudflare Pages（前端）

---

## 階段一：環境準備與工具安裝

### 1.1 本機環境準備

#### 必要安裝項目

1. **VS Code**
   - 確保已安裝最新版本

2. **JDK 17 或 21**
   - 下載：[Oracle JDK](https://www.oracle.com/java/technologies/downloads/) 或 [OpenJDK](https://adoptium.net/)
   - 安裝後驗證：
     ```bash
     java -version
     # 應顯示 java version "17.x.x" 或 "21.x.x"
     ```

3. **Node.js 18+**
   - 下載：[Node.js 官網](https://nodejs.org/)
   - 安裝後驗證：
     ```bash
     node -v
     npm -v
     ```

4. **PostgreSQL（可選，本機測試用）**
   - 如果只用 Antigravity/Render 的雲端資料庫，可以先不裝
   - 如果要本機測試，下載：[PostgreSQL 官網](https://www.postgresql.org/download/)

---

### 1.2 VS Code 擴充套件安裝

在 VS Code 中安裝以下擴充：

1. **Java Extension Pack**
   - 包含：Language Support for Java, Debugger for Java, Maven for Java 等

2. **Spring Boot Extension Pack**
   - 包含：Spring Boot Tools, Spring Initializr Java Support, Spring Boot Dashboard

3. **PostgreSQL**
   - 用於在 VS Code 內連接資料庫、執行 SQL

4. **Antigravity 擴充（如果有官方提供）**
   - 查看 Antigravity 官方文件，安裝對應的 VS Code 擴充

5. **其他推薦擴充**
   - ES7+ React/Redux/React-Native snippets
   - Prettier - Code formatter
   - ESLint

---

### 1.3 建立 Antigravity 專案

#### 步驟

1. **登入 Antigravity**
   - 開啟瀏覽器，前往 Antigravity 平台
   - 使用你的帳號登入

2. **建立新專案**
   - 點擊「New Project」或「Create Workspace」
   - 專案名稱：`hololive-cardgame`
   - 描述：HOLOLIVE Official Card Game 線上對戰系統

3. **選擇環境範本**
   - 選擇「Java + Spring Boot」或「Full Stack（Java + Node.js）」範本
   - 如果有 PostgreSQL 選項，勾選啟用

4. **配置專案設定**
   - Java 版本：17 或 21
   - Build Tool：Maven（推薦）或 Gradle
   - Node.js 版本：18+

5. **啟動開發環境**
   - 等待環境初始化完成
   - 進入 Antigravity IDE（通常是基於 VS Code 的網頁版 IDE）

---

## 階段二：後端專案初始化

### 2.1 建立 Spring Boot 專案

#### 方法一：使用 Spring Initializr（推薦）

1. **在 Antigravity IDE 中開啟終端機**

2. **使用 Spring Initializr 建立專案**
   - 方式 1：在 IDE 中使用 Spring Initializr 擴充
     - 按 `F1` 或 `Ctrl+Shift+P`
     - 輸入 `Spring Initializr: Create a Maven Project`
     - 依序選擇：
       - Spring Boot 版本：3.2.x 或最新穩定版
       - Language：Java
       - Group：`com.hololive`
       - Artifact：`cardgame`
       - Packaging：Jar
       - Java 版本：17 或 21
   
   - 方式 2：使用網頁版 Spring Initializr
     - 前往 https://start.spring.io/
     - 配置同上
     - 下載 zip 檔，解壓後上傳到 Antigravity

3. **選擇 Dependencies（依賴套件）**
   
   必選：
   - `Spring Web`：建立 REST API
   - `Spring Data JPA`：資料庫 ORM
   - `PostgreSQL Driver`：PostgreSQL 連接驅動
   
   推薦：
   - `Spring Boot DevTools`：熱部署，開發時自動重啟
   - `Validation`：資料驗證
   - `Lombok`：減少 boilerplate code

4. **生成專案**
   - 專案會建立在 `hololive-cardgame` 或你指定的目錄

---

### 2.2 專案結構說明

生成的專案結構應該如下：

```
hololive-cardgame/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/hololive/cardgame/
│   │   │       ├── CardgameApplication.java    # 主程式入口
│   │   │       ├── controller/                 # REST API 控制器
│   │   │       ├── service/                    # 業務邏輯層
│   │   │       ├── repository/                 # 資料庫訪問層
│   │   │       ├── entity/                     # 資料庫實體類
│   │   │       ├── dto/                        # 資料傳輸物件
│   │   │       └── config/                     # 配置類
│   │   └── resources/
│   │       ├── application.yml                 # 應用配置
│   │       └── application-dev.yml             # 開發環境配置
│   └── test/                                   # 測試程式
├── pom.xml                                     # Maven 依賴配置
└── README.md
```

---

### 2.3 建立資料庫

#### 在 Antigravity 建立 PostgreSQL 實例

1. **查找資料庫服務**
   - 在 Antigravity 面板中找到「Services」或「Databases」區塊

2. **建立 PostgreSQL 資料庫**
   - 點擊「Add Database」或「Create PostgreSQL」
   - 資料庫名稱：`holocardgame_db`
   - 記下連線資訊：
     - Host：`<antigravity-db-host>`
     - Port：`5432`（預設）
     - Database：`holocardgame_db`
     - Username：`<username>`
     - Password：`<password>`

3. **（替代方案）使用 Docker Compose**
   
   如果 Antigravity 支援 Docker，在專案根目錄建立 `docker-compose.yml`：

   ```yaml
   version: '3.8'
   services:
     postgres:
       image: postgres:15
       container_name: holocardgame_db
       environment:
         POSTGRES_DB: holocardgame_db
         POSTGRES_USER: holocard_user
         POSTGRES_PASSWORD: holocard_password
       ports:
         - "5432:5432"
       volumes:
         - postgres_data:/var/lib/postgresql/data

   volumes:
     postgres_data:
   ```

   啟動：
   ```bash
   docker-compose up -d
   ```

---

### 2.4 配置 Spring Boot 連接資料庫

#### 編輯 `src/main/resources/application.yml`

```yaml
spring:
  application:
    name: hololive-cardgame

  datasource:
    url: jdbc:postgresql://<HOST>:<PORT>/holocardgame_db
    username: <USERNAME>
    password: <PASSWORD>
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: none  # 不自動建表，我們手動執行 SQL
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
    show-sql: true  # 開發時顯示 SQL 語句

server:
  port: 8080

logging:
  level:
    com.hololive.cardgame: DEBUG
    org.hibernate.SQL: DEBUG
```

**重要**：將 `<HOST>`、`<PORT>`、`<USERNAME>`、`<PASSWORD>` 替換為你的實際資料庫連線資訊。

---

### 2.5 導入資料庫 Schema

#### 步驟

1. **準備 SQL 檔案**
   - 在專案中建立 `db/` 目錄
   - 建立檔案：`db/schema.sql`
   - 將先前產生的 `hololive-card-game-database-schema.md` 中的所有 SQL 語句複製到此檔案

2. **連接到 PostgreSQL**
   - 在 VS Code（Antigravity IDE）中：
     - 點擊左側的 PostgreSQL 擴充圖示
     - 新增連線：輸入 Host、Port、Database、Username、Password
     - 連線成功後會看到資料庫結構

3. **執行 SQL Schema**
   - 開啟 `db/schema.sql`
   - 選取所有內容
   - 右鍵 → `Run Query` 或按 `F5`
   - 檢查是否成功建立所有資料表

4. **驗證資料表建立**
   ```sql
   -- 查看所有資料表
   SELECT table_name FROM information_schema.tables 
   WHERE table_schema = 'public';
   
   -- 應該看到：cards, colors, oshi_cards, member_cards, 等等
   ```

---

## 階段三：建立 Spring Boot 基礎結構

### 3.1 建立 Entity 類別

在 `src/main/java/com/hololive/cardgame/entity/` 建立以下 Entity。

#### 3.1.1 `Color.java`

```java
package com.hololive.cardgame.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "colors")
@Data
public class Color {
    @Id
    @Column(length = 20)
    private String code;  // 'WHITE', 'GREEN', 'RED', 'BLUE', 'YELLOW', 'PURPLE'
    
    @Column(nullable = false, length = 50)
    private String name;
}
```

#### 3.1.2 `Card.java`

```java
package com.hololive.cardgame.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "cards")
@Data
public class Card {
    @Id
    @Column(name = "card_id", length = 50)
    private String cardId;
    
    @Column(nullable = false)
    private String name;
    
    @Column(length = 20)
    private String rarity;
    
    @Column(name = "image_url", length = 512)
    private String imageUrl;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "card_type", nullable = false, length = 20)
    private CardType cardType;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
```

#### 3.1.3 `CardType.java`（Enum）

```java
package com.hololive.cardgame.entity;

public enum CardType {
    OSHI,      // 推しカード
    MEMBER,    // ホロメンカード
    SUPPORT,   // サポートカード
    CHEER      // エールカード
}
```

#### 3.1.4 `User.java`

```java
package com.hololive.cardgame.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "line_user_id", nullable = false, unique = true)
    private String lineUserId;
    
    @Column(name = "display_name", nullable = false)
    private String displayName;
    
    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
```

#### 3.1.5 `Match.java`

```java
package com.hololive.cardgame.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "matches")
@Data
public class Match {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "room_code", nullable = false, unique = true, length = 20)
    private String roomCode;
    
    @ManyToOne
    @JoinColumn(name = "player_a_id", nullable = false)
    private User playerA;
    
    @ManyToOne
    @JoinColumn(name = "player_b_id", nullable = false)
    private User playerB;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MatchStatus status = MatchStatus.ACTIVE;
    
    @ManyToOne
    @JoinColumn(name = "winner_user_id")
    private User winner;
    
    @ManyToOne
    @JoinColumn(name = "current_turn_player_id")
    private User currentTurnPlayer;
    
    @Column(name = "turn_number", nullable = false)
    private Integer turnNumber = 1;
    
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();
    
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
```

#### 3.1.6 `MatchStatus.java`（Enum）

```java
package com.hololive.cardgame.entity;

public enum MatchStatus {
    ACTIVE,      // 進行中
    FINISHED,    // 已結束
    ABANDONED    // 已放棄
}
```

**提示**：其他 Entity（`OshiCard`、`MemberCard` 等）可以在後續階段逐步加入。

---

### 3.2 建立 Repository 介面

在 `src/main/java/com/hololive/cardgame/repository/` 建立 Repository。

#### 3.2.1 `UserRepository.java`

```java
package com.hololive.cardgame.repository;

import com.hololive.cardgame.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByLineUserId(String lineUserId);
}
```

#### 3.2.2 `CardRepository.java`

```java
package com.hololive.cardgame.repository;

import com.hololive.cardgame.entity.Card;
import com.hololive.cardgame.entity.CardType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CardRepository extends JpaRepository<Card, String> {
    List<Card> findByCardType(CardType cardType);
}
```

#### 3.2.3 `MatchRepository.java`

```java
package com.hololive.cardgame.repository;

import com.hololive.cardgame.entity.Match;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface MatchRepository extends JpaRepository<Match, Long> {
    Optional<Match> findByRoomCode(String roomCode);
}
```

---

### 3.3 建立測試用 Controller

在 `src/main/java/com/hololive/cardgame/controller/` 建立測試 API。

#### 3.3.1 `HealthController.java`

```java
package com.hololive.cardgame.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {
    
    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "ok");
        response.put("message", "HOLOLIVE Card Game API is running");
        return response;
    }
}
```

#### 3.3.2 `UserController.java`（測試用）

```java
package com.hololive.cardgame.controller;

import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {
    
    @Autowired
    private UserRepository userRepository;
    
    @GetMapping
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }
    
    @PostMapping("/test")
    public User createTestUser() {
        User user = new User();
        user.setLineUserId("test_line_" + System.currentTimeMillis());
        user.setDisplayName("測試使用者");
        user.setAvatarUrl("https://example.com/avatar.png");
        return userRepository.save(user);
    }
}
```

---

### 3.4 啟動 Spring Boot 應用

#### 在 Antigravity IDE 中啟動

1. **使用 Spring Boot Dashboard**
   - 在 VS Code 左側找到「Spring Boot Dashboard」
   - 點擊「Start」按鈕啟動應用

2. **使用終端機**
   ```bash
   # Maven
   ./mvnw spring-boot:run
   
   # 或 Gradle
   ./gradlew bootRun
   ```

3. **驗證應用啟動**
   - 觀察終端機輸出，應該看到：
     ```
     Started CardgameApplication in X.XXX seconds
     ```
   - 應用會在 `http://localhost:8080` 啟動

---

### 3.5 測試 API

使用瀏覽器或 curl 測試：

```bash
# 測試健康檢查
curl http://localhost:8080/api/health

# 預期回應：
# {"status":"ok","message":"HOLOLIVE Card Game API is running"}

# 建立測試使用者
curl -X POST http://localhost:8080/api/users/test

# 查看所有使用者
curl http://localhost:8080/api/users
```

---

## 階段四：前端專案初始化

### 4.1 建立 React 專案

#### 在 Antigravity IDE 中

1. **開啟終端機**

2. **建立 React 專案**（選擇其中一種方式）

   **方式 1：使用 Create React App + TypeScript**
   ```bash
   npx create-react-app client --template typescript
   cd client
   ```

   **方式 2：使用 Vite（推薦，更快）**
   ```bash
   npm create vite@latest client -- --template react-ts
   cd client
   npm install
   ```

3. **安裝必要套件**
   ```bash
   # LIFF SDK（LINE 前端框架）
   npm install @line/liff
   
   # React Router（路由）
   npm install react-router-dom
   
   # Axios（HTTP 請求）
   npm install axios
   
   # 其他推薦
   npm install @types/node
   ```

---

### 4.2 設定環境變數

在 `client/` 目錄建立 `.env` 檔案：

```env
# 後端 API 基礎 URL
VITE_API_BASE_URL=http://localhost:8080/api

# LIFF ID（稍後在 LINE Developers 取得）
VITE_LIFF_ID=your-liff-id-here
```

**注意**：如果使用 Create React App，變數名稱要改為 `REACT_APP_` 開頭。

---

### 4.3 建立基本頁面結構

#### 專案結構

```
client/
├── src/
│   ├── components/          # 元件
│   ├── pages/              # 頁面
│   │   ├── Home.tsx        # 首頁
│   │   ├── Match.tsx       # 對戰頁面
│   │   └── NotFound.tsx    # 404 頁面
│   ├── services/           # API 服務
│   │   └── api.ts
│   ├── types/              # TypeScript 類型定義
│   ├── App.tsx
│   └── main.tsx
```

---

#### 4.3.1 設定 API 服務（`src/services/api.ts`）

```typescript
import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api';

export const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// API 方法
export const healthCheck = async () => {
  const response = await api.get('/health');
  return response.data;
};

export const getAllUsers = async () => {
  const response = await api.get('/users');
  return response.data;
};

export const createTestUser = async () => {
  const response = await api.post('/users/test');
  return response.data;
};
```

---

#### 4.3.2 首頁（`src/pages/Home.tsx`）

```typescript
import React, { useEffect, useState } from 'react';
import { healthCheck } from '../services/api';

const Home: React.FC = () => {
  const [apiStatus, setApiStatus] = useState<string>('checking...');

  useEffect(() => {
    const checkAPI = async () => {
      try {
        const data = await healthCheck();
        setApiStatus(data.message);
      } catch (error) {
        setApiStatus('API 連線失敗');
        console.error(error);
      }
    };
    checkAPI();
  }, []);

  return (
    <div style={{ padding: '20px' }}>
      <h1>HOLOLIVE Card Game</h1>
      <p>API 狀態: {apiStatus}</p>
      <button onClick={() => window.location.href = '/match/test'}>
        開始測試對戰
      </button>
    </div>
  );
};

export default Home;
```

---

#### 4.3.3 設定路由（`src/App.tsx`）

```typescript
import React from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import Home from './pages/Home';
import Match from './pages/Match';
import NotFound from './pages/NotFound';

const App: React.FC = () => {
  return (
    <Router>
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/match/:id" element={<Match />} />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </Router>
  );
};

export default App;
```

---

### 4.4 啟動前端開發伺服器

```bash
# 在 client/ 目錄下
npm run dev

# 應該會在 http://localhost:5173 或 http://localhost:3000 啟動
```

在瀏覽器開啟，應該會看到首頁並顯示「API 狀態: HOLOLIVE Card Game API is running」。

---

## 階段五：LINE OA / LIFF 整合

### 5.1 建立 LINE Developer 資源

#### 步驟

1. **前往 LINE Developers Console**
   - https://developers.line.biz/console/

2. **建立 Provider**
   - 如果還沒有，先建立一個 Provider（例如：HOLOLIVE Card Game）

3. **建立 Messaging API Channel**
   - 類型：Messaging API
   - Channel 名稱：HOLOLIVE Card Game OA
   - 描述：官方帳號，用於遊戲通知

4. **建立 LINE Login Channel**
   - 類型：LINE Login
   - 用於使用者登入驗證

5. **建立 LIFF App**
   - 在 LINE Login Channel 中，點擊「LIFF」
   - 新增 LIFF App：
     - LIFF App 名稱：HOLOLIVE Card Game
     - Size：Full
     - Endpoint URL：`https://your-frontend-url.com`（部署後的網址）
     - 暫時可填：`http://localhost:5173`（本機測試）
   - 記下 **LIFF ID**（例如：`1234567890-AbCdEfGh`）

---

### 5.2 前端整合 LIFF

#### 5.2.1 更新 `.env`

```env
VITE_LIFF_ID=1234567890-AbCdEfGh
```

---

#### 5.2.2 建立 LIFF 初始化 Hook（`src/hooks/useLiff.ts`）

```typescript
import { useEffect, useState } from 'react';
import liff from '@line/liff';

const LIFF_ID = import.meta.env.VITE_LIFF_ID;

export const useLiff = () => {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [profile, setProfile] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const initLiff = async () => {
      try {
        await liff.init({ liffId: LIFF_ID });
        
        if (liff.isLoggedIn()) {
          setIsLoggedIn(true);
          const userProfile = await liff.getProfile();
          setProfile(userProfile);
        } else {
          liff.login();
        }
      } catch (err: any) {
        setError(err.message);
        console.error('LIFF 初始化失敗:', err);
      }
    };

    initLiff();
  }, []);

  return { isLoggedIn, profile, error };
};
```

---

#### 5.2.3 在首頁使用 LIFF（`src/pages/Home.tsx`）

```typescript
import React, { useEffect, useState } from 'react';
import { healthCheck } from '../services/api';
import { useLiff } from '../hooks/useLiff';

const Home: React.FC = () => {
  const [apiStatus, setApiStatus] = useState<string>('checking...');
  const { isLoggedIn, profile, error } = useLiff();

  useEffect(() => {
    const checkAPI = async () => {
      try {
        const data = await healthCheck();
        setApiStatus(data.message);
      } catch (error) {
        setApiStatus('API 連線失敗');
        console.error(error);
      }
    };
    checkAPI();
  }, []);

  if (error) {
    return <div>LIFF 錯誤: {error}</div>;
  }

  if (!isLoggedIn) {
    return <div>正在登入...</div>;
  }

  return (
    <div style={{ padding: '20px' }}>
      <h1>HOLOLIVE Card Game</h1>
      <p>歡迎, {profile?.displayName}!</p>
      <img src={profile?.pictureUrl} alt="avatar" style={{ width: '100px', borderRadius: '50%' }} />
      <p>API 狀態: {apiStatus}</p>
      <button onClick={() => window.location.href = '/match/test'}>
        開始測試對戰
      </button>
    </div>
  );
};

export default Home;
```

---

### 5.3 後端實作 LINE Login API

#### 5.3.1 建立 DTO（`src/main/java/com/hololive/cardgame/dto/LineLoginRequest.java`）

```java
package com.hololive.cardgame.dto;

import lombok.Data;

@Data
public class LineLoginRequest {
    private String lineUserId;
    private String displayName;
    private String pictureUrl;
}
```

#### 5.3.2 建立 Service（`src/main/java/com/hololive/cardgame/service/UserService.java`）

```java
package com.hololive.cardgame.service;

import com.hololive.cardgame.dto.LineLoginRequest;
import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    
    @Autowired
    private UserRepository userRepository;
    
    public User loginOrRegister(LineLoginRequest request) {
        return userRepository.findByLineUserId(request.getLineUserId())
            .orElseGet(() -> {
                User newUser = new User();
                newUser.setLineUserId(request.getLineUserId());
                newUser.setDisplayName(request.getDisplayName());
                newUser.setAvatarUrl(request.getPictureUrl());
                return userRepository.save(newUser);
            });
    }
}
```

#### 5.3.3 更新 Controller（`UserController.java`）

```java
@PostMapping("/line-login")
public User lineLogin(@RequestBody LineLoginRequest request) {
    return userService.loginOrRegister(request);
}
```

---

## 階段六：部署準備

### 6.1 部署後端到 Render

#### 步驟

1. **將專案推送到 GitHub**
   ```bash
   git init
   git add .
   git commit -m "Initial commit"
   git remote add origin <your-github-repo-url>
   git push -u origin main
   ```

2. **在 Render 建立 Web Service**
   - 登入 https://render.com
   - 點擊「New +」→「Web Service」
   - 連接 GitHub repository
   - 配置：
     - Name：`hololive-cardgame-api`
     - Environment：Java
     - Build Command：`./mvnw clean install`
     - Start Command：`java -jar target/cardgame-0.0.1-SNAPSHOT.jar`

3. **在 Render 建立 PostgreSQL**
   - 點擊「New +」→「PostgreSQL」
   - 記下連線資訊

4. **設定環境變數**
   - 在 Web Service 的「Environment」設定：
     ```
     SPRING_DATASOURCE_URL=<render-postgres-url>
     SPRING_DATASOURCE_USERNAME=<username>
     SPRING_DATASOURCE_PASSWORD=<password>
     ```

---

### 6.2 部署前端到 Vercel

```bash
# 在 client/ 目錄
npm run build

# 安裝 Vercel CLI
npm install -g vercel

# 部署
vercel

# 依照提示完成設定
```

更新 `.env.production`：
```env
VITE_API_BASE_URL=https://hololive-cardgame-api.onrender.com/api
```

---

## 階段七：開發流程建議

### 7.1 功能開發順序

按照優先順序開發以下功能：

1. **第一週**
   - 完成使用者登入（LINE LIFF）
   - 建立房間系統（建立、加入房間）

2. **第二週**
   - 實作對戰初始化（選推し、洗牌、起手）
   - 基本回合流程（抽牌、エール、結束回合）

3. **第三週**
   - 出場ホロメン
   - 使用アーツ攻擊

4. **第四週**
   - COLLAB 與 ホロパワー
   - 使用推しスキル

5. **第五週**
   - Bloom（進化）
   - サポートカード

6. **第六週以後**
   - 完整規則細化
   - UI/UX 優化
   - 測試與除錯

---

### 7.2 開發習慣建議

- **每天 commit**：保持小步快跑，方便回溯
- **寫測試**：重要邏輯要寫單元測試
- **API 文檔**：用 Swagger / Postman 記錄 API
- **Code Review**：定期檢視程式碼品質

---

## 階段八：常見問題與除錯

### 8.1 資料庫連線失敗

**錯誤訊息**：`Unable to connect to database`

**解決方式**：
- 檢查 `application.yml` 中的連線資訊是否正確
- 確認 PostgreSQL 服務是否啟動
- 檢查防火牆設定

---

### 8.2 CORS 錯誤

**錯誤訊息**：`Access to fetch at '...' from origin '...' has been blocked by CORS policy`

**解決方式**：

在 Spring Boot 加入 CORS 配置（`src/main/java/com/hololive/cardgame/config/WebConfig.java`）：

```java
package com.hololive.cardgame.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://localhost:3000", "https://your-frontend-url.com")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowCredentials(true);
    }
}
```

---

### 8.3 LIFF 初始化失敗

**錯誤訊息**：`LIFF ID is not valid`

**解決方式**：
- 檢查 `.env` 中的 `VITE_LIFF_ID` 是否正確
- 確認 LIFF Endpoint URL 是否設定正確
- 在 LINE Developers Console 確認 LIFF App 狀態

---

## 附錄

### A. 完整套件清單

#### 後端（Spring Boot）
- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `postgresql`
- `lombok`
- `spring-boot-starter-validation`
- `spring-boot-devtools`

#### 前端（React）
- `react`
- `react-dom`
- `react-router-dom`
- `@line/liff`
- `axios`
- `typescript`

---

### B. 參考資料

- [Spring Boot 官方文件](https://spring.io/projects/spring-boot)
- [LINE LIFF 文件](https://developers.line.biz/en/docs/liff/)
- [PostgreSQL 文件](https://www.postgresql.org/docs/)
- [React 官方文件](https://react.dev/)

---

## 結語

這份文件涵蓋了從環境準備到基礎功能實作的完整流程。建議按照階段循序漸進開發，遇到問題時可以回頭參考相關章節。

祝開發順利！
