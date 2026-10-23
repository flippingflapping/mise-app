"""Pydantic mirror of schema/recipe.schema.json (schema_version 1).

The JSON Schema is the source of truth; tests check that the fixtures validate against both
the schema and these models, and that a model round-trip doesn't change the JSON.
"""

from __future__ import annotations

from enum import Enum
from typing import Any, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, model_serializer


class Unit(str, Enum):
    g = "g"
    kg = "kg"
    ml = "ml"
    l = "l"  # noqa: E741
    tsp = "tsp"
    tbsp = "tbsp"
    cup = "cup"
    fl_oz = "fl_oz"
    oz = "oz"
    lb = "lb"
    pinch = "pinch"


class Heat(str, Enum):
    low = "low"
    medium_low = "medium_low"
    medium = "medium"
    medium_high = "medium_high"
    high = "high"


class Status(str, Enum):
    needs_review = "needs_review"
    ready = "ready"


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", use_enum_values=True)


class Yield(_Strict):
    base_servings: float = Field(gt=0)
    label: Optional[str] = None


class Times(_Strict):
    prep_minutes: Optional[int] = Field(default=None, ge=0)
    cook_minutes: Optional[int] = Field(default=None, ge=0)
    total_minutes: Optional[int] = Field(default=None, ge=0)


class Ingredient(_Strict):
    id: str = Field(pattern=r"^[0-9]{4}$")
    group: Optional[str] = None
    name: str = Field(min_length=1)
    short_name: str = Field(min_length=1)
    prep: Optional[str] = None
    amount: Optional[float] = Field(gt=0)
    amount_max: Optional[float] = Field(default=None, gt=0)
    unit: Optional[Unit]
    optional: Optional[bool] = None
    substitutes: Optional[str] = None
    scales: Optional[bool] = None
    aisle: Optional[str] = None

    @model_serializer(mode="wrap")
    def _keep_required_nulls(self, handler: Any) -> dict[str, Any]:
        data = handler(self)
        # amount and unit are required-but-nullable: never drop them, even with exclude_none.
        data.setdefault("amount", self.amount)
        data.setdefault("unit", self.unit.value if isinstance(self.unit, Unit) else self.unit)
        return data


class Step(_Strict):
    id: str = Field(min_length=1)
    title: str = Field(min_length=1)
    content: str = Field(min_length=1)
    timer_seconds: Optional[int] = Field(default=None, gt=0)
    timer_max_seconds: Optional[int] = Field(default=None, gt=0)
    heat: Optional[Heat] = None


class Source(_Strict):
    type: Literal["book", "web", "family", "other"]
    title: Optional[str] = None
    author: Optional[str] = None
    page: Optional[str] = None
    url: Optional[str] = None
    original_filename: Optional[str] = None


class ReviewFlag(_Strict):
    path: str
    message: str = Field(min_length=1)


class YieldEdit(_Strict):
    base_servings: float = Field(gt=0)
    label: Optional[str] = None


class IngredientEdit(_Strict):
    """The complete editable state of one ingredient (absent = none)."""

    name: str = Field(min_length=1)
    #: How it reads in the steps; None = unchanged (or follows the name, if it did).
    short_name: Optional[str] = Field(default=None, min_length=1)
    prep: Optional[str] = None
    amount: Optional[float] = Field(default=None, gt=0)
    amount_max: Optional[float] = Field(default=None, gt=0)
    unit: Optional[Unit] = None
    optional: Optional[bool] = None


class StepEdit(_Strict):
    title: str = Field(min_length=1)
    timer_seconds: Optional[int] = Field(default=None, gt=0)
    timer_max_seconds: Optional[int] = Field(default=None, gt=0)
    #: New wording with {0001} placeholders; None = as converted.
    content: Optional[str] = Field(default=None, min_length=1)


class Edits(_Strict):
    """Corrections made in the app, applied on top of the converted recipe (see mise_shared.edits)."""

    title: Optional[str] = Field(default=None, min_length=1)
    yield_: Optional[YieldEdit] = Field(default=None, alias="yield")
    ingredients: dict[str, IngredientEdit] = Field(default_factory=dict)
    steps: dict[str, StepEdit] = Field(default_factory=dict)
    checked_flags: list[str] = Field(default_factory=list)
    added_ingredients: list[Ingredient] = Field(default_factory=list)
    removed_ingredients: list[str] = Field(default_factory=list)

    model_config = ConfigDict(extra="forbid", use_enum_values=True, populate_by_name=True)

    @property
    def is_empty(self) -> bool:
        return not (
            self.title or self.yield_ or self.ingredients or self.steps or self.checked_flags
            or self.added_ingredients or self.removed_ingredients
        )


class UserData(_Strict):
    """Annotations merged into a served recipe (from <id>.user.json)."""

    favorite: Optional[bool] = None
    reviewed: Optional[bool] = None
    last_cooked: Optional[str] = None
    cook_log: Optional[list[str]] = None
    edits: Optional[Edits] = None
    updated_at: Optional[str] = None


class Recipe(_Strict):
    schema_version: Literal[1] = 1
    id: str = Field(pattern=r"^[a-z0-9]+(-[a-z0-9]+)*$")
    title: str = Field(min_length=1)
    description: Optional[str] = None
    yield_: Yield = Field(alias="yield")
    times: Optional[Times] = None
    advance_prep: list[str] = Field(default_factory=list)
    tags: list[str] = Field(default_factory=list)
    ingredients: list[Ingredient] = Field(default_factory=list)
    steps: list[Step] = Field(min_length=1)
    notes: Optional[str] = None
    cook_notes: list[str] = Field(default_factory=list)
    related_recipes: list[str] = Field(default_factory=list)
    source: Optional[Source] = None
    source_text_sha256: Optional[str] = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    review_flags: list[ReviewFlag] = Field(default_factory=list)
    status: Status
    image: Optional[str] = None
    user: Optional[UserData] = None
    created_at: Optional[str] = None
    updated_at: Optional[str] = None

    model_config = ConfigDict(extra="forbid", use_enum_values=True, populate_by_name=True)

    def to_json_dict(self) -> dict[str, Any]:
        """Canonical JSON dict: `yield` alias, no None-valued optionals."""
        return self.model_dump(mode="json", by_alias=True, exclude_none=True)


class Annotations(_Strict):
    """Contents of the <id>.user.json sidecar, and the body of PUT /recipes/{id}/annotations.

    Every field is optional; a PUT replaces only the fields it sends.
    """

    favorite: Optional[bool] = None
    reviewed: Optional[bool] = None
    cook_notes: Optional[list[str]] = None
    last_cooked: Optional[str] = None
    cook_log: Optional[list[str]] = None
    #: Replaces all earlier edits when sent; {} clears them.
    edits: Optional[Edits] = None
    updated_at: Optional[str] = None
