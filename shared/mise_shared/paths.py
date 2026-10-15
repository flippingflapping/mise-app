"""Locations of repo-level files (schema, fixtures, prompts)."""

from __future__ import annotations

from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = REPO_ROOT / "schema" / "recipe.schema.json"
FIXTURES_DIR = REPO_ROOT / "fixtures"
PROMPTS_DIR = REPO_ROOT / "converter" / "prompts"
