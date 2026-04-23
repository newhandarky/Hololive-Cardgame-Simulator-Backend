#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

import analyze_card_coverage_gaps as coverage

ATTACHABLE_SUPPORT_STATIC_HP_PATTERN = re.compile(
    r"この(?:マスコット|ツール|ファン)が付いているホロメンのHP\s*[+＋−-]\s*\d+"
)
ATTACHABLE_SUPPORT_STATIC_ARTS_PATTERN = re.compile(
    r"この(?:マスコット|ツール|ファン)が付いているホロメンのアーツ\s*[+＋−-]\s*\d+"
)
ATTACHABLE_SUPPORT_DAMAGE_REDUCTION_PATTERN = re.compile(r"受けるダメージ\s*[−-]\s*\d+")


@dataclass(frozen=True)
class GroupDefinition:
    group_id: str
    label: str
    tier: str
    rationale: str
    selector: Callable[[dict[str, Any]], set[str]]


def ids_with_tag(context: dict[str, Any], tag: str) -> set[str]:
    features: dict[str, coverage.CardFeature] = context["features"]
    official_ids: list[str] = context["official_ids"]
    return {
        card_id
        for card_id in official_ids
        if tag in features.get(card_id, coverage.CardFeature()).tags
    }


def ids_with_any_tag(context: dict[str, Any], tags: list[str]) -> set[str]:
    return set().union(*(ids_with_tag(context, tag) for tag in tags))


def support_ids_with_any_tag(context: dict[str, Any], tags: list[str]) -> set[str]:
    cards: dict[str, coverage.CardMeta] = context["cards"]
    return {
        card_id
        for card_id in ids_with_any_tag(context, tags)
        if cards[card_id].card_type == "SUPPORT"
    }


def parse_member_art_risk(
    migration_path: Path,
    official_ids: list[str],
) -> tuple[set[str], set[str], int]:
    official_set = set(official_ids)
    art_counts: dict[str, int] = Counter()
    non_damage_alternate_cards: set[str] = set()

    for statement in coverage.split_sql_statements(migration_path.read_text(encoding="utf-8")):
        line = statement.strip()
        if not line.startswith("INSERT INTO member_arts "):
            continue
        values = coverage.split_sql_values(coverage.extract_values_segment(line))
        card_id = coverage.parse_sql_token(values[0])
        if card_id not in official_set:
            continue
        order_index = coverage.parse_sql_token(values[5])
        effect_json = coverage.parse_sql_token(values[4])
        effect_tokens = coverage.extract_effect_tokens(effect_json) or ["TEXT_ONLY"]
        art_counts[card_id] += 1
        if isinstance(order_index, int) and order_index > 0:
            if any(token != "DAMAGE" for token in effect_tokens):
                non_damage_alternate_cards.add(card_id)

    multi_art_cards = {card_id for card_id, count in art_counts.items() if count > 1}
    alternate_art_rows = sum(max(count - 1, 0) for count in art_counts.values())
    return multi_art_cards, non_damage_alternate_cards, alternate_art_rows


