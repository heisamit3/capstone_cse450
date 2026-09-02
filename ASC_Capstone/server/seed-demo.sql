-- =============================================================================
-- seed-demo.sql -- one complete, scannable assignment, with the teacher
-- worksheet system switched off.
--
--   psql "$DATABASE_URL" -f seed-demo.sql
--   locally: psql postgresql://capstone:capstone@127.0.0.1:5433/capstone -f seed-demo.sql
--
-- WHY THIS FILE EXISTS
--
-- POST /api/assignments/import is the only route that can give an assignment a
-- layout, an external_question_id or an external_answer_box_id, and it can only
-- do that by fetching TEACHER_API_BASE_URL. Nothing in either repo starts that
-- service. It also creates every Question with model_answer = '', and no route
-- in the API can ever fill that in -- so even a successful import produces an
-- assignment that WorksheetGrader refuses to grade (isGradeable == false) and
-- uploads as zero marks flagged for manual review.
--
-- This script writes, by hand, the row set that a successful import followed by
-- the teacher typing in the marking data would have produced.
--
-- WHERE THE GEOMETRY COMES FROM
--
-- page_w_px, page_h_px and every answer box (id, bbox, page_index, order_index)
-- are copied verbatim from the one ArUco fixture that exists in this project:
--
--   Capstone_Android/extractor/src/test/resources/sample/layout.json
--   Capstone_Android/extractor/src/test/resources/sample/sample_page.png
--
-- external_question_id is that fixture's own layout_id, so the seeded assignment
-- and the printed page are the same worksheet by identity, not by coincidence.
--
-- The four marker centres are NOT in the fixture json. They are computed here
-- the way SampleFixture.markerCentres and the server's
-- TEMPORARY_markerCentresFromTeacherConstants both compute them, from the same
-- three constants (DICT_4X4_50 / MARKER_SIZE_PX 60 / MARKER_MARGIN_PX 40) with
-- the same integer floor, m + s/2:
--
--   half   = 60 / 2         = 30
--   near   = 40 + 30        = 70
--   right  = 1240 - 40 - 30 = 1170
--   bottom = 1754 - 40 - 30 = 1684
--
--   0 = [  70,   70]   top-left
--   1 = [1170,   70]   top-right
--   2 = [  70, 1684]   BOTTOM-left     <- row-major, NOT clockwise
--   3 = [1170, 1684]   bottom-right
--
-- Swapping 2 and 3 into clockwise order still detects 4/4 markers and still
-- reports "homography" -- and displaces every crop by ~990 px (MarkerContractTest).
--
-- THE ONE FIELD THAT IS NOT VERBATIM: answer_boxes[].points
--
-- The fixture carries points = 1 on both boxes. This script uses 5, so that each
-- box is worth marking against a real rubric instead of a coin flip. points is
-- not geometry -- nothing in :app or :extractor reads LayoutAnswerBoxDto.points
-- at all -- and it is kept equal to questions.marks here so the layout and the
-- question rows cannot disagree. Everything registration or cropping touches is
-- untouched.
--
-- SHAPE: this JSON has to satisfy two readers written independently.
--
--   layouts.markers      written by teacher-worksheet.service.ts as MarkerContract:
--                        { aruco_dict, marker_size_px, marker_margin_px, centres,
--                          source }
--                        read by MarkerContractDto -- the same five keys, all
--                        non-null Kotlin types. centres values must be 2-element
--                        int arrays; AssignmentRepository.toExtractorLayout DROPS
--                        any centre that is not a pair, and LayoutValidator then
--                        refuses the layout by marker count rather than saying
--                        what was actually wrong.
--
--   layouts.answer_boxes written as ImportableBox[] = the teacher API box with
--                        bbox / page_index / order_index forced non-null:
--                        { id, label, points, bbox, page_index, order_index }
--                        read by LayoutAnswerBoxDto -- the same six keys, every
--                        one a non-null Kotlin type. Omitting label, points or
--                        order_index would leave a null in a non-null field and
--                        surface somewhere downstream of Gson, not at parse time.
--
--   The teacher API's extra keys (segments, qr_segments in the fixture) are
--   stripped by answerBoxSchema, a plain z.object. They are not written here
--   either, so this row is shaped exactly like a real import.
--
-- IDEMPOTENT
--
-- Every insert lands on a real unique index -- users_email_key,
-- assignments(teacher_id, external_question_id),
-- questions(assignment_id, external_answer_box_id), layouts(assignment_id) --
-- and does DO UPDATE, so re-running converges the rows rather than duplicating
-- them. Question ids are stable across runs, which matters: they are the ids the
-- phone posts back in the grade call.
-- =============================================================================

