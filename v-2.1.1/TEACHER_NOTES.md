# `v-2.1.1/` — the teacher side, as it actually exists

> Produced 2026-08-30 by reading all 26 source/config files in `backend/` and
> `frontend/src/`, and by **executing the page-geometry, ArUco, homography,
> pagination and primary-key logic directly** (see §9 for the exact runs).
> Written for someone building the *student* app that consumes this output.
>
> | Tag | Meaning |
> |---|---|
> | **VERIFIED** | Executed and observed during this read-through |
> | **CODE-READ** | Traced in the source, never run |
> | **UNVERIFIED** | Written, but nothing here proves it works |
>
> **Nothing in this folder was modified. This file is the only addition.**
> `backend/uploads/` and `backend/pdfs/` were redirected to a scratch directory
> during execution so the repo tree stayed untouched.

---

## 0. What it is, in one paragraph

A **standalone FastAPI + React app** (title "NLP-OCR Evaluation Subsystem",
version 0.1.0) in which a teacher writes a worksheet in a TipTap rich-text
editor, drops **answer boxes** into the flow as first-class document nodes, and
then **finalizes** it. Finalizing launches headless Chromium (Playwright) twice:
once to *measure* where every block lands, once to *print* the document to a
**PDF** carrying four **ArUco corner markers** and one QR per answer box. The
measured pixel rectangle of each answer box is written back into SQLite. A
second screen re-uploads a photo of that printed page and cuts the answer boxes
back out using the markers.

**It is not a grading system.** There is no model answer, no rubric, no marks
total, no correct answer, no user, no auth, and no student — anywhere. I grepped
the entire tree for that vocabulary; §4.3 has the result. It is a
*worksheet generator plus a geometric answer-box extractor*, and it stops there.

It shares no code, no database, and no auth with `ASC_Capstone` or
`Capstone_Android`. It has never been run: **there is no `nlp_ocr.db` anywhere on
disk, `backend/pdfs/` is empty, and `backend/uploads/` does not exist**
(VERIFIED). It is **not a git repo** and has no history (VERIFIED).

---

## 1. Q1 — Does it render a printable worksheet page?

**Yes. It produces a real PDF file on disk.** This is the strongest, most
complete part of the codebase.

### 1.1 The path

`POST /api/questions/{id}/finalize` (`routers/questions.py:130`) →
`services/doc_renderer.py:render_finalized_question()` (`:217`).

Two headless Chromium launches (CODE-READ — **playwright is not installed in
this environment, so neither launch has ever executed here**):

1. **`_measure()` (`:144`)** renders every top-level block into a throwaway page
   at `content_w` width, waits for KaTeX to finish, and reads back each block's
   `getBoundingClientRect()` → `{top, height}`.
2. **`_paginate()` (`:175`)** walks those rects, assigns each block a page index,
   and pushes an *atomic* block (an answer box or a display equation) onto the
   next page if it would straddle the boundary. It records
   `answer_box_id → (page_idx, offset, height, width_percent)`.
3. The final HTML is composed with one `<div class="doc-page">` per page and
   printed with `page.pdf(...)` to **`./pdfs/{question_id}.pdf`**.

### 1.2 Format and dimensions (VERIFIED — arithmetic executed)

| Property | Value | Source |
|---|---|---|
| Format | **PDF** (single file per question, multi-page) | `page.pdf(path=…)` `:288` |
| Canvas | **1240 × 1754 px** | `round(8.27*dpi) × round(11.69*dpi)`, `:222-223` |
| DPI constant | **150** | `DEFAULT_DPI`, and `Question.dpi` default |
| Implied paper | 209.97 × 297.01 mm — **A4 to within 0.03 mm** | computed |
| Top / bottom margin | **124 px** | `_MARKER_ZONE(100) + 24` |
| Left margin | **186 px** | `_MARKER_ZONE(100) + _QR_GUTTER(66) + 20` |
| Right margin | **124 px** | `_MARKER_ZONE + 24` |
| Content width | **930 px** | `1240 − 186 − 124` |
| Usable height/page | **1506 px** | `1754 − 124 − 124` |

The canvas size is **always 1240 × 1754**. `Question.physical_page` exists,
defaults to `"A4"`, is echoed by the API and shown in the UI dropdown — and is
**never read by the renderer**; A4 is hardcoded as the literals `8.27` and
`11.69` (CODE-READ). `Question.dpi` is likewise never settable: `QuestionCreate`
has no `dpi` field and `create_question` never assigns one, so it is always the
column default of 150.

### 1.3 ⚠️ The PDF is **not physically A4** (VERIFIED — arithmetic)

`page.pdf(width="1240px", height="1754px")`. CSS pixels in a PDF are **1/96
inch**, not 1/150 inch. So the emitted media box is:

```
1240/96 × 1754/96 in = 12.917 × 18.271 in = 328.1 × 464.1 mm = 930.0 × 1315.5 pt
A4 = 595.3 × 841.9 pt  →  this page is 1.56× A4 in each dimension
```

Printing it on A4 requires **fit-to-page at ~64%**. A 60 px ArUco marker is
15.9 mm at actual size, **10.2 mm after fit-to-A4 scaling** — still detectable,
but the "150 dpi" intent is not honoured by the artifact. Because everything
downstream registers off the markers, the *scale* is absorbed by the homography;
this matters for **print setup and marker legibility**, not for coordinate math.

### 1.4 Fiducials — yes, ArUco at all four corners (VERIFIED)

