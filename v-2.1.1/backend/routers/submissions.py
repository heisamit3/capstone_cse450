from __future__ import annotations

import io
import uuid
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, UploadFile, File, Form
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from database import get_db
from models import Submission
from schemas import ExtractionResult, TabletSubmission
from services.extractor import extract_page
from routers.questions import _question_to_dict, _get_question_or_404

router = APIRouter(prefix="/api/submissions", tags=["submissions"])


def _pdf_to_images(pdf_bytes: bytes) -> list[bytes]:
    try:
        from pdf2image import convert_from_bytes
        images = convert_from_bytes(pdf_bytes, dpi=150, fmt="png")
        result = []
        for img in images:
            buf = io.BytesIO()
            img.save(buf, format="PNG")
            result.append(buf.getvalue())
        return result
    except ImportError:
        raise HTTPException(status_code=500, detail="pdf2image not installed — cannot process PDF uploads")


def _tiff_to_images(tiff_bytes: bytes) -> list[bytes]:
    from PIL import Image as PILImage
    img = PILImage.open(io.BytesIO(tiff_bytes))
    result = []
    try:
        for i in range(1000):
            img.seek(i)
            buf = io.BytesIO()
            img.save(buf, format="PNG")
            result.append(buf.getvalue())
    except EOFError:
        pass
    return result


@router.post("", response_model=ExtractionResult)
async def create_submission(
    question_id: str = Form(...),
    modality: str = Form(...),
    page_index: int = Form(0),
    image: UploadFile = File(...),
    db: Session = Depends(get_db),
):
    if modality not in ("photo", "scanner"):
        raise HTTPException(
            status_code=400,
            detail=f"Invalid modality '{modality}'. Use 'photo' or 'scanner'. "
            f"For tablet submissions, use POST /api/submissions/tablet.",
        )

    q = _get_question_or_404(question_id, db)
    if q.state != "finalized":
        raise HTTPException(status_code=400, detail="Question must be finalized before submissions can be processed.")

    question_dict = _question_to_dict(q)

    raw_bytes = await image.read()
    filename = image.filename or ""
    content_type = image.content_type or ""
    is_pdf = filename.lower().endswith(".pdf") or "pdf" in content_type
    is_tiff = filename.lower().endswith((".tif", ".tiff")) or "tiff" in content_type

    if is_pdf:
        page_images = _pdf_to_images(raw_bytes)
        if not page_images:
            raise HTTPException(status_code=400, detail="PDF contains no pages")
    elif is_tiff:
        page_images = _tiff_to_images(raw_bytes)
        if not page_images:
            raise HTTPException(status_code=400, detail="TIFF contains no pages")
    else:
        page_images = None

    sub_id = str(uuid.uuid4())
    page_results = []

    if page_images is not None:
        n_pages = len(page_images)
        if q.page_count:
            n_pages = min(n_pages, q.page_count)
        for i in range(n_pages):
            try:
                page_results.append(extract_page(
                    question=question_dict, image_bytes=page_images[i],
                    modality=modality, page_index=i, submission_id=sub_id,
                ))
            except ValueError as e:
                page_results.append({
                    "page_index": i, "markers_detected": "N/A", "transform_type": "none",
                    "crops": [], "image_resolution": None, "image_dpi": None, "error": str(e),
                })
    else:
        try:
            page_results.append(extract_page(
                question=question_dict, image_bytes=raw_bytes,
                modality=modality, page_index=page_index, submission_id=sub_id,
            ))
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))

    manifest = {
        "submission_id": sub_id,
        "question_id": question_id,
        "modality": modality,
        "pages": page_results,
    }

    submission = Submission(
        id=sub_id,
        question_id=question_id,
        modality=modality,
        image_dpi=page_results[0].get("image_dpi") if page_results else None,
        manifest=manifest,
    )
    db.add(submission)
    db.commit()

    return ExtractionResult(**manifest)


@router.post("/tablet", response_model=ExtractionResult)
def create_tablet_submission(body: TabletSubmission, page_index: int = 0, db: Session = Depends(get_db)):
    q = _get_question_or_404(body.question_id, db)
    if q.state != "finalized":
        raise HTTPException(status_code=400, detail="Question must be finalized before submissions can be processed.")

    question_dict = _question_to_dict(q)
    sub_id = str(uuid.uuid4())
    ink_data = [{"points": s.points} for s in body.ink_strokes]

    try:
        page_result = extract_page(
            question=question_dict, image_bytes=None, modality="tablet",
            page_index=page_index, ink_strokes=ink_data, submission_id=sub_id,
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    manifest = {"submission_id": sub_id, "question_id": body.question_id, "modality": "tablet", "pages": [page_result]}
    submission = Submission(id=sub_id, question_id=body.question_id, modality="tablet", manifest=manifest)
    db.add(submission)
    db.commit()
    return ExtractionResult(**manifest)


@router.get("/{submission_id}")
def get_submission(submission_id: str, db: Session = Depends(get_db)):
    sub = db.query(Submission).filter(Submission.id == submission_id).first()
    if not sub:
        raise HTTPException(status_code=404, detail=f"Submission {submission_id} not found")
    return sub.manifest


@router.get("/{submission_id}/crops/{answer_box_id}")
def get_crop_image(submission_id: str, answer_box_id: str, db: Session = Depends(get_db)):
    sub = db.query(Submission).filter(Submission.id == submission_id).first()
    if not sub:
        raise HTTPException(status_code=404, detail=f"Submission {submission_id} not found")

    manifest = sub.manifest or {}
    crop = None
    for page in manifest.get("pages", []):
        crop = next((c for c in page.get("crops", []) if c["answer_box_id"] == answer_box_id), None)
        if crop:
            break
    if not crop:
        raise HTTPException(status_code=404, detail=f"Crop for answer box {answer_box_id} not found")

    crop_path = Path(crop["crop_path"])
    if not crop_path.exists():
        raise HTTPException(status_code=404, detail=f"Crop file not found on disk: {crop_path}")
    return FileResponse(str(crop_path), media_type="image/png")
