from __future__ import annotations

import base64
import io
import json
import logging
from pathlib import Path

import cv2
import qrcode
from PIL import Image as PILImage

from config import settings

logger = logging.getLogger(__name__)

KATEX_CSS = "https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.css"
KATEX_JS = "https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.js"
KATEX_AUTORENDER = "https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/contrib/auto-render.min.js"

ARUCO_DICT_ID = getattr(cv2.aruco, settings.ARUCO_DICT, cv2.aruco.DICT_4X4_50)
ARUCO_DICT = cv2.aruco.getPredefinedDictionary(ARUCO_DICT_ID)
CORNER_MARKER_IDS = [0, 1, 2, 3]

_MARKER_ZONE = settings.MARKER_MARGIN_PX + settings.MARKER_SIZE_PX
_QR_SIZE = 46
_QR_GUTTER = _QR_SIZE + 20

TOP_MARGIN = _MARKER_ZONE + 24
BOTTOM_MARGIN = _MARKER_ZONE + 24
RIGHT_MARGIN = _MARKER_ZONE + 24
LEFT_MARGIN = _MARKER_ZONE + _QR_GUTTER + 20


def get_marker_positions(canvas_w: int, canvas_h: int) -> dict[int, tuple[int, int]]:
    m = settings.MARKER_MARGIN_PX
    s = settings.MARKER_SIZE_PX
    half = s // 2
    return {
        0: (m + half, m + half),
        1: (canvas_w - m - half, m + half),
        2: (m + half, canvas_h - m - half),
        3: (canvas_w - m - half, canvas_h - m - half),
    }


def _b64_png(img: PILImage.Image) -> str:
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


def _aruco_data_uri(marker_id: int) -> str:
    arr = cv2.aruco.generateImageMarker(ARUCO_DICT, marker_id, settings.MARKER_SIZE_PX)
    return "data:image/png;base64," + _b64_png(PILImage.fromarray(arr))


def _qr_data_uri(question_id: str, answer_box_id: str) -> str:
    qr = qrcode.QRCode(version=1, error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=5, border=2)
    qr.add_data(json.dumps({"q": question_id, "b": answer_box_id}))
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
    return "data:image/png;base64," + _b64_png(img)


def _render_marks(text: str, marks: list[dict]) -> str:
    font_size = None
    for mark in marks or []:
        mt = mark.get("type")
        if mt == "bold":
            text = f"<strong>{text}</strong>"
        elif mt == "italic":
            text = f"<em>{text}</em>"
        elif mt == "fontSize":
            font_size = mark.get("attrs", {}).get("size")
    if font_size:
        text = f'<span style="font-size:{font_size}">{text}</span>'
    return text


def _render_inline(nodes: list[dict] | None) -> str:
    out = []
    for n in nodes or []:
        t = n.get("type")
        if t == "text":
            out.append(_render_marks(n.get("text", ""), n.get("marks")))
        elif t == "equation":
            latex = n.get("attrs", {}).get("latex", "")
            display = n.get("attrs", {}).get("display", False)
            delim = f"\\[{latex}\\]" if display else f"\\({latex}\\)"
            cls = "eq-block" if display else "eq-inline"
            out.append(f'<span class="{cls}">{delim}</span>')
        elif t == "hardBreak":
            out.append("<br/>")
    return "".join(out)


def _render_top_level(node: dict) -> dict:
    t = node.get("type")

    if t == "paragraph":
        inline_nodes = node.get("content") or []
        inner = _render_inline(inline_nodes) or "&nbsp;"
        has_block_eq = any(
            c.get("type") == "equation" and c.get("attrs", {}).get("display") for c in inline_nodes
        )
        return {"html": f"<p>{inner}</p>", "atomic": has_block_eq, "answer_box_id": None}

    if t == "heading":
        level = node.get("attrs", {}).get("level", 2)
        inner = _render_inline(node.get("content"))
        return {"html": f"<h{level}>{inner}</h{level}>", "atomic": False, "answer_box_id": None}

    if t == "answerBox":
        attrs = node.get("attrs", {})
        label = attrs.get("label") or "answer"
        width_pct = attrs.get("widthPercent", 100)
        min_h = attrs.get("minHeight", 90)
        return {
            "html": f'<div class="answer-box-node" style="width:{width_pct}%;min-height:{min_h}px"><div class="ab-label">☐ {label}</div></div>',
            "atomic": True,
            "answer_box_id": attrs.get("id"),
            "width_percent": width_pct,
        }

    logger.warning("Unknown top-level node type %r — skipping", t)
    return {"html": "", "atomic": False, "answer_box_id": None}


