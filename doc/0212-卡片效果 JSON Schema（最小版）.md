# 卡片效果 JSON Schema（最小版）

## 概述

本文件定義卡片效果的 JSON Schema，用於前後端共用，確保效果格式一致性。**此為最小版本**，先支援基本效果類型，複雜效果後續擴充。

---

## 1. 效果類型（Effect Types）

### 1.1 支援的效果類型清單

| 效果類型 | 說明 | 觸發時機 | 範例 |
|---------|------|---------|------|
| `DAMAGE` | 造成傷害 | アーツ、推しスキル | 對敵方センター造成 50 傷害 |
| `HEAL` | 回復傷害 | サポートカード、被動效果 | 回復我方センター 30 傷害 |
| `DRAW` | 抽牌 | サポートカード、被動效果 | 抽 2 張牌 |
| `SEARCH` | 從牌庫搜尋 | サポートカード | 從牌庫搜尋 1 張ホロメン |
| `BUFF` | 增益效果 | サポートカード、被動效果 | 我方所有ホロメン +20 傷害（本回合） |
| `DEBUFF` | 減益效果 | サポートカード、被動效果 | 敵方センター -30 傷害（本回合） |
| `MOVE_ZONE` | 移動卡片到其他區域 | サポートカード、被動效果 | 將手牌中 1 張卡送到アーカイブ |
| `CHANGE_ZONE` | 改變ホロメン位置 | バトンタッチ | センター與バック交換位置 |
| `SET_FLAG` | 設定狀態標記 | 被動效果 | 標記「本回合已使用推しスキル」 |
| `ADD_CHEER` | 附加エール | サポートカード、被動效果 | 從エールデッキ取 2 張附給我方センター |
| `REMOVE_CHEER` | 移除エール | 推しスキル、被動效果 | 從敵方センター移除 1 張エール |
| `GENERATE_HOLOPOWER` | 產生ホロパワー | コラボ | 從牌庫抽 1 張到ホロパワーゾーン |

---

## 2. JSON Schema 定義

### 2.1 基礎結構

所有效果都遵循以下基礎結構：

```json
{
  "effectType": "DAMAGE | HEAL | DRAW | ...",
  "target": {
    "targetType": "SELF | OPPONENT | SPECIFIC | ALL",
    "targetZone": "CENTER | COLLAB | BACK | HAND | DECK | ...",
    "targetId": null  // 若 targetType 為 SPECIFIC，則填入具體 ID
  },
  "value": 0,  // 效果數值（傷害、回復、抽牌數等）
  "condition": null,  // 觸發條件（可選）
  "duration": "INSTANT | TURN | PERMANENT"  // 效果持續時間
}
```

---

### 2.2 `target` 欄位定義

#### `targetType`（必填）

| 值 | 說明 | 適用情境 |
|----|------|---------|
| `SELF` | 施放者自己 | 回復自己的センター |
| `OPPONENT` | 對手 | 攻擊對手的センター |
| `SPECIFIC` | 特定目標 | 玩家選擇的目標（需填 `targetId`） |
| `ALL_SELF` | 我方所有 | 我方所有ホロメン獲得增益 |
| `ALL_OPPONENT` | 對方所有 | 對方所有ホロメン獲得減益 |
| `ALL` | 所有玩家 | 雙方都受影響 |

#### `targetZone`（選填）

限制目標所在區域：

- `CENTER`：センター
- `COLLAB`：コラボ
- `BACK`：バック
- `STAGE`：場上所有位置（CENTER + COLLAB + BACK）
- `HAND`：手牌
- `DECK`：牌庫
- `HOLOPOWER`：ホロパワーゾーン
- `ARCHIVE`：アーカイブ
- `LIFE`：推しライフ

---

### 2.3 `value` 欄位定義

數值型欄位，根據效果類型有不同意義：

| 效果類型 | `value` 意義 | 範例 |
|---------|-------------|------|
| `DAMAGE` | 傷害數值 | `50` = 造成 50 傷害 |
| `HEAL` | 回復數值 | `30` = 回復 30 傷害 |
| `DRAW` | 抽牌數量 | `2` = 抽 2 張牌 |
| `BUFF` | 增益數值 | `20` = +20 傷害 |
| `DEBUFF` | 減益數值 | `-30` = -30 傷害 |
| `ADD_CHEER` | エール 數量 | `2` = 附加 2 張エール |
| `REMOVE_CHEER` | エール 數量 | `1` = 移除 1 張エール |