BEGIN;

DO $seed$
DECLARE
  -- The fixture's own layout_id. This is the join key to the printed page.
  c_external_question_id CONSTANT TEXT := '32824d98-aa41-43f9-8eef-4f4c2fb3b956';

  -- bcrypt cost 10, matching auth.routes.ts. Plaintext for both accounts: demo1234
  c_password_hash CONSTANT TEXT :=
    '$2b$10$eZOgxOKMKK5Lo84mRzykqexYhNTRjIPJdc7C5JWUnABPnQ0EMUyee';

  -- Verbatim from sample/layout.json.
  c_page_w      CONSTANT INT  := 1240;
  c_page_h      CONSTANT INT  := 1754;
  c_box_1_id    CONSTANT TEXT := 'ab_syzn1vsmmsrm6jat';
  c_box_2_id    CONSTANT TEXT := 'ab_uub03qhomsrm71en';

  -- Marks. Kept equal to answer_boxes[].points below; the assignment total is
  -- recomputed from the question rows at the end of this block regardless.
  c_box_1_marks CONSTANT INT  := 5;
  c_box_2_marks CONSTANT INT  := 5;

  v_teacher_id    INT;
  v_student_id    INT;
  v_assignment_id INT;
  v_total         INT;
BEGIN

  ---------------------------------------------------------------------------
  -- 1. Accounts. Seeded directly rather than through POST /api/auth/register
  --    so the script works with the server stopped, and so the student id is
  --    stable across runs.
  ---------------------------------------------------------------------------
  INSERT INTO users (email, password_hash, role, created_at, updated_at)
  VALUES ('teacher@demo.local', c_password_hash, 'teacher'::"Role", NOW(), NOW())
  ON CONFLICT (email) DO UPDATE
    SET password_hash = EXCLUDED.password_hash,
        role          = EXCLUDED.role,
        updated_at    = NOW()
  RETURNING id INTO v_teacher_id;

  INSERT INTO users (email, password_hash, role, created_at, updated_at)
  VALUES ('student@demo.local', c_password_hash, 'student'::"Role", NOW(), NOW())
  ON CONFLICT (email) DO UPDATE
    SET password_hash = EXCLUDED.password_hash,
        role          = EXCLUDED.role,
        updated_at    = NOW()
  RETURNING id INTO v_student_id;

  ---------------------------------------------------------------------------
  -- 2. The assignment. external_question_id is what makes
  --    AssignmentRepository build Assignment.layout at all -- it is populated
  --    only when externalQuestionId != null -- and what
  --    QuestionResolver.forAssignment scopes itself to.
  --
  --    total_marks is set here and re-asserted at step 5. It must equal the sum
  --    of the question marks, or POST /submissions/:id/grade answers 400 for a
  --    full-marks worksheet.
  ---------------------------------------------------------------------------
  INSERT INTO assignments (
    teacher_id, title, description, total_marks, external_question_id,
    created_at, updated_at
  )
  VALUES (
    v_teacher_id,
    'Demo worksheet: states of matter',
    'Seeded from the extractor sample page. Print or display sample_page.png, write an answer in each box, then scan it.',
    c_box_1_marks + c_box_2_marks,
    c_external_question_id,
    NOW(), NOW()
  )
  ON CONFLICT (teacher_id, external_question_id) DO UPDATE
    SET title       = EXCLUDED.title,
        description = EXCLUDED.description,
        updated_at  = NOW()
  RETURNING id INTO v_assignment_id;

  ---------------------------------------------------------------------------
  -- 3. One Question per answer box, joined by external_answer_box_id and
  --    scoped by assignment_id -- the pair, never the bare box id.
  --
  --    Inserted in served array order so ascending question id is the teacher's
  --    document order, which is what GET /assignments/:id reads back
  --    (orderBy: { id: "asc" }).
  --
  --    marks and a non-blank model_answer are both required for
  --    Question.isGradeable. Without them WorksheetGrader.gradeOne returns
  --    NeedsReview before it ever calls the model, and mergeBoxResults uploads
  --    0 marks / 0.0 confidence / needsManualReview -- a grading demo in which
  --    the model is never invoked.
  ---------------------------------------------------------------------------
  INSERT INTO questions (
    assignment_id, question_text, marks, question_type, model_answer, rubric,
    external_answer_box_id, created_at, updated_at
  )
  VALUES (
    v_assignment_id,
    'Name the three states of matter and give one everyday example of each.',
    c_box_1_marks,
    'short_answer'::"QuestionType",
    'Solid, liquid and gas. Examples: a solid such as ice or a brick; a liquid such as water or milk; a gas such as air, steam or oxygen.',
    '1 mark for each of the three states correctly named (3 marks). 1 mark for a correct example of a solid or a liquid, 1 mark for a correct example of a gas (2 marks). Do not penalise spelling. Award 0 if fewer than two states are named.',
    c_box_1_id,
    NOW(), NOW()
  )
  ON CONFLICT (assignment_id, external_answer_box_id) DO UPDATE
    SET question_text = EXCLUDED.question_text,
        marks         = EXCLUDED.marks,
        question_type = EXCLUDED.question_type,
        model_answer  = EXCLUDED.model_answer,
        rubric        = EXCLUDED.rubric,
        updated_at    = NOW();

  INSERT INTO questions (
    assignment_id, question_text, marks, question_type, model_answer, rubric,
    external_answer_box_id, created_at, updated_at
  )
  VALUES (
    v_assignment_id,
    'Describe what happens to the particles in a solid when it is heated until it melts.',
    c_box_2_marks,
    'short_answer'::"QuestionType",
    'Heating gives the particles more energy, so they vibrate faster and more strongly. The vibration becomes strong enough to overcome the forces holding the particles in fixed positions, so the regular arrangement breaks down. The particles can then move past one another and the solid becomes a liquid. They stay close together, so the substance does not expand much.',
    '1 mark: the particles gain energy from heating. 1 mark: they vibrate faster or more strongly. 1 mark: the forces holding them in fixed positions are overcome. 1 mark: the fixed regular arrangement breaks down and the particles can move past one another. 1 mark: the particles stay close together as a liquid. Accept equivalent wording; do not penalise spelling.',
    c_box_2_id,
    NOW(), NOW()
  )
  ON CONFLICT (assignment_id, external_answer_box_id) DO UPDATE
    SET question_text = EXCLUDED.question_text,
        marks         = EXCLUDED.marks,
        question_type = EXCLUDED.question_type,
        model_answer  = EXCLUDED.model_answer,
        rubric        = EXCLUDED.rubric,
        updated_at    = NOW();

  ---------------------------------------------------------------------------
  -- 4. The layout row. Both boxes are on page_index 0: ScanViewModel
  --    photographs one page and blocks any layout whose distinct page set is
  --    not exactly [0], before it even asks for a photo.
  ---------------------------------------------------------------------------
  INSERT INTO layouts (
    assignment_id, page_w_px, page_h_px, aruco_dict, markers, answer_boxes,
    layout_version, created_at, updated_at
  )
  VALUES (
    v_assignment_id,
    c_page_w,
    c_page_h,
    'DICT_4X4_50',
    jsonb_build_object(
      'aruco_dict',       'DICT_4X4_50',
      'marker_size_px',   60,
      'marker_margin_px', 40,
      -- Keys are marker ids AS STRINGS, which is how the import route stores
      -- them and how MarkerContractDto.centres is declared.
      'centres', jsonb_build_object(
        '0', jsonb_build_array(  70,   70),
        '1', jsonb_build_array(1170,   70),
        '2', jsonb_build_array(  70, 1684),
        '3', jsonb_build_array(1170, 1684)
      ),
      -- Honest about provenance: these came from constants, not from a served
      -- contract. Parsed by the app and, today, read by nothing.
      'source', 'computed_from_constants'
    ),
    -- Array order is reading order and is load bearing. LayoutValidator sorts by
    -- (page_index, bbox.y) and refuses the layout if that does not reproduce
    -- this array. 600 then 1282: it does.
    jsonb_build_array(
      jsonb_build_object(
        'id',          c_box_1_id,
        'label',       '',
        'points',      c_box_1_marks,
        'bbox',        jsonb_build_array(168,  600, 960, 250),
        'page_index',  0,
        'order_index', 0
      ),
      jsonb_build_object(
        'id',          c_box_2_id,
        'label',       '',
        'points',      c_box_2_marks,
        'bbox',        jsonb_build_array(168, 1282, 960, 290),
        'page_index',  0,
        'order_index', 1
      )
    ),
    1,
    NOW(), NOW()
  )
  ON CONFLICT (assignment_id) DO UPDATE
    SET page_w_px      = EXCLUDED.page_w_px,
        page_h_px      = EXCLUDED.page_h_px,
        aruco_dict     = EXCLUDED.aruco_dict,
        markers        = EXCLUDED.markers,
        answer_boxes   = EXCLUDED.answer_boxes,
        layout_version = EXCLUDED.layout_version,
        updated_at     = NOW();

  ---------------------------------------------------------------------------
  -- 5. Re-assert the invariant the grade upload depends on, from the rows that
  --    actually exist rather than from the constants above.
  ---------------------------------------------------------------------------
  SELECT COALESCE(SUM(marks), 0) INTO v_total
    FROM questions
   WHERE assignment_id = v_assignment_id;

  UPDATE assignments
     SET total_marks = v_total, updated_at = NOW()
   WHERE id = v_assignment_id;

  RAISE NOTICE 'seed-demo: teacher_id=% student_id=% assignment_id=% total_marks=%',
    v_teacher_id, v_student_id, v_assignment_id, v_total;