def parse_implemented_trigger_smoke_buckets(
    migration_path: Path,
    official_ids: list[str],
    trigger_family_ids: set[str] | None = None,
) -> dict[str, set[str]]:
    official_set = set(official_ids)
    bloom_cards: set[str] = set()
    collab_cards: set[str] = set()
    passive_gift_cards: set[str] = set()
    down_event_life_cards: set[str] = set()
    member_art_trigger_family_cards: set[str] = set()
    oshi_skill_cards: set[str] = set()
    support_non_attachable_cards: set[str] = set()
    support_attachable_static_bonus_cards: set[str] = set()
    support_attachable_damage_reduction_cards: set[str] = set()
    support_attachable_conditional_trigger_cards: set[str] = set()

    for statement in coverage.split_sql_statements(migration_path.read_text(encoding="utf-8")):
        line = statement.strip()
        if line.startswith("INSERT INTO oshi_skills "):
            values = coverage.split_sql_values(coverage.extract_values_segment(line))
            card_id = coverage.parse_sql_token(values[0])
            if card_id in official_set:
                oshi_skill_cards.add(card_id)
            continue

        if line.startswith("INSERT INTO support_cards "):
            values = coverage.split_sql_values(coverage.extract_values_segment(line))
            card_id = coverage.parse_sql_token(values[0])
            if card_id not in official_set:
                continue
            effect_json = coverage.parse_sql_token(values[5])
            effect_text = " ".join(coverage.iter_text_fragments(effect_json))
            base_effect_text = effect_text.split("◆", 1)[0]
            is_attachable_support = any(
                token in effect_text
                for token in ["サポート・マスコット", "サポート・ツール", "サポート・ファン"]
            )
            if not is_attachable_support:
                support_non_attachable_cards.add(card_id)
            elif (
                ATTACHABLE_SUPPORT_STATIC_HP_PATTERN.search(base_effect_text)
                or ATTACHABLE_SUPPORT_STATIC_ARTS_PATTERN.search(base_effect_text)
            ):
                support_attachable_static_bonus_cards.add(card_id)
            if is_auto_attached_support_damage_reduction(base_effect_text):
                support_attachable_damage_reduction_cards.add(card_id)
            if is_attached_support_conditional_trigger(base_effect_text):
                support_attachable_conditional_trigger_cards.add(card_id)
            continue

        if line.startswith("INSERT INTO member_arts "):
            values = coverage.split_sql_values(coverage.extract_values_segment(line))
            card_id = coverage.parse_sql_token(values[0])
            if card_id not in official_set:
                continue
            effect_json = coverage.parse_sql_token(values[4])
            if is_member_art_trigger_family_effect(effect_json):
                member_art_trigger_family_cards.add(card_id)
            continue

        if not line.startswith("INSERT INTO member_cards "):
            continue
        values = coverage.split_sql_values(coverage.extract_values_segment(line))
        card_id = coverage.parse_sql_token(values[0])
        if card_id not in official_set:
            continue
        passive_effect_json = coverage.parse_sql_token(values[6])
        passive_text = " ".join(coverage.iter_text_fragments(passive_effect_json))
        extra_text = ""
        if isinstance(passive_effect_json, dict):
            extra_value = passive_effect_json.get("エクストラ")
            if isinstance(extra_value, str):
                extra_text = extra_value
        elif isinstance(passive_effect_json, str):
            try:
                passive_node = json.loads(passive_effect_json)
            except json.JSONDecodeError:
                passive_node = None
            if isinstance(passive_node, dict):
                extra_value = passive_node.get("エクストラ")
                if isinstance(extra_value, str):
                    extra_text = extra_value
        if "ブルームエフェクト" in passive_text:
            bloom_cards.add(card_id)
        if "コラボエフェクト" in passive_text:
            collab_cards.add(card_id)
        if "ギフト" in passive_text:
            passive_gift_cards.add(card_id)
        if "このホロメンがダウンした時" in extra_text and "ライフ" in extra_text:
            down_event_life_cards.add(card_id)

    def restrict_to_trigger_family(card_ids: set[str]) -> set[str]:
        if trigger_family_ids is None:
            return card_ids
        return card_ids & trigger_family_ids

    return {
        "member-bloom-trigger-effect-smoke": restrict_to_trigger_family(bloom_cards),
        "member-art-trigger-family-smoke": restrict_to_trigger_family(member_art_trigger_family_cards),
        "member-collab-trigger-effect-smoke": restrict_to_trigger_family(collab_cards),
        "member-down-event-life-smoke": restrict_to_trigger_family(down_event_life_cards),
        "member-passive-gift-effect-smoke": restrict_to_trigger_family(passive_gift_cards),
        "oshi-trigger-skill-effect-engine-smoke": restrict_to_trigger_family(oshi_skill_cards),
        "support-attachable-damage-reduction-ongoing-smoke": restrict_to_trigger_family(
            support_attachable_damage_reduction_cards
        ),
        "support-attachable-conditional-trigger-smoke": restrict_to_trigger_family(
            support_attachable_conditional_trigger_cards
        ),
        "support-attachable-static-bonus-ongoing-smoke": restrict_to_trigger_family(
            support_attachable_static_bonus_cards
        ),
        "support-non-attachable-trigger-effect-engine-smoke": restrict_to_trigger_family(
            support_non_attachable_cards
        ),
    }