| | |
|---|---|
| Dictionary | **`cv2.aruco.DICT_4X4_50`** (`ARUCO_DICT` setting, `config.py:11`) |
| Marker IDs | **0, 1, 2, 3** (`CORNER_MARKER_IDS`, `doc_renderer.py:23`) |
| Size | **60 × 60 px** (`MARKER_SIZE_PX`) |
| Margin from page edge | **40 px** (`MARKER_MARGIN_PX`) |
| Generated by | `cv2.aruco.generateImageMarker`, inlined as a base64 PNG data URI |
| Drawn on | **every page** of the PDF |

**Exact positions on the 1240 × 1754 canvas** (`get_marker_positions`, `:35`):

| id | corner | **centre (cx, cy)** | top-left draw position |
|---|---|---|---|
| **0** | top-left | **(70, 70)** | (40, 40) |
| **1** | top-right | **(1170, 70)** | (1140, 40) |
| **2** | **bottom-left** | **(70, 1684)** | (40, 1654) |
| **3** | **bottom-right** | **(1170, 1684)** | (1140, 1654) |

Note the ordering is **row-major (TL, TR, BL, BR) — not clockwise.** Getting 2
and 3 backwards silently produces a mirrored homography.

**Round-trip VERIFIED:** I generated all four markers exactly as `doc_renderer`
does, placed them at those coordinates on a blank 1240×1754 canvas, and ran the
extractor's own `_detect_aruco_markers`. All four were detected, each within
**0.707 px** of its canonical position.

That residual is a real (tiny) systematic bias, not noise: a 60 px marker drawn
at x=40 spans pixels 40–99, whose true centre is **69.5**, while
`get_marker_positions` reports `40 + 60//2 = 70`. Every marker is off by exactly
(+0.5, +0.5) px. It is a constant translation and the homography absorbs nearly
all of it — but if you reimplement the canonical points on the student side,
reproduce `m + s//2` rather than the geometrically correct centre, or you will
disagree with the teacher side by half a pixel.

### 1.5 QR codes — drawn, but undecodable as rendered

One QR per answer box, in the left gutter, vertically centred on its box
(`:259-264`): `qr_x = LEFT_MARGIN − 46 − 15 = 125`, `qr_y = TOP_MARGIN +
top_off + h/2 − 23`. Payload is `json.dumps({"q": question_id, "b":
answer_box_id})` — question id and answer box id, **no question text, no marks,
no page index**.

**They cannot be decoded at the size they are rendered** (VERIFIED by
arithmetic; `qrcode` is not installed so I could not generate one):

```
payload  {"q": "<36-char uuid4>", "b": "ab_<17 chars>"}   = 72 bytes
qrcode.QRCode(version=1, ...) + qr.make(fit=True)  → fit=True grows past v1
smallest ECC-M version holding 72 bytes = version 5 = 37×37 modules
+ border=2 quiet zone                              = 41×41 modules
rendered into _QR_SIZE = 46 px                     = 1.12 px per module
```

A QR generally needs ≥2–3 px/module to decode. At **1.12 px/module** it is lost
before the page is even printed. This independently reproduces the finding in
`mobile_Extract/NOTES.md` §5, where the sibling service could not decode the
sample page's QRs at any upscale. **Treat `qr_check` as decorative.** The ArUco
markers are the working registration mechanism; the QRs are not.

---

## 2. Q2 — Does anything record where the answer boxes sit?

**Yes — in three places, all in the same convention.** This is the single most
useful thing on the teacher side for you.

### 2.1 Where the numbers are produced

`render_finalized_question` returns (`doc_renderer.py:291-297`):

```python
boxes = {
    bid: [page_idx,
          LEFT_MARGIN,                              # x  — always 186
          round(TOP_MARGIN + top_off),              # y
          round(content_w * width_pct / 100),       # w
          round(h)]                                 # h
    for bid, (page_idx, top_off, h, width_pct) in answer_layout.items()
}
return {"page_w_px": canvas_w, "page_h_px": canvas_h,
        "page_count": page_count, "boxes": boxes}
```

`finalize_question` (`routers/questions.py:146-156`) unpacks that into columns:

```python
page_index, x, y, w, h = layout["boxes"][box.id]
box.page_index = page_index
box.bbox_x, box.bbox_y, box.bbox_w, box.bbox_h = x, y, w, h
```

If a box exists in the DB but not in the rendered layout it raises **500** with
an explicit "out of sync" message rather than silently writing NULLs — a good
guard.

### 2.2 The stored schema (`models.py:52`)

```python
class AnswerBox(Base):
    __tablename__ = "answer_boxes"
    id          = Column(String, primary_key=True)      # client-generated, see §3.2
    question_id = Column(String, ForeignKey("questions.id", ondelete="CASCADE"))
    label       = Column(String, nullable=True)
    points      = Column(Integer, nullable=False, default=1)
    order_index = Column(Integer, nullable=False, default=0)
    page_index  = Column(Integer, nullable=True)        # NULL until finalize
    bbox_x      = Column(Integer, nullable=True)        # NULL until finalize
    bbox_y      = Column(Integer, nullable=True)
    bbox_w      = Column(Integer, nullable=True)
    bbox_h      = Column(Integer, nullable=True)
```

All five geometry columns are **NULL while the question is a draft** and are
populated only by `finalize`.

### 2.3 The convention — state it exactly

> **`[x, y, w, h]` — top-left origin, x rightward, y downward, integer
> _canonical page pixels_ on the 1240 × 1754 canvas, paired with a separate
> `page_index`.**

- **Not** `[x1, y1, x2, y2]`. **Not** points, millimetres, or percentages.
- **Not** relative to the photo — relative to the *ideal rendered page*.
- The origin is the **page corner**, not the content box: `y` already includes
  `TOP_MARGIN`, `x` already equals `LEFT_MARGIN`.