def _flatten(content_doc: dict) -> list[dict]:
    return [_render_top_level(n) for n in content_doc.get("content", [])]


_MEASURE_CSS = """
body{margin:0}
.measure{font-family:Georgia,'Times New Roman',serif}
.measure p{margin:0 0 10px;font-size:15px;line-height:1.5;color:#111}
.measure h1,.measure h2,.measure h3{margin:14px 0 8px}
.measure .answer-box-node{border:2px dashed #999;border-radius:6px;min-height:90px;margin:10px 0;padding:8px;box-sizing:border-box}
.measure .ab-label{font-size:11px;font-weight:bold;color:#888}
"""


def _measure(blocks: list[dict], content_w: int) -> list[dict]:
    from playwright.sync_api import sync_playwright

    fragments = "".join(f'<div data-idx="{i}">{b["html"]}</div>' for i, b in enumerate(blocks))
    html = f"""<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="{KATEX_CSS}">
<style>{_MEASURE_CSS} .measure{{width:{content_w}px}}</style></head>
<body><div class="measure">{fragments}</div>
<script src="{KATEX_JS}"></script>
<script src="{KATEX_AUTORENDER}"></script>
<script>
renderMathInElement(document.body, {{delimiters:[
  {{left:"\\\\(", right:"\\\\)", display:false}},
  {{left:"\\\\[", right:"\\\\]", display:true}}
]}});
window.__ready = true;
</script></body></html>"""

    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": content_w, "height": 400})
        page.set_content(html, wait_until="load")
        page.wait_for_function("window.__ready === true", timeout=15000)
        rects = page.eval_on_selector_all(
            ".measure > div",
            "els => els.map(e => { const r = e.getBoundingClientRect(); return {top: r.top, height: r.height}; })",
        )
        browser.close()
    return rects


