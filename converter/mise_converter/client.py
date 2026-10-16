"""Thin wrapper around the Anthropic SDK so the pipeline can be tested with a fake.

The API key comes from the environment (ANTHROPIC_API_KEY) via the SDK; it is never read, logged
or written by this code.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Iterator, Protocol


@dataclass
class LLMResponse:
    text: str | None
    stop_reason: str | None
    #: Full content blocks (thinking included), to send back unchanged on a retry turn.
    content: list[dict[str, Any]] = field(default_factory=list)
    usage: dict[str, int] = field(default_factory=dict)
    error: str | None = None


@dataclass
class BatchState:
    id: str
    processing_status: str  # in_progress | canceling | ended
    counts: dict[str, int] = field(default_factory=dict)


class LLMClient(Protocol):
    def create(self, params: dict[str, Any]) -> LLMResponse: ...
    def batch_create(self, requests: list[dict[str, Any]]) -> str: ...
    def batch_state(self, batch_id: str) -> BatchState: ...
    def batch_results(self, batch_id: str) -> Iterator[tuple[str, LLMResponse]]: ...


FALLBACK_BETA = "server-side-fallback-2026-07-01"


def _from_message(msg: Any) -> LLMResponse:
    content = [b.model_dump(mode="json", exclude_none=True) for b in msg.content]
    text = next((b.text for b in msg.content if getattr(b, "type", None) == "text"), None)
    usage = {}
    if getattr(msg, "usage", None) is not None:
        u = msg.usage
        for k in ("input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens"):
            v = getattr(u, k, None)
            if isinstance(v, int):
                usage[k] = v
    return LLMResponse(text=text, stop_reason=msg.stop_reason, content=content, usage=usage)


class AnthropicClient:
    def __init__(self, *, refusal_fallback: bool = True, sdk_client: Any = None):
        import anthropic  # imported lazily so status/dry-run work without network or key

        self._anthropic = anthropic
        self._client = sdk_client or anthropic.Anthropic(max_retries=4)
        self.refusal_fallback = refusal_fallback

    def create(self, params: dict[str, Any]) -> LLMResponse:
        """One synchronous conversion. Streams (large max_tokens) and returns the final message."""
        a = self._anthropic
        try:
            if self.refusal_fallback:
                with self._client.beta.messages.stream(
                    **params, fallbacks="default", betas=[FALLBACK_BETA]
                ) as stream:
                    msg = stream.get_final_message()
            else:
                with self._client.messages.stream(**params) as stream:
                    msg = stream.get_final_message()
        except a.BadRequestError as e:
            return LLMResponse(None, None, error=f"bad request: {e.message}")
        except a.AuthenticationError:
            raise SystemExit("ANTHROPIC_API_KEY is missing or invalid.")
        except a.APIStatusError as e:
            return LLMResponse(None, None, error=f"API error {e.status_code}: {e.message}")
        except a.APIConnectionError as e:
            return LLMResponse(None, None, error=f"connection error: {e}")
        return _from_message(msg)

    def batch_create(self, requests: list[dict[str, Any]]) -> str:
        batch = self._client.messages.batches.create(requests=requests)
        return batch.id

    def batch_state(self, batch_id: str) -> BatchState:
        b = self._client.messages.batches.retrieve(batch_id)
        c = b.request_counts
        counts = {k: getattr(c, k) for k in ("processing", "succeeded", "errored", "canceled", "expired")}
        return BatchState(b.id, b.processing_status, counts)

    def batch_results(self, batch_id: str) -> Iterator[tuple[str, LLMResponse]]:
        for r in self._client.messages.batches.results(batch_id):
            kind = r.result.type
            if kind == "succeeded":
                yield r.custom_id, _from_message(r.result.message)
            elif kind == "errored":
                err = getattr(r.result.error, "error", r.result.error)
                detail = f"{getattr(err, 'type', 'error')}: {getattr(err, 'message', '')}".strip(": ")
                yield r.custom_id, LLMResponse(None, None, error=f"batch request errored ({detail})")
            else:
                yield r.custom_id, LLMResponse(None, None, error=f"batch request {kind}")