**Beware — the same codebase uses the other convention on the way out.**
`extractor.py:185` emits `warped_bbox = [x_min, y_min, x_max, y_max]` in
**photo pixels**. So `bbox` is `[x,y,w,h]` canonical, `warped_bbox` is
`[x1,y1,x2,y2]` observed. Nothing in the code flags the switch. (Same trap
documented in `mobile_Extract/NOTES.md` §3.1.)

### 2.4 Properties of the numbers you will actually receive (VERIFIED)

Executed `_paginate` with synthetic measured rects:

```
page_breaks = {3}  page_count = 2
  ab_one: page=0 offset=210.0 h=90 width%=100  -> bbox [186, 334, 930, 90]
  ab_two: page=1 offset=0.0   h=90 width%=50   -> bbox [186, 124, 465, 90]
```

- **`x` is always 186.** It is the literal `LEFT_MARGIN`. A 25%-width box does
  not move; only `w` shrinks. Boxes never sit side by side and never indent.
- **`w` takes exactly four values**, from the editor's width dropdown:
  `930` (100%), `698` (75%), `465` (50%), `233` (25%).
- **`h`** is the measured rendered height, floor 90 px (`minHeight`, adjustable
  in the editor in 40 px steps between 60 and 800 — note the editor's floor of
  60 disagrees with the CSS `min-height:90px` in `_MEASURE_CSS`, so heights
  below 90 cannot actually be achieved).
- The rect is the **outer border-box** of `.answer-box-node`: it includes a 2 px
  dashed border, 8 px padding, and the `☐ label` caption line rendered *inside*
  the box. The writable region is inset from `bbox` by roughly 10 px on each side
  and ~15 px more at the top. **Crop `bbox` verbatim and you capture the caption
  and the border along with the handwriting.**
- The `margin:10px 0` around the box is *outside* `bbox`.

### 2.5 Two structural risks in these numbers (CODE-READ — playwright absent)

1. **The measure pass and the print pass build different DOM.** `_measure` wraps
   every block in `<div data-idx="i">`; the print page concatenates the same
   block HTML as **raw siblings** (`:253`). Adjacent-sibling margin collapsing is
   not guaranteed to match across those two structures, so the printed `y` can
   drift from the measured `y`. Neither has ever run here. This is the single
   most likely source of a systematic vertical offset in the stored `bbox`.
2. **Non-atomic blocks are never broken across pages, and the page clips.**
   `_paginate` only shifts a block when `b["atomic"]` is true — i.e. answer boxes
   and display equations. A long paragraph that crosses the boundary is left
   where it is, and `.doc-page` carries `overflow:hidden`, so **the overflowing
   text is silently clipped out of the PDF.** Answer-box geometry stays correct;
   the question text can be lost.

---

## 3. Q3 — What is a question's identity?

### 3.1 "Question" means the whole worksheet

A `Question` row is **one TipTap document** — headings, paragraphs, equations
and *N* answer boxes — rendered to *one* multi-page PDF. It is not one prompt.
There is no entity below it except `AnswerBox`. Budget for this naming trap:
his "question" is closer to your **assignment**, and his **answer box** is the
closest thing to your **question**.

### 3.2 The two id types

| | `Question.id` | `AnswerBox.id` |
|---|---|---|
| Type | `String`, **uuid4** | `String`, **not a UUID** |
| Generated | **server**, `str(uuid.uuid4())` at create (`questions.py:91`) | **browser**, `AnswerBoxNode.jsx:5` |
| Formula | — | `'ab_' + Math.random().toString(36).slice(2,10) + Date.now().toString(36)` |
| Example | `e9cce9d0-34b1-4657-87ad-65898e6a71ab` | `ab_syzn1vsmmsrm6jat` |

`AnswerBox.id` is `Math.random`-derived with **no server-side collision check** —
it is the bare primary key, and the client picks it. Two boxes created in the
same millisecond in two tabs collide with probability ~1/2^40 per pair; not a
practical worry, but it is an unvalidated client-supplied PK.

### 3.3 Does identity survive an edit? (CODE-READ)

State machine: **`draft` → `finalized`**, one way.

**While `draft`** — `PUT /api/questions/{id}/blocks` calls `_upsert_answer_boxes`
(`questions.py:64`) on every autosave (debounced 500 ms in the editor):

- a box still present in the doc **keeps its id**; `label`, `points` and
  `order_index` are overwritten;
- `order_index` is **reassigned from document order on every save** — it is
  positional, not stable;
- a box no longer in the doc is **hard-deleted** (`db.delete`).

So: **editing text around a box preserves its id. Deleting the box node and
re-inserting it produces a brand-new id**, and the old row (with any geometry) is
gone. There is no tombstone and no history.

**Once `finalized`** — `_assert_draft` returns **409** on `PUT /blocks` and on
`finalize`. The content, the geometry and the PDF are frozen. The *only* offered
path forward is `POST /{id}/clone`.

### 3.4 ⚠️ Clone is broken for any question that has an answer box (VERIFIED)

`clone_question` (`questions.py:195-202`) inserts new `AnswerBox` rows **reusing
the original `b.id` verbatim** into a new question. But `AnswerBox.id` is the
**sole primary key** — there is no composite `(question_id, id)` key. I mirrored
the DDL in raw SQLite and ran it:

```
original question + its answer box inserted OK
clone insert REJECTED -> IntegrityError : UNIQUE constraint failed: answer_boxes.id
```

So cloning succeeds only for a question with **zero** answer boxes. Every other
clone raises `IntegrityError` → unhandled → **500**. The frontend swallows it
into `console.error` (`QuestionList.jsx:49`), so the user sees the Clone button
do nothing.

