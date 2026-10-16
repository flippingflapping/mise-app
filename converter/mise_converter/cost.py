"""Rough cost estimate shown before anything is sent."""

from __future__ import annotations

from dataclasses import dataclass

from mise_shared.config import Pricing

from .inputs import SourceDocument

CHARS_PER_TOKEN = 3.5  # rough for English recipe text and JSON
SCHEMA_OVERHEAD_TOKENS = 1200  # structured-output schema + per-request framing


@dataclass
class Estimate:
    recipes: int
    input_tokens: int
    cached_tokens: int
    output_tokens: int
    usd: float
    batch: bool

    def describe(self) -> str:
        mode = "Message Batches API (50% off)" if self.batch else "synchronous requests"
        return (
            f"{self.recipes} recipe(s) via {mode}: ~{self.input_tokens:,} input tokens "
            f"(+ ~{self.cached_tokens:,} cached system-prompt tokens per request), "
            f"~{self.output_tokens:,} output tokens incl. thinking → roughly ${self.usd:.2f}"
        )


def estimate(docs: list[SourceDocument], system_prompt_chars: int, pricing: Pricing, *, batch: bool) -> Estimate:
    n = len(docs)
    sys_tokens = int(system_prompt_chars / CHARS_PER_TOKEN) + SCHEMA_OVERHEAD_TOKENS
    per_input = [int(len(d.text) / CHARS_PER_TOKEN) + 60 for d in docs]
    in_price = pricing.input_per_mtok / 1e6
    out_price = pricing.output_per_mtok / 1e6
    # First request writes the cache; the rest read it (best effort, especially in batches).
    sys_cost = 0.0
    if n:
        sys_cost = sys_tokens * in_price * (pricing.cache_write_multiplier + (n - 1) * pricing.cache_read_multiplier)
    out_tokens = n * pricing.est_output_tokens_per_recipe
    usd = sys_cost + sum(per_input) * in_price + out_tokens * out_price
    if batch:
        usd *= pricing.batch_discount
    return Estimate(n, sum(per_input), sys_tokens, out_tokens, usd, batch)
