from __future__ import annotations

from typing import Literal, Any
from pydantic import BaseModel


class AnswerBoxIn(BaseModel):
    id: str
    label: str = ""
    points: int = 1


class AnswerBoxOut(AnswerBoxIn):
    bbox: list[int] | None = None
    page_index: int | None = None


class QuestionCreate(BaseModel):
    physical_page: str = "A4"
    content: dict[str, Any] | None = None
    answer_boxes: list[AnswerBoxIn] = []


class QuestionContentUpdate(BaseModel):
    content: dict[str, Any]
    answer_boxes: list[AnswerBoxIn] = []


class QuestionOut(BaseModel):
    question_id: str
    state: str
    physical_page: str
    dpi: int
    content: dict[str, Any] | None
    answer_boxes: list[AnswerBoxOut]
    page_w_px: int | None = None
    page_h_px: int | None = None
    page_count: int | None = None
    derived_from: str | None = None
    created_at: str
    finalized_at: str | None = None

    model_config = {"from_attributes": True}


class CropInfo(BaseModel):
    answer_box_id: str
    crop_path: str
    qr_check: Literal["pass", "fail", "absent"]
    warped_bbox: list[int]


class PageExtractionResult(BaseModel):
    page_index: int
    markers_detected: str
    transform_type: str
    crops: list[CropInfo]
    image_resolution: str | None = None
    image_dpi: int | None = None
    error: str | None = None


class ExtractionResult(BaseModel):
    submission_id: str
    question_id: str
    modality: str
    pages: list[PageExtractionResult]


class TabletStroke(BaseModel):
    points: list[list[float]]


class TabletSubmission(BaseModel):
    question_id: str
    ink_strokes: list[TabletStroke]