---

### 2.4 `condition` 欄位定義（可選）

觸發條件，用於條件效果（例如：GIFT、COLLAB 條件等）。

```json
{
  "conditionType": "GIFT | COLLAB | HP_BELOW | COLOR_MATCH | ...",
  "conditionValue": {}  // 條件參數
}
```

#### 常見條件類型

| 條件類型 | 說明 | `conditionValue` 範例 |
|---------|------|---------------------|
| `GIFT` | GIFT 效果（入場時觸發） | `null` |
| `COLLAB` | COLLAB 效果（執行コラボ時觸發） | `null` |
| `HP_BELOW` | HP 低於某值時觸發 | `{"threshold": 50}` |
| `COLOR_MATCH` | 附著特定顏色エール時觸發 | `{"color": "GREEN"}` |
| `TAG_MATCH` | ホロメン 擁有特定標籤時觸發 | `{"tag": "JP"}` |

---

### 2.5 `duration` 欄位定義（必填）

效果持續時間：

| 值 | 說明 | 範例 |
|----|------|------|
| `INSTANT` | 立即生效，不持續 | 造成傷害、抽牌 |
| `TURN` | 持續到回合結束 | +20 傷害（本回合） |
| `PERMANENT` | 永久持續（直到離場） | 永久 +10 HP |

---

## 3. 效果範例

### 3.1 `DAMAGE`（造成傷害）

```json
{
  "effectType": "DAMAGE",
  "target": {
    "targetType": "OPPONENT",
    "targetZone": "CENTER"
  },
  "value": 50,
  "duration": "INSTANT"
}
```

**說明**：對敵方センター造成 50 傷害。

---

### 3.2 `HEAL`（回復傷害）

```json
{
  "effectType": "HEAL",
  "target": {
    "targetType": "SELF",
    "targetZone": "CENTER"
  },
  "value": 30,
  "duration": "INSTANT"
}
```

**說明**：回復我方センター 30 傷害。

---

### 3.3 `DRAW`（抽牌）

```json
{
  "effectType": "DRAW",
  "target": {
    "targetType": "SELF"
  },
  "value": 2,
  "duration": "INSTANT"
}
```

**說明**：抽 2 張牌。

---

### 3.4 `BUFF`（增益效果）

```json
{
  "effectType": "BUFF",
  "target": {
    "targetType": "ALL_SELF",
    "targetZone": "STAGE"
  },
  "value": 20,
  "duration": "TURN"
}
```

**說明**：我方場上所有ホロメン +20 傷害（本回合）。

---

### 3.5 `ADD_CHEER`（附加エール）

```json
{
  "effectType": "ADD_CHEER",
  "target": {
    "targetType": "SPECIFIC",
    "targetZone": "CENTER",
    "targetId": null  // 由玩家選擇
  },
  "value": 2,
  "duration": "INSTANT",
  "condition": {
    "conditionType": "GIFT"
  }
}
```

**說明**：GIFT 效果，從エールデッキ取 2 張附給我方センター。

---

### 3.6 `SEARCH`（從牌庫搜尋）

```json
{
  "effectType": "SEARCH",
  "target": {
    "targetType": "SELF",
    "targetZone": "DECK"
  },
  "value": 1,
  "duration": "INSTANT",
  "searchCriteria": {
    "cardType": "MEMBER",
    "level": "DEBUT",
    "tag": "JP"
  }
}
```

**說明**：從牌庫搜尋 1 張 JP 標籤的 Debut ホロメン 加入手牌。

---

### 3.7 `MOVE_ZONE`（移動卡片）

```json
{
  "effectType": "MOVE_ZONE",
  "target": {
    "targetType": "SPECIFIC",
    "targetZone": "HAND"
  },
  "value": 1,
  "duration": "INSTANT",
  "destinationZone": "ARCHIVE"
}
```

**說明**：從手牌選擇 1 張卡送到アーカイブ。

---

### 3.8 複合效果（多個效果組合）

```json
{
  "effects": [
    {
      "effectType": "DAMAGE",
      "target": {
        "targetType": "OPPONENT",
        "targetZone": "CENTER"
      },
      "value": 50,
      "duration": "INSTANT"
    },
    {
      "effectType": "DRAW",
      "target": {
        "targetType": "SELF"
      },
      "value": 1,
      "duration": "INSTANT"
    }
  ]
}
```

