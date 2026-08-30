import uuid
from datetime import datetime, timezone

from sqlalchemy import (
    Column,
    String,
    Integer,
    DateTime,
    ForeignKey,
    Enum as SAEnum,
    JSON,
)
from sqlalchemy.orm import relationship

from database import Base


def _uuid() -> str:
    return str(uuid.uuid4())


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Question(Base):
    __tablename__ = "questions"

    id = Column(String, primary_key=True, default=_uuid)
    state = Column(SAEnum("draft", "finalized", name="question_state"), default="draft", nullable=False)
    physical_page = Column(String, nullable=False, default="A4")
    dpi = Column(Integer, nullable=False, default=150)

    content = Column(JSON, nullable=True)

    page_w_px = Column(Integer, nullable=True)
    page_h_px = Column(Integer, nullable=True)
    page_count = Column(Integer, nullable=True)

    derived_from = Column(String, ForeignKey("questions.id"), nullable=True)
    created_at = Column(DateTime, default=_utcnow, nullable=False)
    finalized_at = Column(DateTime, nullable=True)

    answer_boxes = relationship(
        "AnswerBox",
        back_populates="question",
        cascade="all, delete-orphan",
        order_by="AnswerBox.order_index",
    )


class AnswerBox(Base):
    __tablename__ = "answer_boxes"

    id = Column(String, primary_key=True)
    question_id = Column(String, ForeignKey("questions.id", ondelete="CASCADE"), nullable=False)
    label = Column(String, nullable=True)
    points = Column(Integer, nullable=False, default=1)
    order_index = Column(Integer, nullable=False, default=0)

    page_index = Column(Integer, nullable=True)
    bbox_x = Column(Integer, nullable=True)
    bbox_y = Column(Integer, nullable=True)
    bbox_w = Column(Integer, nullable=True)
    bbox_h = Column(Integer, nullable=True)

    question = relationship("Question", back_populates="answer_boxes")


class Submission(Base):
    __tablename__ = "submissions"

    id = Column(String, primary_key=True, default=_uuid)
    question_id = Column(String, ForeignKey("questions.id"), nullable=False)
    modality = Column(SAEnum("tablet", "photo", "scanner", name="submission_modality"), nullable=False)
    original_image_path = Column(String, nullable=True)
    image_width = Column(Integer, nullable=True)
    image_height = Column(Integer, nullable=True)
    image_dpi = Column(Integer, nullable=True)
    manifest = Column(JSON, nullable=True)
    created_at = Column(DateTime, default=_utcnow, nullable=False)

    question = relationship("Question")
