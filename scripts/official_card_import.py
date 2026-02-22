#!/usr/bin/env python3
"""
官方卡表文字頁匯入工具（第一版）

用途：
1) 抓取官方 cardsearch 的 text view 頁面
2) 解析卡片基本資料、推し技能、ホロメンアーツ
3) 輸出可重複執行的 SQL（搭配 Flyway migration）
"""

from __future__ import annotations

import argparse
import html
import json
import re
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

BASE_URL = "https://hololive-official-cardgame.com"

CARD_TYPE_MAP = {
    "推しホロメン": "OSHI",
    "ホロメン": "MEMBER",
    "サポート": "SUPPORT",
    "エール": "CHEER",
}

COLOR_MAP = {
    "白": "WHITE",
    "緑": "GREEN",
    "赤": "RED",
    "青": "BLUE",
    "黄": "YELLOW",
    "紫": "PURPLE",
    "◇": "COLORLESS",
}

LEVEL_MAP = {
    "Debut": "DEBUT",
    "1st": "FIRST",
    "2nd": "SECOND",
    "Spot": "SPOT",
    "Buzz": "BUZZ",
}

BLOOM_LEVEL_NUMERIC_MAP = {
    "Debut": 0,
    "1st": 1,
    "2nd": 2,
    "Spot": 0,
    "Buzz": 2,
}


@dataclass
class SkillData:
    skill_type: str
    skill_name: str
    holopower_cost: int
    description: str
    effect_json: dict[str, Any]


@dataclass
class ArtData:
    name: str
    description: str
    cost_cheer_json: dict[str, int]
    effect_json: dict[str, Any]
    order_index: int


@dataclass
class CardRecord:
    card_id: str
    name: str
    rarity: str
    image_url: str | None
    card_type: str
    expansion_code: str
    source_url: str
    tags: list[str] = field(default_factory=list)
    main_color: str | None = None
    life: int | None = None
    hp: int | None = None
    level_type: str | None = None
    bloom_level: int | None = None
    oshi_skills: list[SkillData] = field(default_factory=list)
    member_arts: list[ArtData] = field(default_factory=list)
    member_extra: dict[str, str] = field(default_factory=dict)


def fetch_html(expansion: str, sort: str) -> str:
    url = f"{BASE_URL}/cardlist/cardsearch/?expansion={expansion}&view=text&sort={sort}"
    with urllib.request.urlopen(url) as response:
        return response.read().decode("utf-8")


def clean_text(fragment: str) -> str:
    text = re.sub(r"<img[^>]*alt=\"([^\"]*)\"[^>]*>", r"\1", fragment)
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.IGNORECASE)
    text = re.sub(r"<[^>]+>", "", text)
    text = html.unescape(text)
    text = text.replace("\u3000", " ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n\s*", "\n", text)
    return text.strip()


def extract_img_alts(fragment: str) -> list[str]:
    return re.findall(r"<img[^>]*alt=\"([^\"]*)\"[^>]*>", fragment)


def sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def sql_nullable(value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)):
        return str(value)
    return sql_quote(str(value))


def sql_json(value: Any) -> str:
    return sql_quote(json.dumps(value, ensure_ascii=False)) + "::jsonb"


def normalize_art_name(span_text: str) -> str:
    # 去掉前方顏色符號，再保留原始數值（例如 60、90+）。
    return re.sub(r"^[赤青緑黄紫白◇\s]+", "", span_text).strip()


def parse_cost_from_span(span_html: str) -> dict[str, int]:
    cost: dict[str, int] = {}
    for alt in extract_img_alts(span_html):
        mapped = COLOR_MAP.get(alt)
        if mapped is None:
            continue
        cost[mapped] = cost.get(mapped, 0) + 1
    return cost


