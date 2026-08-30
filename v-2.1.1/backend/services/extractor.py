from __future__ import annotations

import io
import json
import logging
import uuid
from pathlib import Path

import cv2
import numpy as np
from PIL import Image as PILImage

from config import settings
from services.doc_renderer import get_marker_positions, ARUCO_DICT

logger = logging.getLogger(__name__)

ARUCO_PARAMS = cv2.aruco.DetectorParameters()
ARUCO_DETECTOR = cv2.aruco.ArucoDetector(ARUCO_DICT, ARUCO_PARAMS)


def _decode_image(image_bytes: bytes) -> tuple[np.ndarray, int | None]:
    dpi = None
    try:
        pil_img = PILImage.open(io.BytesIO(image_bytes))
        if "dpi" in pil_img.info:
            dpi = int(pil_img.info["dpi"][0])
    except Exception:
        pass

    arr = np.frombuffer(image_bytes, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("Failed to decode image — unsupported format or corrupt file")
    return img, dpi


def _detect_aruco_markers(img: np.ndarray) -> dict[int, np.ndarray]:
    corners, ids, _ = ARUCO_DETECTOR.detectMarkers(img)
    if ids is None:
        return {}
    return {int(mid): corners[i][0].mean(axis=0) for i, mid in enumerate(ids.flatten())}


def _compute_transform(canonical_pts: np.ndarray, detected_pts: np.ndarray, modality: str):
    if modality == "tablet":
        return None, "identity"
    if modality == "photo":
        H, _ = cv2.findHomography(canonical_pts, detected_pts, cv2.RANSAC, 5.0)
        return H, "homography"
    if modality == "scanner":
        M, _ = cv2.estimateAffine2D(canonical_pts, detected_pts)
        return np.vstack([M, [0, 0, 1]]), "affine"
    raise ValueError(f"Unknown modality: {modality}")


def _transform_bbox(bbox: list[int], transform: np.ndarray | None) -> np.ndarray:
    x, y, w, h = bbox
    corners = np.array([[x, y], [x + w, y], [x + w, y + h], [x, y + h]], dtype=np.float64)
    if transform is None:
        return corners
    warped = cv2.perspectiveTransform(corners.reshape(-1, 1, 2), transform)
    return warped.reshape(-1, 2)


def _crop_region(img: np.ndarray, corners: np.ndarray) -> np.ndarray:
    x_min = max(0, int(corners[:, 0].min()))
    y_min = max(0, int(corners[:, 1].min()))
    x_max = min(img.shape[1], int(corners[:, 0].max()))
    y_max = min(img.shape[0], int(corners[:, 1].max()))
    if x_max <= x_min or y_max <= y_min:
        logger.warning("Degenerate crop region: (%d,%d)-(%d,%d)", x_min, y_min, x_max, y_max)
        return np.zeros((10, 10, 3), dtype=np.uint8)
    return img[y_min:y_max, x_min:x_max].copy()


def _check_qr(img: np.ndarray, corners: np.ndarray, question_id: str, answer_box_id: str, margin: int = 80) -> str:
    x_min = max(0, int(corners[:, 0].min()) - margin)
    y_min = max(0, int(corners[:, 1].min()) - margin)
    x_max = min(img.shape[1], int(corners[:, 0].max()) + margin)
    y_max = min(img.shape[0], int(corners[:, 1].max()) + margin)
    region = img[y_min:y_max, x_min:x_max]
    if region.size == 0:
        return "absent"

    data, _, _ = cv2.QRCodeDetector().detectAndDecode(region)
    if not data:
        try:
            from pyzbar.pyzbar import decode as pyzbar_decode
            results = pyzbar_decode(region)
            if results:
                data = results[0].data.decode("utf-8")
        except ImportError:
            pass
    if not data:
        return "absent"

    try:
        payload = json.loads(data)
        if payload.get("q") == question_id and payload.get("b") == answer_box_id:
            return "pass"
        logger.warning("QR mismatch for answer_box %s: got %s", answer_box_id, payload)
        return "fail"
    except (json.JSONDecodeError, AttributeError):
        return "fail"


def extract_page(
    question: dict,
    image_bytes: bytes | None,
    modality: str,
    page_index: int = 0,
    ink_strokes: list | None = None,
    submission_id: str | None = None,
) -> dict:
    submission_id = submission_id or str(uuid.uuid4())
    q_id = question["question_id"]
    canvas_w, canvas_h = question["page_w_px"], question["page_h_px"]

    page_boxes = [b for b in question["answer_boxes"] if b.get("page_index") == page_index and b.get("bbox")]
    if not page_boxes:
        return {
            "page_index": page_index,
            "markers_detected": "N/A",
            "transform_type": "none",
            "crops": [],
            "image_resolution": None,
            "image_dpi": None,
            "error": f"No answer boxes found on page {page_index} for question {q_id}.",
        }

    out_dir = Path(settings.UPLOAD_DIR) / q_id / submission_id / "crops"
    out_dir.mkdir(parents=True, exist_ok=True)

    if modality == "tablet":
        result = _extract_tablet(question, page_boxes, ink_strokes, submission_id, out_dir)
        result["page_index"] = page_index
        return result

    if image_bytes is None:
        raise ValueError("image_bytes required for photo/scanner modality")

    img, detected_dpi = _decode_image(image_bytes)
    img_h, img_w = img.shape[:2]

    orig_dir = Path(settings.UPLOAD_DIR) / q_id / submission_id
    orig_dir.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(orig_dir / f"page{page_index}_original.jpg"), img)

    detected_markers = _detect_aruco_markers(img)
    n_detected = len([mid for mid in detected_markers if mid in (0, 1, 2, 3)])

    if n_detected < 4:
        return {
            "page_index": page_index,
            "markers_detected": f"{n_detected}/4",
            "transform_type": "none",
            "crops": [],
            "image_resolution": f"{img_w}x{img_h}",
            "image_dpi": detected_dpi,
            "error": f"Only {n_detected}/4 ArUco markers detected on page {page_index}. Flag for manual review.",
        }

    canonical_pos = get_marker_positions(canvas_w, canvas_h)
    src_pts = np.array([canonical_pos[i] for i in range(4)], dtype=np.float64)
    dst_pts = np.array([detected_markers[i] for i in range(4)], dtype=np.float64)
    transform, transform_type = _compute_transform(src_pts, dst_pts, modality)

    crops = []
    for box in page_boxes:
        bbox = box["bbox"]
        warped = _transform_bbox(bbox, transform)
        crop_img = _crop_region(img, warped)
        crop_path = out_dir / f"{box['id']}.png"
        cv2.imwrite(str(crop_path), crop_img)

        qr_check = _check_qr(img, warped, q_id, box["id"])
        x_min, y_min = int(warped[:, 0].min()), int(warped[:, 1].min())
        x_max, y_max = int(warped[:, 0].max()), int(warped[:, 1].max())

        crops.append({
            "answer_box_id": box["id"],
            "crop_path": str(crop_path),
            "qr_check": qr_check,
            "warped_bbox": [x_min, y_min, x_max, y_max],
        })

    return {
        "page_index": page_index,
        "markers_detected": f"{n_detected}/4",
        "transform_type": transform_type,
        "crops": crops,
        "image_resolution": f"{img_w}x{img_h}",
        "image_dpi": detected_dpi,
    }