def is_auto_attached_support_damage_reduction(effect_text: str) -> bool:
    for clause in re.split(r"[。\n]", effect_text):
        if not clause.strip():
            continue
        if not any(
            token in clause
            for token in [
                "このマスコットが付いているホロメン",
                "このツールが付いているホロメン",
                "このファンが付いているホロメン",
            ]
        ):
            continue
        if "受けるダメージ" not in clause or "できる" in clause or "：" in clause:
            continue
        if ATTACHABLE_SUPPORT_DAMAGE_REDUCTION_PATTERN.search(clause):
            return True
    return False


def is_attached_support_conditional_trigger(effect_text: str) -> bool:
    for clause in re.split(r"[。\n]", effect_text):
        if not clause.strip():
            continue
        if not any(
            token in clause
            for token in [
                "このマスコットが付いているホロメン",
                "このツールが付いているホロメン",
                "このファンが付いているホロメン",
            ]
        ):
            continue
        if "ダウンした時" in clause or "ダメージを受ける時" in clause:
            return True
    return False


def is_member_art_trigger_family_effect(effect_json: Any) -> bool:
    effect_text = " ".join(coverage.iter_text_fragments(effect_json))
    tags = coverage.extract_text_tags(effect_text, "MEMBER_ART")
    return bool(
        tags
        & {
            "BLOOM_EFFECT",
            "COLLAB_EFFECT",
            "PASSIVE_GIFT",
            "SELF_DOWNED",
            "ALLY_DOWNED",
            "OPPONENT_DOWNED",
            "DAMAGE_RECEIVED",
            "DEAL_DAMAGE_TRIGGER",
        }
    )


def type_counts(card_ids: set[str], cards: dict[str, coverage.CardMeta]) -> dict[str, int]:
    counts = Counter(cards[card_id].card_type for card_id in card_ids)
    return dict(sorted(counts.items()))


def expansion_counts(card_ids: set[str]) -> dict[str, int]:
    counts = Counter(card_id.split("-")[0] for card_id in card_ids)
    return dict(counts.most_common())


def short_examples(
    card_ids: set[str],
    cards: dict[str, coverage.CardMeta],
    limit: int,
) -> list[str]:
    return [
        coverage.short_card_label(card_id, cards)
        for card_id in sorted(card_ids, key=coverage.card_sort_key)[:limit]
    ]


def group_rows(
    definitions: list[GroupDefinition],
    context: dict[str, Any],
) -> list[dict[str, Any]]:
    cards: dict[str, coverage.CardMeta] = context["cards"]
    rows: list[dict[str, Any]] = []
    for definition in definitions:
        card_ids = definition.selector(context)
        rows.append(
            {
                "id": definition.group_id,
                "label": definition.label,
                "tier": definition.tier,
                "rationale": definition.rationale,
                "count": len(card_ids),
                "card_type_counts": type_counts(card_ids, cards),
                "top_expansions": dict(list(expansion_counts(card_ids).items())[:8]),
                "examples": short_examples(card_ids, cards, 8),
                "card_ids": sorted(card_ids, key=coverage.card_sort_key),
            }
        )
    rows.sort(key=lambda row: (row["tier"], -row["count"], row["id"]))
    return rows