def parse_skill_html(skill_html: str, skill_type: str) -> SkillData:
    skill_name_match = re.search(r"<span>(.*?)</span>", skill_html, flags=re.DOTALL)
    skill_name = clean_text(skill_name_match.group(1)) if skill_name_match else "未命名技能"
    whole_text = clean_text(skill_html)
    cost_match = re.search(r"ホロパワー：-?(\d+)", whole_text)
    holopower_cost = int(cost_match.group(1)) if cost_match else 0

    # 描述去掉成本與技能名（保留原文敘述，方便後續 effect_json 映射）
    description = whole_text
    description = re.sub(r"^\[ホロパワー：-?\d+\]", "", description).strip()
    description = description.replace(skill_name, "", 1).strip()

    return SkillData(
        skill_type=skill_type,
        skill_name=skill_name,
        holopower_cost=holopower_cost,
        description=description,
        effect_json={"type": "UNIMPLEMENTED", "rawText": description},
    )


def parse_member_arts(block_html: str) -> list[ArtData]:
    arts: list[ArtData] = []
    art_blocks = re.findall(
        r"<div class=\"sp arts\"><p>アーツ</p><p>(.*?)</p></div>",
        block_html,
        flags=re.DOTALL,
    )

    for index, art_html in enumerate(art_blocks):
        span_match = re.search(r"<span>(.*?)</span>", art_html, flags=re.DOTALL)
        if not span_match:
            continue
        span_html = span_match.group(1)
        span_text = clean_text(span_html)
        name = normalize_art_name(span_text)
        cost = parse_cost_from_span(span_html)

        effect_text = clean_text(re.sub(r"<span>.*?</span>", "", art_html, count=1, flags=re.DOTALL))
        description = effect_text if effect_text else None

        arts.append(
            ArtData(
                name=name,
                description=description or "",
                cost_cheer_json=cost if cost else {"COLORLESS": 1},
                effect_json={
                    "type": "UNIMPLEMENTED",
                    "rawHeader": span_text,
                    "rawEffect": effect_text,
                },
                order_index=index,
            )
        )

    return arts


def parse_cards(html_text: str, expansion_code: str) -> list[CardRecord]:
    cards: list[CardRecord] = []
    blocks = re.findall(
        r"<li>\s*<a href=\"(/cardlist/\?id=[^\"]+)\">(.*?)</a>\s*</li>",
        html_text,
        flags=re.DOTALL,
    )

    for href, block in blocks:
        card_id_match = re.search(r"<p class=\"number\">(.*?)</p>", block, flags=re.DOTALL)
        name_match = re.search(r"<p class=\"name\">(.*?)</p>", block, flags=re.DOTALL)
        if not card_id_match or not name_match:
            continue

        card_id = clean_text(card_id_match.group(1)).upper()
        name = clean_text(name_match.group(1))
        image_match = re.search(r"<div class=\"img w100\"><img src=\"([^\"]+)\"", block)
        image_url = BASE_URL + image_match.group(1) if image_match else None

        info_pairs = re.findall(r"<dt>(.*?)</dt>\s*<dd>(.*?)</dd>", block, flags=re.DOTALL)
        info_map: dict[str, str] = {}
        for dt_raw, dd_raw in info_pairs:
            info_map[clean_text(dt_raw)] = clean_text(dd_raw)

        card_type_jp = info_map.get("カードタイプ", "")
        card_type = CARD_TYPE_MAP.get(card_type_jp)
        if card_type is None:
            continue

        rarity = info_map.get("レアリティ", "")
        tags = [tag for tag in info_map.get("タグ", "").split(" ") if tag.startswith("#")]

        color_dd_raw = next((dd for dt, dd in info_pairs if clean_text(dt) == "色"), "")
        color_alts = extract_img_alts(color_dd_raw)
        main_color = None
        for alt in color_alts:
            main_color = COLOR_MAP.get(alt)
            if main_color:
                break

        life = int(info_map["LIFE"]) if info_map.get("LIFE", "").isdigit() else None
        hp = int(info_map["HP"]) if info_map.get("HP", "").isdigit() else None
        bloom_raw = info_map.get("Bloomレベル")
        level_type = LEVEL_MAP.get(bloom_raw) if bloom_raw else None
        bloom_level = BLOOM_LEVEL_NUMERIC_MAP.get(bloom_raw) if bloom_raw else None

        record = CardRecord(
            card_id=card_id,
            name=name,
            rarity=rarity,
            image_url=image_url,
            card_type=card_type,
            expansion_code=expansion_code.upper(),
            source_url=BASE_URL + href,
            tags=tags,
            main_color=main_color,
            life=life,
            hp=hp,
            level_type=level_type,
            bloom_level=bloom_level,
        )

        if card_type == "OSHI":
            normal_skill_match = re.search(
                r"<div class=\"oshi skill\"><p>推しスキル</p><p>(.*?)</p></div>",
                block,
                flags=re.DOTALL,
            )
            sp_skill_match = re.search(
                r"<div class=\"sp skill\"><p>SP推しスキル</p><p>(.*?)</p></div>",
                block,
                flags=re.DOTALL,
            )
            if normal_skill_match:
                record.oshi_skills.append(parse_skill_html(normal_skill_match.group(1), "NORMAL"))
            if sp_skill_match:
                record.oshi_skills.append(parse_skill_html(sp_skill_match.group(1), "SP"))

        if card_type == "MEMBER":
            record.member_arts = parse_member_arts(block)
            keyword_match = re.search(
                r"<div class=\"keyword\"><p>(.*?)</p><p>(.*?)</p></div>",
                block,
                flags=re.DOTALL,
            )
            extra_match = re.search(
                r"<div class=\"extra\"><p>(.*?)</p><p>(.*?)</p></div>",
                block,
                flags=re.DOTALL,
            )
            if keyword_match:
                record.member_extra[clean_text(keyword_match.group(1))] = clean_text(keyword_match.group(2))
            if extra_match:
                record.member_extra[clean_text(extra_match.group(1))] = clean_text(extra_match.group(2))

        cards.append(record)

    return cards