def _extract_tablet(question: dict, page_boxes: list[dict], ink_strokes: list | None, submission_id: str, out_dir: Path) -> dict:
    canvas_w, canvas_h = question["page_w_px"], question["page_h_px"]

    canvas_img = np.ones((canvas_h, canvas_w, 3), dtype=np.uint8) * 255
    for stroke in ink_strokes or []:
        points = stroke.get("points", [])
        if len(points) < 2:
            continue
        pts = np.array(points, dtype=np.int32)
        cv2.polylines(canvas_img, [pts], isClosed=False, color=(0, 0, 0), thickness=2)

    crops = []
    for box in page_boxes:
        x, y, w, h = box["bbox"]
        x2, y2 = min(x + w, canvas_w), min(y + h, canvas_h)
        crop_img = canvas_img[y:y2, x:x2].copy()
        crop_path = out_dir / f"{box['id']}.png"
        cv2.imwrite(str(crop_path), crop_img)
        crops.append({
            "answer_box_id": box["id"],
            "crop_path": str(crop_path),
            "qr_check": "absent",
            "warped_bbox": [x, y, x2, y2],
        })

    return {
        "markers_detected": "N/A",
        "transform_type": "identity",
        "crops": crops,
        "image_resolution": f"{canvas_w}x{canvas_h}",
        "image_dpi": None,
    }