**Consequences worth carrying into your planning:**
- A finalized question can never be revised. Edit → 409; clone → 500.
- `GET /{id}/pdf` 404s if the file is missing and tells the user to *"try
  re-finalizing (clone + finalize) to regenerate it"* — **the documented recovery
  path is the broken one.** A deleted PDF is unrecoverable through the API.
- `derived_from` (a self-FK on `questions`) is written by clone and, because
  clone cannot complete, is **in practice always NULL**.

---

## 4. Q4 — Where does it store data?

### 4.1 Its own database, not a shared one

**SQLite**, `sqlite:///./nlp_ocr.db` (`config.py:6`) — relative to the process
CWD, so `backend/nlp_ocr.db`. **The file does not exist anywhere on disk**
(VERIFIED) — the app has never been booted.

Created by `Base.metadata.create_all` at startup (`database.py:25`). **No Alembic,
no migrations** — any schema change means deleting the file.

It is **completely separate** from `ASC_Capstone`'s PostgreSQL. No shared engine,
no foreign key, no user table, no cross-reference. Note also the key-type
mismatch: this side uses **string UUID** primary keys; `ASC_Capstone` uses
**integer autoincrement**. Any bridge needs a mapping table.

### 4.2 Full schema — three tables (`models.py`)

```python
class Question(Base):                     # __tablename__ = "questions"
    id            = Column(String, primary_key=True, default=_uuid)   # uuid4
    state         = Column(Enum("draft","finalized"), default="draft", nullable=False)
    physical_page = Column(String, nullable=False, default="A4")   # stored, NEVER read
    dpi           = Column(Integer, nullable=False, default=150)   # never settable
    content       = Column(JSON, nullable=True)    # the whole TipTap document
    page_w_px     = Column(Integer, nullable=True) # NULL until finalize -> 1240
    page_h_px     = Column(Integer, nullable=True) # NULL until finalize -> 1754
    page_count    = Column(Integer, nullable=True) # NULL until finalize; NEVER SERIALIZED (§5.3)
    derived_from  = Column(String, ForeignKey("questions.id"), nullable=True)
    created_at    = Column(DateTime, default=_utcnow, nullable=False)
    finalized_at  = Column(DateTime, nullable=True)
    answer_boxes  = relationship("AnswerBox", cascade="all, delete-orphan",
                                 order_by="AnswerBox.order_index")

class AnswerBox(Base):                    # __tablename__ = "answer_boxes"
    id          = Column(String, primary_key=True)          # client-generated
    question_id = Column(String, ForeignKey("questions.id", ondelete="CASCADE"),
                         nullable=False)
    label       = Column(String, nullable=True)
    points      = Column(Integer, nullable=False, default=1)
    order_index = Column(Integer, nullable=False, default=0)
    page_index  = Column(Integer, nullable=True)   # \
    bbox_x      = Column(Integer, nullable=True)   #  |  all NULL until finalize
    bbox_y      = Column(Integer, nullable=True)   #  |  [x, y, w, h] canonical page px
    bbox_w      = Column(Integer, nullable=True)   #  |
    bbox_h      = Column(Integer, nullable=True)   # /

class Submission(Base):                   # __tablename__ = "submissions"
    id                  = Column(String, primary_key=True, default=_uuid)
    question_id         = Column(String, ForeignKey("questions.id"), nullable=False)
    modality            = Column(Enum("tablet","photo","scanner"), nullable=False)
    original_image_path = Column(String, nullable=True)   # declared, NEVER WRITTEN
    image_width         = Column(Integer, nullable=True)  # declared, NEVER WRITTEN
    image_height        = Column(Integer, nullable=True)  # declared, NEVER WRITTEN
    image_dpi           = Column(Integer, nullable=True)
    manifest            = Column(JSON, nullable=True)     # the whole extraction result
    created_at          = Column(DateTime, default=_utcnow, nullable=False)
```

Three of `Submission`'s columns (`original_image_path`, `image_width`,
`image_height`) are declared and **never assigned** by any code path
(`submissions.py:121-127`, `:153`) — CODE-READ, confirmed by grep. The image
*is* written to disk (`extractor.py:148`,
`uploads/{q_id}/{sub_id}/page{N}_original.jpg`) but its path never reaches the
row.

Crops go to the **filesystem**, not the DB:
`uploads/{question_id}/{submission_id}/crops/{answer_box_id}.png`
(`extractor.py:132`). Note this differs from the sibling `mobile_Extract`, which
stores crops as DB BLOBs.

### 4.3 Model answers, rubric, marks — **none of them exist**

This is the headline for anyone planning to grade against this.

I grepped the whole tree (`*.py *.jsx *.js *.json *.html *.css`, excluding the
lockfile) for: `model_answer`, `rubric`, `marks`, `total_marks`, `grade`,
`grading`, `score`, `correct_answer`, `answer_key`, `solution`, `student`,
`teacher`, `auth`, `token`, `jwt`, `login`. **VERIFIED result:**

- every `marks` hit is TipTap's rich-text `marks` array (bold/italic/fontSize)
  in `doc_renderer._render_marks` — a false positive;
- every other hit is `image_resolution` or the string `"Question Author"`;
- **zero hits** for model answer, rubric, grade, score, solution, answer key,
  student, teacher, auth, token, JWT, or login.

**The only grading-adjacent field in the entire codebase is
`answer_boxes.points`** (int, default 1). It is editable in the editor, stored,
and echoed by the API. **Nothing ever reads it.** Nothing sums it, nothing
compares against it, there is no assignment total. It is exactly the same dead
`points` field flagged in `mobile_Extract/NOTES.md` §6.