def build_sql(cards: list[CardRecord], source_url: str, expansion_code: str) -> str:
    lines: list[str] = []
    lines.append(f"-- 匯入官方卡表批次資料（來源：{expansion_code.upper()}）")
    lines.append("-- 由 scripts/official_card_import.py 產生，請勿手動大幅改寫。")
    lines.append(f"-- Source: {source_url}")
    lines.append("")
    lines.append("BEGIN;")
    lines.append("")

    for card in cards:
        tags_json = card.tags if card.tags else []
        lines.append(
            "INSERT INTO cards (card_id, name, rarity, image_url, card_type, expansion_code, card_no, tags_json, source_url) VALUES "
            f"({sql_quote(card.card_id)}, {sql_quote(card.name)}, {sql_quote(card.rarity)}, {sql_nullable(card.image_url)}, "
            f"{sql_quote(card.card_type)}, {sql_quote(card.expansion_code)}, {sql_quote(card.card_id)}, {sql_json(tags_json)}, {sql_quote(card.source_url)}) "
            "ON CONFLICT (card_id) DO UPDATE SET "
            "name = EXCLUDED.name, rarity = EXCLUDED.rarity, image_url = EXCLUDED.image_url, "
            "card_type = EXCLUDED.card_type, expansion_code = EXCLUDED.expansion_code, card_no = EXCLUDED.card_no, "
            "tags_json = EXCLUDED.tags_json, source_url = EXCLUDED.source_url, updated_at = CURRENT_TIMESTAMP;"
        )

        if card.card_type == "OSHI":
            if card.life is None or card.main_color is None:
                raise ValueError(f"OSHI 卡片缺少 LIFE 或主色：{card.card_id}")
            lines.append(
                "INSERT INTO oshi_cards (card_id, life, main_color, sub_color) VALUES "
                f"({sql_quote(card.card_id)}, {card.life}, {sql_quote(card.main_color)}, NULL) "
                "ON CONFLICT (card_id) DO UPDATE SET "
                "life = EXCLUDED.life, main_color = EXCLUDED.main_color, sub_color = EXCLUDED.sub_color, "
                "updated_at = CURRENT_TIMESTAMP;"
            )
            lines.append(f"DELETE FROM oshi_skills WHERE oshi_card_id = {sql_quote(card.card_id)};")
            for skill in card.oshi_skills:
                lines.append(
                    "INSERT INTO oshi_skills (oshi_card_id, skill_type, skill_name, description, holopower_cost, effect_json) VALUES "
                    f"({sql_quote(card.card_id)}, {sql_quote(skill.skill_type)}, {sql_quote(skill.skill_name)}, "
                    f"{sql_quote(skill.description)}, {skill.holopower_cost}, {sql_json(skill.effect_json)});"
                )

        if card.card_type == "MEMBER":
            if card.hp is None or card.level_type is None or card.main_color is None:
                raise ValueError(f"MEMBER 卡片缺少 HP / Bloom / 色：{card.card_id}")
            passive_json = card.member_extra if card.member_extra else None
            lines.append(
                "INSERT INTO member_cards (card_id, hp, level_type, main_color, sub_color, bloom_level, passive_effect_json, trigger_condition) VALUES "
                f"({sql_quote(card.card_id)}, {card.hp}, {sql_quote(card.level_type)}, {sql_quote(card.main_color)}, NULL, "
                f"{sql_nullable(card.bloom_level)}, {sql_json(passive_json) if passive_json else 'NULL'}, NULL) "
                "ON CONFLICT (card_id) DO UPDATE SET "
                "hp = EXCLUDED.hp, level_type = EXCLUDED.level_type, main_color = EXCLUDED.main_color, "
                "sub_color = EXCLUDED.sub_color, bloom_level = EXCLUDED.bloom_level, "
                "passive_effect_json = EXCLUDED.passive_effect_json, trigger_condition = EXCLUDED.trigger_condition, "
                "updated_at = CURRENT_TIMESTAMP;"
            )
            lines.append(f"DELETE FROM member_arts WHERE member_card_id = {sql_quote(card.card_id)};")
            for art in card.member_arts:
                lines.append(
                    "INSERT INTO member_arts (member_card_id, name, description, cost_cheer_json, effect_json, order_index) VALUES "
                    f"({sql_quote(card.card_id)}, {sql_quote(art.name)}, {sql_quote(art.description)}, "
                    f"{sql_json(art.cost_cheer_json)}, {sql_json(art.effect_json)}, {art.order_index});"
                )

        lines.append("")

    lines.append("COMMIT;")
    lines.append("")
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="抓取官方卡表並產生 SQL migration")
    parser.add_argument("--expansion", default="hSD13", help="官方 expansion 代碼，例如 hSD13")
    parser.add_argument("--sort", default="new", help="排序參數（new/old/no...）")
    parser.add_argument(
        "--output",
        default="src/main/resources/db/migration/V9__seed_official_hsd13_batch_01.sql",
        help="輸出 SQL 檔案路徑",
    )
    parser.add_argument(
        "--input-html",
        default="",
        help="離線模式：指定已下載的 HTML 檔案，避免重新抓網頁",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    source_url = f"{BASE_URL}/cardlist/cardsearch/?expansion={args.expansion}&view=text&sort={args.sort}"
    html_text = Path(args.input_html).read_text(encoding="utf-8") if args.input_html else fetch_html(args.expansion, args.sort)

    cards = parse_cards(html_text, args.expansion)
    if not cards:
        raise RuntimeError("解析不到任何卡片，請檢查 expansion 代碼或頁面格式是否改版。")

    sql_text = build_sql(cards, source_url, args.expansion)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(sql_text, encoding="utf-8")
    print(f"[OK] 已產生 {output}，共 {len(cards)} 張卡片")


if __name__ == "__main__":
    main()