def build_group_definitions() -> list[GroupDefinition]:
    return [
        GroupDefinition(
            "bloom-effect",
            "Bloom text/effect",
            "core",
            "Bloom timing changes board state and often chains into once-per-turn or trigger behavior.",
            lambda context: ids_with_tag(context, "BLOOM_EFFECT"),
        ),
        GroupDefinition(
            "collab-effect",
            "Collab text/effect",
            "core",
            "Collab timing is phase- and position-sensitive and often has active trigger windows.",
            lambda context: ids_with_tag(context, "COLLAB_EFFECT"),
        ),
        GroupDefinition(
            "passive-gift",
            "Passive gift",
            "core",
            "Gift text is usually passive or delayed and needs event/timing regression coverage.",
            lambda context: ids_with_tag(context, "PASSIVE_GIFT"),
        ),
        GroupDefinition(
            "down-damage-timing",
            "Down/damage timing trigger",
            "core",
            "Downed, damage received, and damage dealt triggers are high-risk because order matters.",
            lambda context: ids_with_any_tag(
                context,
                [
                    "SELF_DOWNED",
                    "ALLY_DOWNED",
                    "OPPONENT_DOWNED",
                    "DAMAGE_RECEIVED",
                    "DEAL_DAMAGE_TRIGGER",
                ],
            ),
        ),
        GroupDefinition(
            "special-damage",
            "Special damage",
            "core",
            "Special damage bypasses normal art damage assumptions and frequently targets non-center slots.",
            lambda context: ids_with_tag(context, "SPECIAL_DAMAGE"),
        ),
        GroupDefinition(
            "dice",
            "Dice",
            "core",
            "Random branches need deterministic mock coverage for success and failure paths.",
            lambda context: ids_with_tag(context, "DICE"),
        ),
        GroupDefinition(
            "reattach",
            "Reattach",
            "core",
            "Moving attached resources is zone-sensitive and easy to regress when support/cheer schemas change.",
            lambda context: ids_with_tag(context, "REATTACH"),
        ),
        GroupDefinition(
            "archive-recursion",
            "Archive cheer/member recursion",
            "core",
            "Archive-to-stage, archive-to-hand, and archive cheer movement affects hidden zone assumptions.",
            lambda context: ids_with_any_tag(
                context,
                [
                    "ARCHIVE_CHEER",
                    "ARCHIVE_CHEER_ATTACH",
                    "ARCHIVE_MEMBER_TO_HAND",
                    "ARCHIVE_MEMBER_TO_DECK_TOP",
                ],
            ),
        ),
        GroupDefinition(
            "attachable-support",
            "Attachable support",
            "core",
            "Mascot, Tool, and Fan cards remain on board and can affect later timing windows.",
            lambda context: support_ids_with_any_tag(
                context,
                ["FAN_SUPPORT", "MASCOT_SUPPORT", "TOOL_SUPPORT"],
            ),
        ),
        GroupDefinition(
            "member-multi-art",
            "Member with multiple arts",
            "core",
            "Primary art smoke does not prove alternate art rows are executable or rule-correct.",
            lambda context: set(context["multi_art_cards"]),
        ),
        GroupDefinition(
            "member-non-damage-alternate-art",
            "Member with non-damage alternate art",
            "core",
            "Alternate art rows with effects beyond DAMAGE should be prioritized before plain damage alternates.",
            lambda context: set(context["non_damage_alternate_cards"]),
        ),
        GroupDefinition(
            "search-to-hand",
            "Search to hand",
            "watchlist",
            "Search effects are common; handle through representative tests unless a new selector pattern appears.",
            lambda context: ids_with_tag(context, "SEARCH_TO_HAND"),
        ),
        GroupDefinition(
            "turn-arts-buff",
            "Turn arts buff",
            "watchlist",
            "Turn-scoped buffs are broad; they become core risk when tied to position, color, or trigger timing.",
            lambda context: ids_with_tag(context, "TURN_ARTS_BUFF"),
        ),
    ]


