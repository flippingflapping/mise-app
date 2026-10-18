"""HTTP API for the phone. Everything under /api/v1 except /health needs the bearer token."""

from __future__ import annotations

import secrets
from datetime import datetime, timezone
from typing import Annotated, Any

from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from pydantic import BaseModel, ConfigDict, Field
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, PlainTextResponse

from mise_shared import SCHEMA_VERSION, __version__
from mise_shared.models import Annotations
from mise_shared.util import now_iso

from .imports import MAX_TEXT, ImportQueue
from .library import SLUG, RecipeLibrary
from .pairing import pairing_page

LOCAL_HOSTS = {"127.0.0.1", "::1", "localhost"}


def _etag_matches(request: Request, etag: str) -> bool:
    inm = request.headers.get("if-none-match")
    if not inm:
        return False
    tags = {t.strip().removeprefix("W/") for t in inm.split(",")}
    return etag in tags or "*" in tags


class ImportRequest(BaseModel):
    """Body of POST /recipes/import."""

    model_config = ConfigDict(extra="forbid")
    text: str = Field(min_length=1, max_length=MAX_TEXT)
    title: str | None = Field(default=None, max_length=300)
    source: str | None = Field(default=None, max_length=1000)


def create_app(
    library: RecipeLibrary,
    token: str,
    *,
    pairing: list[tuple[str, str]] | None = None,
    local_hosts: set[str] = LOCAL_HOSTS,
    imports: ImportQueue | None = None,
) -> FastAPI:
    app = FastAPI(title="Mise companion server", version=__version__, docs_url=None, redoc_url=None)

    def require_token(request: Request) -> None:
        auth = request.headers.get("authorization", "")
        scheme, _, given = auth.partition(" ")
        if scheme.lower() != "bearer" or not secrets.compare_digest(given.strip().encode(), token.encode()):
            raise HTTPException(401, "missing or wrong access token", headers={"WWW-Authenticate": "Bearer"})

    auth = [Depends(require_token)]

    def entry_or_404(rid: str):
        entry = library.get(rid) if SLUG.match(rid) else None
        if entry is None:
            raise HTTPException(404, f"no recipe {rid!r}")
        return entry

    @app.get("/", include_in_schema=False)
    def home(request: Request) -> Response:
        host = request.client.host if request.client else ""
        if host in local_hosts and pairing:
            return HTMLResponse(pairing_page(pairing))
        return PlainTextResponse("Mise companion server. Pair from the app; open this page on the PC itself to see the QR code.")

    @app.get("/api/v1/health")
    def health() -> dict[str, Any]:
        return {
            "status": "ok",
            "name": "mise-server",
            "version": __version__,
            "schema_version": SCHEMA_VERSION,
            "can_import": imports is not None,
            "can_convert": bool(imports and imports.can_convert),
            "time": now_iso(),
        }

    @app.get("/api/v1/recipes", dependencies=auth)
    def list_recipes(
        request: Request,
        since: Annotated[str | None, Query(description="ISO 8601; only recipes updated after this")] = None,
    ) -> Response:
        since_dt = None
        if since:
            try:
                since_dt = datetime.fromisoformat(since.replace("Z", "+00:00"))
            except ValueError:
                raise HTTPException(400, "since must be an ISO 8601 date-time")
            if since_dt.tzinfo is None:
                since_dt = since_dt.replace(tzinfo=timezone.utc)
        etag = library.list_etag()
        if since_dt is None and _etag_matches(request, etag):
            return Response(status_code=304, headers={"ETag": etag})
        body = {
            "recipes": library.summaries(since_dt),
            "ids": library.ids(),
            "server_time": now_iso(),
        }
        return JSONResponse(body, headers={"ETag": etag, "Cache-Control": "no-cache"})

    @app.get("/api/v1/recipes/{rid}", dependencies=auth)
    def get_recipe(rid: str, request: Request) -> Response:
        entry = entry_or_404(rid)
        if _etag_matches(request, entry.etag):
            return Response(status_code=304, headers={"ETag": entry.etag})
        return JSONResponse(entry.recipe, headers={"ETag": entry.etag, "Cache-Control": "no-cache"})

    @app.get("/api/v1/recipes/{rid}/source", dependencies=auth)
    def get_source(rid: str) -> Response:
        entry_or_404(rid)
        text = library.source_text(rid)
        if text is None:
            raise HTTPException(404, "no original text stored for this recipe")
        return PlainTextResponse(text, media_type="text/plain; charset=utf-8")

    @app.get("/api/v1/recipes/{rid}/image", dependencies=auth)
    def get_image(rid: str) -> Response:
        entry = entry_or_404(rid)
        path = library.image_path(rid, entry.recipe)
        if path is None:
            raise HTTPException(404, "no image for this recipe")
        return FileResponse(path, headers={"Cache-Control": "max-age=3600"})

    @app.put("/api/v1/recipes/{rid}/annotations", dependencies=auth)
    def put_annotations(rid: str, body: Annotations) -> Response:
        entry_or_404(rid)
        try:
            entry = library.save_annotations(rid, body)
        except KeyError:
            raise HTTPException(404, f"no recipe {rid!r}")
        return JSONResponse(entry.recipe, headers={"ETag": entry.etag})

    @app.post("/api/v1/recipes/import", dependencies=auth, status_code=202)
    def import_recipe(body: ImportRequest) -> dict[str, Any]:
        """Save pasted recipe text to the inbox (and convert it, if this server has an API key)."""
        if imports is None:
            raise HTTPException(501, "this server doesn't accept recipes from the app")
        if not body.text.strip():
            raise HTTPException(422, "the recipe text is empty")
        return imports.submit(body.text, body.title, body.source).as_dict()

    @app.get("/api/v1/imports/{job_id}", dependencies=auth)
    def import_status(job_id: str) -> dict[str, Any]:
        job = imports.get(job_id) if imports else None
        if job is None:
            raise HTTPException(404, "unknown import (the server may have restarted; the text is still in the inbox)")
        return job.as_dict()

    return app