def _paginate(blocks: list[dict], rects: list[dict], usable_h: float):
    shift = 0.0
    page_breaks = set()
    answer_layout = {}
    max_page_idx = 0

    for i, (b, r) in enumerate(zip(blocks, rects)):
        natural_top = r["top"]
        h = r["height"]
        adjusted_top = natural_top + shift
        page_idx = int(adjusted_top // usable_h)
        offset = adjusted_top - page_idx * usable_h

        if b["atomic"] and offset + h > usable_h and h <= usable_h:
            needed_shift = (page_idx + 1) * usable_h - adjusted_top
            shift += needed_shift
            adjusted_top += needed_shift
            page_idx += 1
            offset = 0.0
            page_breaks.add(i)

        max_page_idx = max(max_page_idx, page_idx)
        if b["answer_box_id"]:
            answer_layout[b["answer_box_id"]] = (page_idx, offset, h, b.get("width_percent", 100))

    return page_breaks, answer_layout, max_page_idx + 1


_PRINT_CSS_TEMPLATE = """
@page {{ size: {W}px {H}px; margin: 0; }}
body{{margin:0}}
.doc-page{{position:relative;width:{W}px;height:{H}px;box-sizing:border-box;overflow:hidden}}
.doc-page + .doc-page{{break-before:page}}
.content{{position:absolute;left:{L}px;top:{T}px;width:{CW}px;font-family:Georgia,'Times New Roman',serif}}
.content p{{margin:0 0 10px;font-size:15px;line-height:1.5;color:#111}}
.content h1,.content h2,.content h3{{margin:14px 0 8px}}
.answer-box-node{{border:2px dashed #999;border-radius:6px;margin:10px 0;padding:8px;box-sizing:border-box}}
.ab-label{{font-size:11px;font-weight:bold;color:#888}}
.marker-img,.qr-img{{position:absolute}}
"""


def render_finalized_question(question: dict) -> dict:
    from playwright.sync_api import sync_playwright

    q_id = question["question_id"]
    dpi = question.get("dpi") or settings.DEFAULT_DPI
    canvas_w = round(8.27 * dpi)
    canvas_h = round(11.69 * dpi)
    content_w = canvas_w - LEFT_MARGIN - RIGHT_MARGIN
    usable_h = canvas_h - TOP_MARGIN - BOTTOM_MARGIN

    blocks = _flatten(question.get("content") or {"content": []})
    if not blocks:
        raise ValueError("Question has no content to render")

    rects = _measure(blocks, content_w)
    page_breaks, answer_layout, page_count = _paginate(blocks, rects, usable_h)

    marker_uris = {mid: _aruco_data_uri(mid) for mid in CORNER_MARKER_IDS}
    marker_positions = get_marker_positions(canvas_w, canvas_h)
    marker_size = settings.MARKER_SIZE_PX

    def markers_html():
        imgs = []
        for mid, (cx, cy) in marker_positions.items():
            x, y = cx - marker_size // 2, cy - marker_size // 2
            imgs.append(f'<img class="marker-img" style="left:{x}px;top:{y}px;width:{marker_size}px;height:{marker_size}px" src="{marker_uris[mid]}"/>')
        return "".join(imgs)

    page_groups: list[list[int]] = [[]]
    for i in range(len(blocks)):
        if i in page_breaks:
            page_groups.append([])
        page_groups[-1].append(i)

    pages_html = []
    for group in page_groups:
        content_html = "".join(blocks[i]["html"] for i in group)
        overlays = [markers_html()]
        for i in group:
            bid = blocks[i]["answer_box_id"]
            if bid and bid in answer_layout:
                _, top_off, h, _ = answer_layout[bid]
                qr_x = LEFT_MARGIN - _QR_SIZE - 15
                qr_y = TOP_MARGIN + top_off + h / 2 - _QR_SIZE / 2
                overlays.append(
                    f'<img class="qr-img" style="left:{qr_x}px;top:{qr_y}px;'
                    f'width:{_QR_SIZE}px;height:{_QR_SIZE}px" src="{_qr_data_uri(q_id, bid)}"/>'
                )
        pages_html.append(f'<div class="doc-page">{"".join(overlays)}<div class="content">{content_html}</div></div>')

    css = _PRINT_CSS_TEMPLATE.format(W=canvas_w, H=canvas_h, L=LEFT_MARGIN, T=TOP_MARGIN, CW=content_w)
    final_html = f"""<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="{KATEX_CSS}">
<style>{css}</style></head>
<body>{"".join(pages_html)}
<script src="{KATEX_JS}"></script>
<script src="{KATEX_AUTORENDER}"></script>
<script>
renderMathInElement(document.body, {{delimiters:[
  {{left:"\\\\(", right:"\\\\)", display:false}},
  {{left:"\\\\[", right:"\\\\]", display:true}}
]}});
window.__ready = true;
</script></body></html>"""

    pdf_path = Path(settings.PDF_DIR) / f"{q_id}.pdf"
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": canvas_w, "height": canvas_h})
        page.set_content(final_html, wait_until="load")
        page.wait_for_function("window.__ready === true", timeout=15000)
        page.pdf(path=str(pdf_path), width=f"{canvas_w}px", height=f"{canvas_h}px", print_background=True, margin={"top": "0", "bottom": "0", "left": "0", "right": "0"})
        browser.close()

    boxes = {
        bid: [page_idx, LEFT_MARGIN, round(TOP_MARGIN + top_off), round(content_w * width_pct / 100), round(h)]
        for bid, (page_idx, top_off, h, width_pct) in answer_layout.items()
    }
    logger.info("Rendered %s -> %s (%d pages, %d answer boxes)", q_id, pdf_path, page_count, len(boxes))

    return {"page_w_px": canvas_w, "page_h_px": canvas_h, "page_count": page_count, "boxes": boxes}
