from __future__ import annotations

import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from database import get_db
from models import Question, AnswerBox
from schemas import (
    QuestionCreate,
    QuestionContentUpdate,
    QuestionOut,
    AnswerBoxOut,
)

router = APIRouter(prefix="/api/questions", tags=["questions"])


def _box_to_out(b: AnswerBox) -> AnswerBoxOut:
    bbox = None
    if b.bbox_x is not None:
        bbox = [b.bbox_x, b.bbox_y, b.bbox_w, b.bbox_h]
    return AnswerBoxOut(id=b.id, label=b.label or "", points=b.points, bbox=bbox, page_index=b.page_index)


def _question_to_out(q: Question) -> QuestionOut:
    return QuestionOut(
        question_id=q.id,
        state=q.state,
        physical_page=q.physical_page,
        dpi=q.dpi,
        content=q.content,
        answer_boxes=[_box_to_out(b) for b in q.answer_boxes],
        page_w_px=q.page_w_px,
        page_h_px=q.page_h_px,
        derived_from=q.derived_from,
        created_at=q.created_at.isoformat() if q.created_at else "",
        finalized_at=q.finalized_at.isoformat() if q.finalized_at else None,
    )


def _question_to_dict(q: Question) -> dict:
    return _question_to_out(q).model_dump()


def _get_question_or_404(question_id: str, db: Session) -> Question:
    q = db.query(Question).filter(Question.id == question_id).first()
    if not q:
        raise HTTPException(status_code=404, detail=f"Question {question_id} not found")
    return q


def _assert_draft(q: Question):
    if q.state == "finalized":
        raise HTTPException(
            status_code=409,
            detail=f"Question {q.id} is finalized — cannot modify. Clone it to create a new draft.",
        )


def _upsert_answer_boxes(q: Question, boxes_in: list, db: Session):
    incoming_ids = {b.id for b in boxes_in}
    existing = {b.id: b for b in q.answer_boxes}

    for old_id, old_box in existing.items():
        if old_id not in incoming_ids:
            db.delete(old_box)

    for i, b in enumerate(boxes_in):
        if b.id in existing:
            box = existing[b.id]
            box.label = b.label
            box.points = b.points
            box.order_index = i
        else:
            db.add(AnswerBox(
                id=b.id,
                question_id=q.id,
                label=b.label,
                points=b.points,
                order_index=i,
            ))


@router.post("", response_model=QuestionOut, status_code=201)
def create_question(body: QuestionCreate, db: Session = Depends(get_db)):
    q = Question(
        id=str(uuid.uuid4()),
        state="draft",
        physical_page=body.physical_page,
        content=body.content,
    )
    db.add(q)
    db.flush()
    _upsert_answer_boxes(q, body.answer_boxes, db)

    db.commit()
    db.refresh(q)
    return _question_to_out(q)


@router.get("", response_model=list[QuestionOut])
def list_questions(db: Session = Depends(get_db)):
    questions = db.query(Question).order_by(Question.created_at.desc()).all()
    return [_question_to_out(q) for q in questions]


@router.get("/{question_id}", response_model=QuestionOut)
def get_question(question_id: str, db: Session = Depends(get_db)):
    q = _get_question_or_404(question_id, db)
    return _question_to_out(q)


@router.put("/{question_id}/blocks", response_model=QuestionOut)
def save_content(question_id: str, body: QuestionContentUpdate, db: Session = Depends(get_db)):
    q = _get_question_or_404(question_id, db)
    _assert_draft(q)

    q.content = body.content
    _upsert_answer_boxes(q, body.answer_boxes, db)

    db.commit()
    db.refresh(q)
    return _question_to_out(q)


@router.post("/{question_id}/finalize", response_model=QuestionOut)
def finalize_question(question_id: str, db: Session = Depends(get_db)):
    q = _get_question_or_404(question_id, db)
    _assert_draft(q)

    if not q.content:
        raise HTTPException(status_code=400, detail="Cannot finalize an empty question — add some content first.")

    from services.doc_renderer import render_finalized_question

    question_dict = _question_to_dict(q)
    layout = render_finalized_question(question_dict)

    q.page_w_px = layout["page_w_px"]
    q.page_h_px = layout["page_h_px"]
    q.page_count = layout["page_count"]
    for box in q.answer_boxes:
        if box.id in layout["boxes"]:
            page_index, x, y, w, h = layout["boxes"][box.id]
            box.page_index = page_index
            box.bbox_x, box.bbox_y, box.bbox_w, box.bbox_h = x, y, w, h
        else:
            raise HTTPException(
                status_code=500,
                detail=f"Answer box {box.id} exists in DB but was not found in rendered layout — "
                f"doc content and answer_boxes table are out of sync.",
            )

    q.state = "finalized"
    q.finalized_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(q)
    return _question_to_out(q)


@router.get("/{question_id}/pdf")
def export_pdf(question_id: str, db: Session = Depends(get_db)):
    q = _get_question_or_404(question_id, db)
    if q.state != "finalized":
        raise HTTPException(status_code=400, detail="Cannot export PDF — question is still a draft. Finalize it first.")

    from pathlib import Path
    from config import settings
    pdf_path = Path(settings.PDF_DIR) / f"{question_id}.pdf"
    if not pdf_path.exists():
        raise HTTPException(status_code=404, detail="PDF not found on disk — try re-finalizing (clone + finalize) to regenerate it.")

    return FileResponse(str(pdf_path), media_type="application/pdf", filename=f"question_{question_id}.pdf")


@router.post("/{question_id}/clone", response_model=QuestionOut, status_code=201)
def clone_question(question_id: str, db: Session = Depends(get_db)):
    original = _get_question_or_404(question_id, db)

    new_q = Question(
        id=str(uuid.uuid4()),
        state="draft",
        physical_page=original.physical_page,
        dpi=original.dpi,
        content=original.content,
        derived_from=original.id,
    )
    db.add(new_q)
    db.flush()

    for i, b in enumerate(original.answer_boxes):
        db.add(AnswerBox(
            id=b.id,
            question_id=new_q.id,
            label=b.label,
            points=b.points,
            order_index=i,
        ))

    db.commit()
    db.refresh(new_q)
    return _question_to_out(new_q)