def build_recommended_bucket_rows(rows: list[dict[str, Any]], cards: dict[str, coverage.CardMeta]) -> list[dict[str, Any]]:
    row_by_id = {row["id"]: row for row in rows}

    trigger_ids = set().union(
        row_by_id["bloom-effect"]["card_ids"],
        row_by_id["collab-effect"]["card_ids"],
        row_by_id["passive-gift"]["card_ids"],
        row_by_id["down-damage-timing"]["card_ids"],
    )
    mechanism_ids = set().union(
        row_by_id["special-damage"]["card_ids"],
        row_by_id["dice"]["card_ids"],
        row_by_id["reattach"]["card_ids"],
        row_by_id["archive-recursion"]["card_ids"],
    )
    alternate_art_ids = set(row_by_id["member-multi-art"]["card_ids"])
    attachable_support_ids = set(row_by_id["attachable-support"]["card_ids"])

    buckets = [
        (
            "bloom-collab-trigger-family",
            "Partially implemented",
            trigger_ids,
            "MEMBER trigger smoke plus existing OSHI skill and non-attachable SUPPORT effect-engine smoke are counted; remaining work is attachable SUPPORT ongoing effects and MEMBER timing edge paths.",
        ),
        (
            "special-mechanism-family",
            "Representative deep tests",
            mechanism_ids,
            "Prioritize deterministic tests for dice, special damage, reattach, and archive movement.",
        ),
        (
            "member-alternate-art-family",
            "Alternate art smoke/deep tests",
            alternate_art_ids,
            "Run non-primary art rows explicitly; start with non-damage alternate arts.",
        ),
        (
            "attachable-support-ongoing-family",
            "Ongoing support deep tests",
            attachable_support_ids,
            "Existing smoke proves play/attach; follow-up tests should prove ongoing effects.",
        ),
    ]
    return [
        {
            "id": bucket_id,
            "recommendation": recommendation,
            "count": len(card_ids),
            "card_type_counts": type_counts(card_ids, cards),
            "examples": short_examples(card_ids, cards, 8),
            "note": note,
        }
        for bucket_id, recommendation, card_ids, note in buckets
    ]


def build_implemented_smoke_rows(
    buckets: dict[str, set[str]],
    cards: dict[str, coverage.CardMeta],
) -> list[dict[str, Any]]:
    descriptions = {
        "member-bloom-trigger-effect-smoke": "Runs official MEMBER cards with ブルームエフェクト through Bloom preview/apply trigger entrypoints.",
        "member-art-trigger-family-smoke": "Runs official MEMBER art rows with trigger-family text through the shared effect engine smoke path.",
        "member-collab-trigger-effect-smoke": "Runs official MEMBER cards with コラボエフェクト through Collab preview/apply trigger entrypoints.",
        "member-down-event-life-smoke": "Runs official MEMBER cards with down-event extra life text through Down Event preview/apply entrypoints.",
        "member-passive-gift-effect-smoke": "Runs official MEMBER cards with ギフト through stored Gift trigger execution to smoke-test parser/executor compatibility.",
        "oshi-trigger-skill-effect-engine-smoke": "Runs official OSHI cards in the trigger family through existing OSHI skill effect-engine smoke.",
        "support-attachable-conditional-trigger-smoke": "Runs official attachable SUPPORT cards in the trigger family with holder downed/damage-received text through attached-support conditional trigger previews.",
        "support-attachable-damage-reduction-ongoing-smoke": "Runs official attachable SUPPORT cards in the trigger family with automatic incoming damage reduction through attached-support ongoing reduction resolvers.",
        "support-attachable-static-bonus-ongoing-smoke": "Runs official attachable SUPPORT cards in the trigger family with static HP/Arts bonuses through attached-support ongoing stat resolvers.",
        "support-non-attachable-trigger-effect-engine-smoke": "Runs official non-attachable SUPPORT cards in the trigger family through Support effect-engine smoke.",
    }
    rows: list[dict[str, Any]] = []
    for bucket_id, card_ids in sorted(buckets.items()):
        rows.append(
            {
                "id": bucket_id,
                "count": len(card_ids),
                "card_type_counts": type_counts(card_ids, cards),
                "examples": short_examples(card_ids, cards, 8),
                "description": descriptions.get(bucket_id, bucket_id),
                "card_ids": sorted(card_ids, key=coverage.card_sort_key),
            }
        )
    return rows


def render_count_map(counts: dict[str, int]) -> str:
    if not counts:
        return "-"
    return ", ".join(f"{key}: {value}" for key, value in counts.items())