There is also **no question text associated with any answer box**. The prose
lives in `Question.content` as an undifferentiated TipTap block list; an answer
box knows only its `order_index` and a free-text `label` (default `""`,
hand-typed by the teacher, e.g. `1a`). Nothing records "these paragraphs are the
prompt for box `ab_x`."

---

## 5. Q5 — Export and public API

### 5.1 Every route (CODE-READ — the app was never booted; `sqlalchemy` is not installed)

Base: `http://localhost:8000` (uvicorn default; the frontend hardcodes it in
`api.js:4`). Prefixes `/api/questions` and `/api/submissions`.

| # | Method | Path | Auth | Request | Response |
|---|---|---|---|---|---|
| 1 | GET | `/` | **none** | — | `{"status":"ok","project":"NLP-OCR Evaluation Subsystem"}` |
| 2 | POST | `/api/questions` | **none** | `QuestionCreate` | `QuestionOut`, **201** |
| 3 | GET | `/api/questions` | **none** | — | `list[QuestionOut]`, newest first |
| 4 | GET | `/api/questions/{id}` | **none** | — | `QuestionOut` (404 if absent) |
| 5 | PUT | `/api/questions/{id}/blocks` | **none** | `QuestionContentUpdate` | `QuestionOut` (**409** if finalized) |
| 6 | POST | `/api/questions/{id}/finalize` | **none** | — | `QuestionOut` (400 empty, 409 already final, 500 desync) |
| 7 | GET | `/api/questions/{id}/pdf` | **none** | — | **`application/pdf`** FileResponse, `filename=question_{id}.pdf` (400 if draft, 404 if file missing) |
| 8 | POST | `/api/questions/{id}/clone` | **none** | — | `QuestionOut`, 201 — **500 in practice, §3.4** |
| 9 | POST | `/api/submissions` | **none** | multipart `question_id`, `modality`, `page_index`, `image` | `ExtractionResult` |
| 10 | POST | `/api/submissions/tablet` | **none** | `TabletSubmission` JSON, `?page_index=` | `ExtractionResult` |
| 11 | GET | `/api/submissions/{id}` | **none** | — | the raw stored `manifest` JSON (no response model) |
| 12 | GET | `/api/submissions/{id}/crops/{answer_box_id}` | **none** | — | raw **PNG** bytes |

**There is no authentication, no authorization, and no tenancy of any kind** —
identical to the sibling `mobile_Extract`, and in sharp contrast to
`ASC_Capstone`, which is JWT-gated on every route. CORS is restricted to
`http://localhost:5173` only (`main.py:21`), which is the *only* access control
present and is trivially bypassed by any non-browser client.

There is no OpenAPI customisation, but FastAPI's `/docs` and `/openapi.json` are
live by default.

### 5.2 `QuestionOut` — the shape you would consume

```jsonc
{
  "question_id": "e9cce9d0-…",
  "state": "finalized",
  "physical_page": "A4",          // never affects rendering
  "dpi": 150,
  "content": { "type": "doc", "content": [ /* TipTap blocks */ ] },
  "answer_boxes": [
    {
      "id": "ab_syzn1vsmmsrm6jat",
      "label": "",                // free text, default ""
      "points": 1,                // never used in any computation
      "bbox": [186, 334, 930, 90],// [x, y, w, h] canonical page px — NULL while draft
      "page_index": 0             // NULL while draft
    }
  ],
  "page_w_px": 1240,
  "page_h_px": 1754,
  "page_count": null,             // ALWAYS null — see §5.3
  "derived_from": null,
  "created_at": "2026-08-30T…",
  "finalized_at": "2026-08-30T…"
}
```

Note `AnswerBoxOut` does **not** carry `order_index` (it is on the model but
absent from the output schema). The array *is* ordered by it, though — the
relationship declares `order_by="AnswerBox.order_index"` — so order is implicit
in the JSON array, not explicit in a field.

### 5.3 ⚠️ `page_count` is stored but never serialized (CODE-READ)

`_question_to_out` (`questions.py:29-42`) builds `QuestionOut` with
`page_w_px` and `page_h_px` — **and omits `page_count`.** The Pydantic field
defaults to `None`, so **every API response reports `page_count: null`**, even
for a finalized 3-page question whose DB row holds `3`.

This is not just your problem — it breaks his own UI. `ExtractPage.jsx:49`
(`q.page_count > 1`) is never true, and `ExtractionView.jsx:57`
(`question.page_count || 1`) always yields 1, so **the page selector never
renders** and a single-image upload can never be targeted at page 1 or later.
The multi-page path is reachable only by uploading a PDF/TIFF, where
`create_submission` reads `q.page_count` off the DB row directly (`:92`) and so
works correctly.

**You must infer page count from `max(page_index) + 1` across `answer_boxes`,**
which undercounts if the last page holds no answer box.

### 5.4 Can you get everything for one assignment in one call?

**Partly — and the two things you asked about most are not obtainable at all.**

| What you need | Available? | How |
|---|---|---|
| **Box coordinates** | ✅ **yes, one call** | `GET /api/questions/{id}` → `answer_boxes[].bbox` + `page_index` |
| Page geometry | ✅ yes, same call | `page_w_px`, `page_h_px` |
| Page count | ⚠️ **no** — always `null` (§5.3) | infer from `max(page_index)+1` |
| Per-box `points` | ✅ yes, same call | `answer_boxes[].points` (dead field on his side) |
| Box ordering | ✅ implicit | array order; `order_index` not exposed |
| **Question text** | ⚠️ **raw only** | `content` is a TipTap doc — you must walk `content.content[]` and flatten `text` nodes and `equation.attrs.latex` yourself. There is no plain-text field and no HTML endpoint. |
| **Which text goes with which box** | ❌ **not recorded** | nothing associates prose with a box; see §4.3 |
| **Model answers** | ❌ **impossible** | the field does not exist anywhere |
| **Rubric** | ❌ **impossible** | the field does not exist anywhere |
| **Marks / total marks** | ❌ **impossible** | only the dead per-box `points`; no total |
| The printed page itself | ✅ one call | `GET /api/questions/{id}/pdf` |