**說明**：造成 50 傷害 + 抽 1 張牌。

---

## 4. 資料庫儲存格式

### 4.1 `member_arts` 表的 `effect_json`

アーツ的效果儲存在 `effect_json` 欄位（JSONB 型別）：

```sql
-- 範例：ホロメンA 的 Debut アーツ
INSERT INTO member_arts (card_id, art_name, damage, cost_cheer_json, effect_json)
VALUES (
  'HLM-001',
  'キュートパンチ',
  50,
  '{"WHITE": 1, "GREEN": 2}',
  '{"effectType": "DAMAGE", "target": {"targetType": "OPPONENT", "targetZone": "CENTER"}, "value": 50, "duration": "INSTANT"}'
);
```

---

### 4.2 `support_cards` 表的 `effect_json`

サポートカード 的效果：

```sql
-- 範例：抽牌サポート
INSERT INTO support_cards (card_id, card_name, card_type, limited_type, effect_json)
VALUES (
  'SUP-001',
  '緊急補給',
  'SUPPORT',
  'LIMITED',
  '{"effectType": "DRAW", "target": {"targetType": "SELF"}, "value": 2, "duration": "INSTANT"}'
);
```

---

### 4.3 `member_cards` 表的 `gift_effect_json`

GIFT 效果：

```sql
-- 範例：GIFT 效果
UPDATE member_cards
SET gift_effect_json = '{
  "effectType": "ADD_CHEER",
  "target": {"targetType": "SPECIFIC", "targetZone": "CENTER"},
  "value": 2,
  "duration": "INSTANT",
  "condition": {"conditionType": "GIFT"}
}'
WHERE card_id = 'HLM-001';
```

---

## 5. 後端效果處理器（Effect Processor）

### 5.1 效果處理介面

```java
public interface EffectProcessor {
    /**
     * 處理效果
     * @param effect 效果 JSON
     * @param context 執行上下文（對戰狀態、施放者、目標等）
     * @return 狀態變更列表
     */
    List<StateChange> processEffect(EffectJson effect, EffectContext context);
}
```

---

### 5.2 實作範例：傷害處理器

```java
@Component
public class DamageEffectProcessor implements EffectProcessor {
    
    @Override
    public List<StateChange> processEffect(EffectJson effect, EffectContext context) {
        // 1. 解析目標
        List<MatchHolomem> targets = resolveTargets(effect.getTarget(), context);
        
        // 2. 計算傷害（考慮增益／減益）
        int baseDamage = effect.getValue();
        int finalDamage = calculateFinalDamage(baseDamage, context);
        
        // 3. 套用傷害
        List<StateChange> changes = new ArrayList<>();
        for (MatchHolomem target : targets) {
            target.setDamageTaken(target.getDamageTaken() + finalDamage);
            
            changes.add(new StateChange(
                StateChangeType.DAMAGE_DEALT,
                Map.of(
                    "targetId", target.getId(),
                    "damage", finalDamage,
                    "newDamageTaken", target.getDamageTaken()
                )
            ));
            
            // 4. 檢查是否倒下
            if (isHolomemDown(target)) {
                changes.addAll(handleHolomemDown(target, context));
            }
        }
        
        return changes;
    }
}
```

---

### 5.3 效果處理器工廠

```java
@Component
public class EffectProcessorFactory {
    
    @Autowired
    private Map<String, EffectProcessor> processors;
    
    public EffectProcessor getProcessor(String effectType) {
        EffectProcessor processor = processors.get(effectType.toLowerCase() + "Processor");
        if (processor == null) {
            throw new RuntimeException("不支援的效果類型: " + effectType);
        }
        return processor;
    }
    
    public List<StateChange> processEffect(EffectJson effect, EffectContext context) {
        EffectProcessor processor = getProcessor(effect.getEffectType());
        return processor.processEffect(effect, context);
    }
}
```

---

## 6. 前端效果顯示

### 6.1 效果文字轉換