def render_markdown(payload: dict[str, Any]) -> str:
    summary = payload["summary"]
    lines: list[str] = []
    lines.append("# 0423 高風險效果族群分析")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(f"- Migration source: `{payload['migration_source']}`")
    lines.append(f"- Official non-CHEER cards: `{summary['official_non_cheer_cards']}`")
    lines.append(f"- Core high-risk unique cards: `{summary['core_high_risk_unique_cards']}`")
    lines.append(f"- Broad watchlist unique cards: `{summary['broad_watchlist_unique_cards']}`")
    lines.append(f"- Trigger family unique cards: `{summary['trigger_family_unique_cards']}`")
    lines.append(f"- Implemented trigger smoke unique cards: `{summary['implemented_trigger_smoke_unique_cards']}`")
    lines.append(f"- Remaining trigger family cards: `{summary['trigger_family_remaining_cards']}`")
    lines.append(f"- Member multi-art cards: `{summary['member_multi_art_cards']}`")
    lines.append(f"- Member alternate art rows: `{summary['member_alternate_art_rows']}`")
    lines.append("")
    lines.append("## How To Read")
    lines.append("")
    lines.append("- `core` 代表卡片有觸發時點、zone 移動、隨機分支、附加狀態、或 alternate art 風險。")
    lines.append("- `watchlist` 代表效果文字值得追蹤，但通常先用代表性測試，不需要立即逐卡處理。")
    lines.append("- 各族群會重疊。排工作量看 unique summary，選下一個 bucket 看 group count。")
    lines.append("")
    lines.append("## Recommended Buckets")
    lines.append("")
    lines.append("| Bucket | Recommendation | Cards | Type split | Note |")
    lines.append("| --- | --- | ---: | --- | --- |")
    for row in payload["recommended_buckets"]:
        lines.append(
            f"| `{row['id']}` | {row['recommendation']} | `{row['count']}` | "
            f"{render_count_map(row['card_type_counts'])} | {row['note']} |"
        )
    lines.append("")
    lines.append("## Implemented Smoke Buckets")
    lines.append("")
    lines.append("| Bucket | Cards | Type split | Description | Examples |")
    lines.append("| --- | ---: | --- | --- | --- |")
    for row in payload["implemented_smoke_buckets"]:
        examples = "<br>".join(row["examples"]) if row["examples"] else "-"
        lines.append(
            f"| `{row['id']}` | `{row['count']}` | {render_count_map(row['card_type_counts'])} | "
            f"{row['description']} | {examples} |"
        )
    lines.append("")
    lines.append("## Core Groups")
    lines.append("")
    lines.append("| Group | Cards | Type split | Examples |")
    lines.append("| --- | ---: | --- | --- |")
    for row in payload["groups"]:
        if row["tier"] != "core":
            continue
        examples = "<br>".join(row["examples"]) if row["examples"] else "-"
        lines.append(
            f"| `{row['id']}` {row['label']} | `{row['count']}` | "
            f"{render_count_map(row['card_type_counts'])} | {examples} |"
        )
    lines.append("")
    lines.append("## Watchlist Groups")
    lines.append("")
    lines.append("| Group | Cards | Type split | Examples |")
    lines.append("| --- | ---: | --- | --- |")
    for row in payload["groups"]:
        if row["tier"] != "watchlist":
            continue
        examples = "<br>".join(row["examples"]) if row["examples"] else "-"
        lines.append(
            f"| `{row['id']}` {row['label']} | `{row['count']}` | "
            f"{render_count_map(row['card_type_counts'])} | {examples} |"
        )
    lines.append("")
    lines.append("## Suggested Workflow")
    lines.append("")
    lines.append("1. 每次官方卡匯入後重跑這份報表。")
    lines.append("2. 如果 `core_high_risk_unique_cards` 增加，先看是哪個 group 增加。")
    lines.append("3. 單一 group 新增很多卡時，優先做新的 smoke bucket。")
    lines.append("4. group 很小或 engine path 已穩定時，優先做 2 到 5 張代表性 deep tests。")
    lines.append("5. 只有 smoke 失敗、時點特殊、或曾經出 bug 的卡，才寫 single-card deep tests。")
    lines.append("")
    return "\n".join(lines)