**Verdict: one call (`GET /api/questions/{id}`) gets you the complete geometry,
the raw document JSON and `points`. Model answers, rubric and marks are not
obtainable in any number of calls, because they are not modelled.** Those are new
schema, not new endpoints.

---

## 6. Q6 — What does a student physically receive?

Traced concretely (CODE-READ end to end; the PDF has never been generated here):

1. Teacher authors in the editor at `http://localhost:5173/`, inserting answer
   boxes from the toolbar (`☐ Answer Box`, `EditorToolbar.jsx:69`). Autosave
   every 500 ms.
2. Teacher clicks **🔒 Finalize Question** → `window.confirm` warning that
   content will be frozen → `POST /finalize` renders the PDF to
   `backend/pdfs/{question_id}.pdf`.
3. The button swaps to **📄 Export PDF** (`AuthorPage.jsx:111-119`), which calls
   `window.open(getPdfUrl(id), '_blank')` → `GET /api/questions/{id}/pdf` →
   `FileResponse(..., media_type="application/pdf", filename=f"question_{id}.pdf")`.
   The `filename=` makes it a **browser download**.
4. **The chain ends there.** The teacher has a PDF file on their own machine.

**So: the student receives a sheet of paper, and only if the teacher prints and
hands it out.** There is no student-facing anything — no student route, no
login, no link, no email, no share, no QR-to-URL, no on-screen delivery, no
per-student copy. `/extract` is a teacher-facing *test harness* ("Upload a
scanned/photographed page and test the deterministic answer-box extraction
pipeline"), not a student submission portal.

**What is on the paper:** the question prose and equations; dashed-outline answer
boxes each captioned `☐ <label>`; four ArUco markers at the corners of every
page; one (undecodable, §1.5) QR in the left gutter beside each box.

**What is *not* on the paper — important for you:**
- **no student name, id, or field to write one in**;
- **no page number, no copy id, no assignment title**;
- **no question id in human-readable form.**

Every printed copy of a given worksheet is **byte-identical**. The QR would have
carried `question_id` + `answer_box_id`, but not who wrote on it — and it cannot
be read anyway. **Nothing on a returned photo identifies the student.** Whatever
associates a photo with a person has to come from your app.

And note the print-scaling caveat from §1.3: the PDF is 1.56× A4, so the teacher
must print fit-to-page.

---

## 7. Implied layout shape

Box positions **are** recorded, so here is the JSON they imply — his field names,
his convention, exactly as `GET /api/questions/{id}` would return it for a
finalized two-page worksheet. Values are the real ones his code produces
(x=186 always; w from the four width steps; markers as verified in §1.4).

```jsonc
{
  "question_id": "e9cce9d0-34b1-4657-87ad-65898e6a71ab",
  "state": "finalized",
  "physical_page": "A4",        // recorded but ignored by the renderer
  "dpi": 150,
  "page_w_px": 1240,            // canonical page space — ALL bbox values live here
  "page_h_px": 1754,
  "page_count": null,           // stored as 2 in SQLite, never serialized (§5.3)

  "content": { "type": "doc", "content": [ /* TipTap blocks, unassociated */ ] },

  "answer_boxes": [
    {
      "id":         "ab_syzn1vsmmsrm6jat",
      "label":      "1a",       // free text, "" by default
      "points":     1,          // dead field on his side
      "page_index": 0,
      "bbox":       [186, 334, 930, 90]     // [x, y, w, h], top-left origin, page px
    },
    {
      "id":         "ab_uub03qhomsrm71en",
      "label":      "1b",
      "points":     2,
      "page_index": 1,
      "bbox":       [186, 124, 465, 130]    // 50% width -> w = 465; x still 186
    }
  ]
}
```

**Implied but not serialized anywhere** — the marker contract. Both sides
currently recompute it from shared constants rather than exchanging it, exactly
the "shared by convention, with no shared code and no validation" coupling that
`mobile_Extract/NOTES.md` §7.1 flagged. In the same canonical space:

```jsonc
{
  "aruco_dict": "DICT_4X4_50",
  "marker_size_px": 60,
  "marker_margin_px": 40,
  "marker_centres": { "0": [70, 70],  "1": [1170, 70],
                      "2": [70, 1684],"3": [1170, 1684] }   // TL, TR, BL, BR
}
```

**Values he is not recording that you would need him to start recording, and
where in his flow they would have to be captured:**

| Value | Where it must be captured |
|---|---|
| `model_answer` per answer box | **authoring** — a field on the `AnswerBoxNode` node view, alongside `label`/`points`; persisted through `_extractPayload` → `AnswerBoxIn` → a new `answer_boxes` column |
| `rubric` per answer box | same place, same path |
| `question_text` per answer box | **authoring** — either a new field, or a grouping node so the editor knows which prose belongs to which box (see gap 2) |
| `max_marks` per box / `total_marks` per sheet | `points` already exists per box and is already captured at authoring; it needs a **consumer** and a sheet-level sum |
| `page_count` in the response | **serialization** — one line in `_question_to_out`; the value is already stored at finalize |
| marker contract in the payload | **finalize** — `render_finalized_question` already computes it; emit it into the response instead of relying on both sides sharing constants |
| a student/copy identifier on the sheet | **finalize/print** — nothing in the current flow has any concept of a recipient |

---

## 8. Gap list

What must be added on the teacher side before a student app can extract answer
boxes from a photo of a printed sheet, and grade against them. **Ordered largest
first.**

**The good news up front:** for the narrow question of *extracting boxes from a
photo*, the teacher side is **already sufficient**. `GET /api/questions/{id}`
gives you `bbox` + `page_index` + `page_w_px`/`page_h_px`, the markers are drawn
at known positions, and I verified the full detect → homography → box-recovery
round-trip to **0.54 px** (§9C). Gaps 1–3 are about *grading* and *identity*, not
extraction. Gaps 4–6 are what actually get in the way of extraction.

### Large — new schema and new UI, not just endpoints

1. **Model answers and rubric do not exist at all.** No column, no schema field,
   no UI, no API. This is the biggest single gap: a new column pair on
   `answer_boxes`, a new control in the `AnswerBoxNode` node view, plumbing
   through `_extractPayload` → `AnswerBoxIn` → `_upsert_answer_boxes` →
   `AnswerBoxOut`. Without it there is nothing to grade against.

2. **No question ↔ answer-box association.** The document is a flat block list;
   an answer box carries only `order_index` and a free-text `label`. A crop
   arriving at your app cannot be told what it is an answer *to*, except by
   convention ("box 3 belongs to the third prompt"). Fixing it properly means a
   grouping node in the editor (a `question` node wrapping prose + its boxes) —
   an editor-schema change, a renderer change, and a persistence change. Fixing
   it cheaply means treating `label` as authoritative and requiring teachers to
   fill it in, which is a process fix, not a code fix.

3. **No identity of any kind: no auth, no users, no student, no per-copy
   marking.** Every printed sheet is byte-identical and carries nothing that
   names a student. Nothing on a returned photo can be traced to a person. Note
   the QR already reaches every box and is the natural carrier — but it must be
   fixed first (gap 6) and would need a per-copy payload rather than the current
   per-question one.

### Medium — gets in the way of extraction, but each is a contained fix

4. **`page_count` is never serialized** (§5.3). One omitted field in
   `_question_to_out`; the value is already in the DB. Until then a multi-page
   client must infer page count from `max(page_index)+1`, which undercounts a
   trailing page with no boxes. Also un-breaks his own page selector.

5. **`clone` is broken by the `answer_boxes` primary key** (§3.4, VERIFIED
   `IntegrityError`). Needs either a composite PK / surrogate key, or new box ids
   on clone (with a mapping so `content`'s node attrs are rewritten too — the
   ids are embedded in the TipTap JSON, so this is slightly more than a one-line
   fix). Until then, **a finalized worksheet can never be revised, and a deleted
   PDF can never be regenerated** — the 404 message's own advice is the broken
   path.

6. **The QR is undecodable as rendered** — 1.12 px/module (§1.5, VERIFIED
   arithmetic). Either raise `_QR_SIZE` from 46 to ~120 px (and widen
   `_QR_GUTTER`/`LEFT_MARGIN`, which shifts every `bbox_x` and so **invalidates
   the geometry of every already-finalized question**), or shrink the payload to
   short opaque codes. Only needed if you want per-box verification;
   **ArUco-only registration works fine without it**, and his own extractor
   already treats `qr_check: "absent"` as non-fatal.

### Smaller — correctness and fidelity

7. **The PDF is 1.56× A4** (§1.3, VERIFIED). `page.pdf(format="A4")` or emitting
   `pt` instead of CSS `px` would fix it. Affects printing ergonomics and marker
   size on paper, not coordinate math.

8. **Crops are axis-aligned bounding rects of a warped quad — no rectification.**
   `_crop_region` (`extractor.py:66`) takes `min`/`max` of the transformed
   corners; there is no `cv2.warpPerspective` on the crop path anywhere. At the
   mild skew I tested this pulls in **32% more area than the box** and keeps the
   perspective distortion (§9C). This is his extractor's problem, not yours — **if
   your student app does its own extraction, use `warpPerspective` onto the
   canonical `[w, h]` and you get a rectified, consistently-sized crop for free.**

9. **Non-atomic blocks are clipped at page boundaries** (§2.5). Only atomic
   blocks trigger a page break, and `.doc-page` is `overflow:hidden`, so a
   paragraph straddling the boundary is silently dropped from the PDF. Box
   geometry stays correct; the question text does not.

10. **Measure/print DOM mismatch** (§2.5) — the two passes build different
    wrappers, so printed `y` may drift from stored `bbox_y`. Unquantifiable
    without running Playwright. **Worth measuring empirically before you trust
    `bbox_y` to the pixel:** finalize one question, rasterize the PDF, and check
    where the dashed borders actually land.

11. **`points` has no consumer and there is no sheet total.** The field is
    already captured at authoring; it just needs to be summed and used.

12. **Three declared-but-never-written `Submission` columns**
    (`original_image_path`, `image_width`, `image_height`) — the original page
    image is saved to disk but its path never reaches the row, so a stored
    submission cannot be traced back to its source image except by
    reconstructing the `uploads/{q}/{s}/` path convention.

---

## 9. What I actually executed

`sqlalchemy`, `qrcode` and `playwright` are **not installed** in this
environment, so the app could not be booted and the PDF could not be rendered.
`cv2` 4.11.0, `numpy` 1.26.0 and `PIL` 10.4.0 are present, so I ran the geometry
directly, importing his real `config.py` and `doc_renderer.py` with `qrcode`
stubbed and `UPLOAD_DIR`/`PDF_DIR` redirected to scratch.

- **A. Page geometry** — computed the canvas and every margin from his constants.
  1240 × 1754 px; TOP/BOTTOM 124, LEFT 186, RIGHT 124; content 930; usable 1506.
  Implied paper 209.97 × 297.01 mm.
- **B. ArUco round-trip** — generated all four `DICT_4X4_50` markers at
  `MARKER_SIZE_PX`, placed them exactly as `doc_renderer` does, ran his
  `_detect_aruco_markers`. **All four detected, max error 0.707 px** (the
  systematic `s//2` half-pixel bias of §1.4).
- **C. Photo/homography path** — applied a plausible phone-photo perspective warp
  to that page, re-detected, ran his `_compute_transform` and `_transform_bbox`.
  `4/4` markers, `transform_type: "homography"`, **box corners recovered to
  0.540 px**. The axis-aligned crop his `_crop_region` would take was
  **1.320× the true quad area** — quantifying the missing rectification.
- **D. `_paginate`** — synthetic measured rects; correct page break before the
  straddling atomic block; produced `[186, 334, 930, 90]` and
  `[186, 124, 465, 90]`.
- **E. `answer_boxes` primary key** — mirrored the DDL in raw SQLite and
  performed the exact insert `clone_question` performs. **`IntegrityError:
  UNIQUE constraint failed: answer_boxes.id`.**
- **F/G. PDF physical size and QR module density** — arithmetic, reported in
  §1.3 and §1.5.
- Plus the §4.3 vocabulary grep across the whole tree.

**Never executed, by anyone, as far as this folder shows:** the FastAPI app, any
route, the SQLite database, the Playwright measure pass, the PDF render, PDF/TIFF
ingest, the tablet modality, and the entire React frontend (`node_modules` is
absent — VERIFIED).

---

## 10. Loose ends and things that look half-finished

- **`physical_page`** is stored, returned and displayed, but A4 is hardcoded in
  the renderer. Choosing "Letter" would change nothing (and no UI offers the
  choice).
- **`dpi`** is a column with no way to set it — `QuestionCreate` has no such
  field. Always 150.
- **`fabric` ^7.4.0** is a frontend dependency and is **imported nowhere**
  (VERIFIED by grep). It is a canvas library — most likely a leftover from, or a
  placeholder for, the unbuilt tablet-ink UI that `POST /api/submissions/tablet`
  expects.
- **The tablet route has no client.** `submitTablet` exists in `api.js:40` and is
  never called; no component imports it. `_extract_tablet` assumes ink strokes
  arrive in canonical canvas pixels and validates nothing.
- **`TabletStroke.points` is `list[list[float]]`** but `_extract_tablet` casts
  straight to `np.int32` with no bounds check — out-of-canvas coordinates are
  silently accepted by `cv2.polylines`.
- **`GET /api/submissions/{id}` has no response model** and returns the raw
  stored manifest, so its shape is whatever `extract_page` last produced.
- **`get_crop_image` takes no `part` parameter**, unlike the sibling
  `mobile_Extract` route. This codebase has no multi-part / segments concept at
  all — one box, one rect, one page.
- **`_pdf_to_images` catches only `ImportError`**; `pdf2image` also needs the
  **poppler** binary on PATH, an undeclared system dependency whose absence
  raises `PDFInfoNotInstalledError` → unhandled **500**.
- **`pyzbar` is in `requirements.txt`, is not installed here, and its
  `ImportError` is silently swallowed** (`extractor.py:93`) — the same
  optional-or-required ambiguity flagged in `mobile_Extract/NOTES.md` §4.2.
- **`_check_qr` searches an 80 px margin around the whole box** and accepts the
  first QR it decodes; with boxes stacked closely a neighbour's QR can be read
  and reported as `"fail"`. Moot while QRs are undecodable.
- **Errors are 200s.** Fewer than 4 markers returns HTTP **200** with
  `crops: []` and an `error` string in the body. Check `markers_detected`, not
  the status code.
- **`derived_from`** is written only by the broken clone, so in practice always
  NULL.

### Relationship to `mobile_Extract/` (observation, not a claim about intent)

`mobile_Extract/NOTES.md` §7.1 asked: *"What generates the worksheet pages?
Nothing here draws ArUco markers, renders QRs, or emits layout.json."*
**This folder is that generator** — or a close relative of it. VERIFIED:

- `get_marker_positions` is **byte-identical** in both
  (`doc_renderer.py:35` vs `mobile_Extract/marker_geometry.py:10`);
- identical defaults: `DICT_4X4_50`, `MARKER_SIZE_PX=60`, `MARKER_MARGIN_PX=40`;
- identical canvas: `mobile_Extract`'s `sample/layout.json` is `page_w_px: 1240,
  page_h_px: 1754`;
- identical answer-box id scheme: `ab_syzn1vsmmsrm6jat` in the sample matches
  `AnswerBoxNode.jsx`'s `'ab_' + Math.random().toString(36).slice(2,10) +
  Date.now().toString(36)` exactly;
- identical QR payload keys `{"q": …, "b": …}`.

But they are **not the same version.** The sample layout's boxes are
`bbox: [168, 600, 960, 250]` — x=168, w=960, implying `LEFT_MARGIN=168` and
`RIGHT_MARGIN=112`, whereas this code computes **186** and **124**. Working
backwards, the sample was produced when `_QR_SIZE` was 28 and the margin
constants added 12 rather than 24. `mobile_Extract` also has `segments`,
`qr_segments` and multi-part boxes, which have **no counterpart here**, and it
calls the container a `layout_id` (uuid) rather than a `question_id`.

So: same lineage, **diverged constants and diverged box schema**. If you build
against both, do not assume a layout from one is valid for the other — and in
particular, **do not hardcode x=168 or x=186**; read `bbox` from the API.