```typescript
const getEffectDescription = (effect: EffectJson): string => {
  switch (effect.effectType) {
    case 'DAMAGE':
      return `對${getTargetText(effect.target)}造成 ${effect.value} 傷害`;
    case 'HEAL':
      return `回復${getTargetText(effect.target)} ${effect.value} 傷害`;
    case 'DRAW':
      return `抽 ${effect.value} 張牌`;
    case 'BUFF':
      return `${getTargetText(effect.target)} +${effect.value} 傷害（本回合）`;
    case 'ADD_CHEER':
      return `從エールデッキ取 ${effect.value} 張附給${getTargetText(effect.target)}`;
    default:
      return '未知效果';
  }
};

const getTargetText = (target: EffectTarget): string => {
  if (target.targetType === 'OPPONENT' && target.targetZone === 'CENTER') {
    return '敵方センター';
  }
  if (target.targetType === 'SELF' && target.targetZone === 'CENTER') {
    return '我方センター';
  }
  if (target.targetType === 'ALL_SELF' && target.targetZone === 'STAGE') {
    return '我方場上所有ホロメン';
  }
  return '目標';
};
```

---

### 6.2 效果圖示

```typescript
const getEffectIcon = (effectType: string): string => {
  switch (effectType) {
    case 'DAMAGE':
      return '⚔️';
    case 'HEAL':
      return '❤️';
    case 'DRAW':
      return '📄';
    case 'BUFF':
      return '⬆️';
    case 'DEBUFF':
      return '⬇️';
    case 'ADD_CHEER':
      return '✨';
    default:
      return '❓';
  }
};
```

---

## 7. Schema 檔案（供前後端共用）

### 7.1 `effect-schema.json`

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Card Effect Schema",
  "type": "object",
  "required": ["effectType", "target", "value", "duration"],
  "properties": {
    "effectType": {
      "type": "string",
      "enum": [
        "DAMAGE", "HEAL", "DRAW", "SEARCH", "BUFF", "DEBUFF",
        "MOVE_ZONE", "CHANGE_ZONE", "SET_FLAG", "ADD_CHEER",
        "REMOVE_CHEER", "GENERATE_HOLOPOWER"
      ]
    },
    "target": {
      "type": "object",
      "required": ["targetType"],
      "properties": {
        "targetType": {
          "type": "string",
          "enum": ["SELF", "OPPONENT", "SPECIFIC", "ALL_SELF", "ALL_OPPONENT", "ALL"]
        },
        "targetZone": {
          "type": "string",
          "enum": ["CENTER", "COLLAB", "BACK", "STAGE", "HAND", "DECK", "HOLOPOWER", "ARCHIVE", "LIFE"]
        },
        "targetId": {
          "type": ["number", "null"]
        }
      }
    },
    "value": {
      "type": "number"
    },
    "condition": {
      "type": ["object", "null"],
      "properties": {
        "conditionType": {
          "type": "string",
          "enum": ["GIFT", "COLLAB", "HP_BELOW", "COLOR_MATCH", "TAG_MATCH"]
        },
        "conditionValue": {
          "type": "object"
        }
      }
    },
    "duration": {
      "type": "string",
      "enum": ["INSTANT", "TURN", "PERMANENT"]
    },
    "searchCriteria": {
      "type": "object"
    },
    "destinationZone": {
      "type": "string"
    }
  }
}
```

---

## 8. 驗證與測試

### 8.1 效果驗證

```java
@Test
public void testDamageEffect() {
    // Given: 效果 JSON
    EffectJson effect = EffectJson.builder()
        .effectType("DAMAGE")
        .target(new EffectTarget("OPPONENT", "CENTER"))
        .value(50)
        .duration("INSTANT")
        .build();
    
    // When: 處理效果
    List<StateChange> changes = effectProcessor.processEffect(effect, context);
    
    // Then: 應產生傷害狀態變更
    assertEquals(1, changes.size());
    assertEquals(StateChangeType.DAMAGE_DEALT, changes.get(0).getType());
    assertEquals(50, changes.get(0).getData().get("damage"));
}
```

---

## 總結

本文件定義了卡片效果的 JSON Schema（最小版），包括：

1. **12 種基本效果類型**：傷害、回復、抽牌、增益、附加エール 等
2. **統一的 JSON 格式**：`effectType`, `target`, `value`, `duration`
3. **資料庫儲存方式**：使用 JSONB 欄位
4. **後端效果處理器**：模組化設計，易於擴充
5. **前端顯示邏輯**：效果文字與圖示

此 Schema 可供前後端共用，確保效果格式一致性。未來可根據需求擴充更多效果類型。