def build_payload(migration_path: Path, include_cheer: bool) -> dict[str, Any]:
    cards, features = coverage.parse_migration(migration_path)
    official_ids = sorted(
        [
            card_id
            for card_id, meta in cards.items()
            if meta.expansion_code.startswith("H") and (include_cheer or meta.card_type != "CHEER")
        ],
        key=coverage.card_sort_key,
    )
    context: dict[str, Any] = {
        "cards": cards,
        "features": features,
        "official_ids": official_ids,
        "multi_art_cards": set(),
        "non_damage_alternate_cards": set(),
    }
    multi_art_cards, non_damage_alternate_cards, alternate_art_rows = parse_member_art_risk(
        migration_path,
        official_ids,
    )
    context["multi_art_cards"] = multi_art_cards
    context["non_damage_alternate_cards"] = non_damage_alternate_cards
    rows = group_rows(build_group_definitions(), context)
    row_by_id = {row["id"]: row for row in rows}
    trigger_family_ids = set().union(
        row_by_id["bloom-effect"]["card_ids"],
        row_by_id["collab-effect"]["card_ids"],
        row_by_id["passive-gift"]["card_ids"],
        row_by_id["down-damage-timing"]["card_ids"],
    )
    implemented_smoke_buckets = parse_implemented_trigger_smoke_buckets(
        migration_path,
        official_ids,
        trigger_family_ids,
    )
    core_unique = set().union(*(set(row["card_ids"]) for row in rows if row["tier"] == "core"))
    broad_unique = set().union(*(set(row["card_ids"]) for row in rows))
    implemented_trigger_smoke_unique = set().union(*implemented_smoke_buckets.values())
    trigger_family_remaining = trigger_family_ids - implemented_trigger_smoke_unique

    summary = {
        "official_non_cheer_cards": len(official_ids),
        "core_high_risk_unique_cards": len(core_unique),
        "core_high_risk_card_type_counts": type_counts(core_unique, cards),
        "broad_watchlist_unique_cards": len(broad_unique),
        "broad_watchlist_card_type_counts": type_counts(broad_unique, cards),
        "trigger_family_unique_cards": len(trigger_family_ids),
        "implemented_trigger_smoke_unique_cards": len(implemented_trigger_smoke_unique),
        "trigger_family_remaining_cards": len(trigger_family_remaining),
        "trigger_family_remaining_card_type_counts": type_counts(trigger_family_remaining, cards),
        "member_multi_art_cards": len(multi_art_cards),
        "member_non_damage_alternate_art_cards": len(non_damage_alternate_cards),
        "member_alternate_art_rows": alternate_art_rows,
    }
    return {
        "migration_source": str(migration_path),
        "summary": summary,
        "recommended_buckets": build_recommended_bucket_rows(rows, cards),
        "implemented_smoke_buckets": build_implemented_smoke_rows(implemented_smoke_buckets, cards),
        "groups": rows,
    }


def resolve_display_path(path: Path) -> str:
    try:
        return str(path.relative_to(coverage.REPO_ROOT))
    except ValueError:
        return str(path)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Analyze high-risk official card effect groups for smoke bucket and deep-test planning."
    )
    parser.add_argument(
        "--migration-file",
        default="src/main/resources/db/migration/V40__add_multi_effects_array_from_official_all.sql",
        help="Migration file used as the official card/effect source.",
    )
    parser.add_argument(
        "--output-md",
        default="doc/0423-高風險效果族群分析.md",
        help="Markdown report output path.",
    )
    parser.add_argument(
        "--output-json",
        default="",
        help="Optional JSON output path for machine-readable post-processing.",
    )
    parser.add_argument(
        "--include-cheer",
        action="store_true",
        help="Include CHEER cards in the official total.",
    )
    args = parser.parse_args()

    migration_path = coverage.resolve_repo_path(args.migration_file)
    output_md_path = coverage.resolve_repo_path(args.output_md)

    payload = build_payload(migration_path, args.include_cheer)
    output_md_path.parent.mkdir(parents=True, exist_ok=True)
    output_md_path.write_text(render_markdown(payload), encoding="utf-8")

    print(f"Wrote markdown report to: {resolve_display_path(output_md_path)}")
    if args.output_json:
        output_json_path = coverage.resolve_repo_path(args.output_json)
        output_json_path.parent.mkdir(parents=True, exist_ok=True)
        output_json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Wrote json report to: {resolve_display_path(output_json_path)}")


if __name__ == "__main__":
    main()