END
$seed$;

COMMIT;

-- -----------------------------------------------------------------------------
-- OPTIONAL, DESTRUCTIVE, DELIBERATELY NOT RUN.
--
-- Re-running this seed does not clear previous demo submissions, so after one
-- scan the assignment appears under HomeScreen's "Completed" tab rather than
-- "Pending". Uncomment to reset the demo student's work on this assignment
-- only. It deletes real rows; nothing above does. Answers and grades cascade.
--
-- DELETE FROM submissions
--  WHERE assignment_id = (SELECT id FROM assignments
--                          WHERE external_question_id = '32824d98-aa41-43f9-8eef-4f4c2fb3b956')
--    AND student_id    = (SELECT id FROM users WHERE email = 'student@demo.local');
-- -----------------------------------------------------------------------------

-- What was seeded, and the assignment id the curl sequence needs.
SELECT a.id                                AS assignment_id,
       a.total_marks,
       SUM(q.marks)                        AS sum_question_marks,
       COUNT(q.id)                         AS questions,
       jsonb_array_length(l.answer_boxes)  AS answer_boxes,
       (SELECT COUNT(*) FROM jsonb_object_keys(l.markers -> 'centres')) AS marker_centres,
       COUNT(*) FILTER (WHERE btrim(q.model_answer) = '')               AS questions_without_model_answer
  FROM assignments a
  JOIN questions   q ON q.assignment_id = a.id
  JOIN layouts     l ON l.assignment_id = a.id
 WHERE a.external_question_id = '32824d98-aa41-43f9-8eef-4f4c2fb3b956'
 GROUP BY a.id, a.total_marks, l.answer_boxes, l.markers;
