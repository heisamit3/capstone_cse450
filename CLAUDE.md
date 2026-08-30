# Capstone: On-Device Worksheet Grading — state of the code

> Factual record of both repos, produced by reading every source file and by
> executing the builds, the test suite and the server on **2026-08-21**.
> It describes what the code is, not what it should become. If something here is
> not true of the code, the file is wrong and should be re-audited.
> Claude Code must not rewrite this file spontaneously; only on explicit request.

Every claim is tagged with one of three states:

| State | Meaning |
|---|---|
| **VERIFIED** | Compiled, passed a test, or was executed and observed during this audit |
| **UNVERIFIED** | Code is written and compiles, but this behaviour has never been executed |
| **NOT PRESENT** | Referenced by another part of the system, absent from the codebase |

---

# 0. Repo layout

One parent folder, two independent git repos:

```
capstone app/                  not itself a git repo
├── Capstone_Android/          git root — Kotlin + Jetpack Compose app
│                              branch feature/on-device-grading, uncommitted changes
├── ASC_Capstone/              git root
│   ├── server/                Node + Express + Prisma + PostgreSQL
│   │                          branch feature/local-grade-endpoint, uncommitted changes
│   ├── api_doc.md
│   ├── readme.md
│   └── specs/
├── LLaVA-OneVision-0.5B.litertlm      829,262,144 bytes
├── gemma3-1b-it-int4.litertlm         584,417,280 bytes
└── .gitignore                         contains only `*.litertlm`
```

Both `.litertlm` byte counts match `ModelSpec.expectedBytes` exactly (VERIFIED —
`ls -l`).

Android git state: 18 modified files, untracked `data/local/{LocalGradingService,
LocalModelProvider,ModelSpec}.kt`, `domain/grading/`, `util/`,
`ui/screens/ModelTest{Screen,ViewModel}.kt`, and both new test directories.
Single commit on the branch: `8281d9f Initial commit`.

Server git state (repo root is `ASC_Capstone/`, not `ASC_Capstone/server/`):
5 modified files — `server/src/routes/assignment.routes.ts`,
`server/src/routes/submission.routes.ts`,
`server/src/schemas/submission.schemas.ts`,
`server/src/services/grading.service.ts` and `ASC_Capstone/.gitignore`.
HEAD is `3bcf9cf before first eval`.

---

# 1. Status summary

## VERIFIED

**Server — the whole HTTP + persistence layer was executed during this audit.**

- `npx tsc --noEmit` → exit 0, no errors.
- PostgreSQL reachable at `127.0.0.1:5433` (Docker container `capstone-db`,
  image `postgres:16`, published `0.0.0.0:5433->5432/tcp`). The container is not
  defined by any file in either repo.
- `npx prisma migrate status` → `2 migrations found`, `Database schema is up to
  date!`
- `npm run dev` boots and listens on port 3000.
- All 14 routes exercised with curl. Observed: health 200; missing bearer 401;
  unknown route 404; Zod rejection 400 with an `issues[]` array; teacher and
  student register 201; assignment create 201; student `GET /assignments/:id`
  returns `model_answer` and `rubric`; student `GET /assignments` returns only
  `id` + `question_text` per question plus `submission_status`; multipart
  submission 201 with `answers[]` and a populated `answer_image_path`;
  `POST /submissions/:id/grade` **201 on first call, 200 on the second with the
  same grade row id**; `obtained_marks` over `total_marks` 400; a teacher token on
  a student-only route 403; a student token on a teacher-only route 403;
  `answer_text` observed updated to the posted `transcription`;
  `PATCH /grades/:id/override` sets `graded_by=teacher_override` and
  `confidence=null`. All audit rows were deleted afterwards; the uploads folder
  and row counts were left as found.

**Android — compiles and its unit tests pass.**

- `./gradlew compileDebugKotlin` → BUILD SUCCESSFUL.
- `./gradlew cleanTestDebugUnitTest testDebugUnitTest` → BUILD SUCCESSFUL,
  **38 tests, 0 failures, 0 errors** (fresh run, XML regenerated this audit).
- Because compilation succeeds, every LiteRT-LM signature the app calls exists in
  `litertlm-android:0.16.1` (see §7).

## UNVERIFIED

- **Everything requiring the physical device.** Engine construction and
  `initialize()`, LiteRT-LM inference, the GPU vision backend, handwriting
  transcription quality, latency, and whether the app uid can read
  `/data/local/tmp/llm/`. There is no run log, benchmark, or recorded output in
  either repo. None of it can be executed from this machine.
- **The Android app against the server.** Nothing in the repo records the app
  having completed a login, an assignment fetch, or a submission upload.
- `ExampleInstrumentedTest` (1 test) — requires a device or emulator, never run.
- `LocalGradingService.grade()` end to end. Its parsing half is covered by tests;
  the inference half has never executed.
- Every screen and ViewModel — no UI or instrumentation tests exist.

## NOT PRESENT

- **`POST submissions/{id}/grade` client method.** The server route is complete
  and exercised; `ApiService` has no method for it, and no Kotlin code posts a
  grade.
- **`GET grades/me` client method.** `AssignmentRepository.getMyGrades()` calls
  `getMySubmissions()` and reads `SubmissionDto.grades` instead.
- **Any caller of `GradingService.grade()` outside `ModelTestViewModel`.**
- **Any caller of `ImagePrep.toGradingPng()` outside `ModelTestViewModel`** and
  its tests.
- **Per-submission aggregation.** No code sums per-question marks, minimises
  confidence, joins feedback, or flags a submission for manual review.
- **Server tests.** `package.json` `"test": "echo \"Error: no test specified\" &&
  exit 1"`. No test files, no test framework in `devDependencies`.
- **`marks`, `model_answer`, `rubric` on `QuestionDto`** — the server sends them
  on the student detail route; the DTO has no fields for them (§4).
- **A `grade` field on the server's Grade row** — `GradeDto` declares
  `grade: String`; the server sends `obtained_marks: Int` (§4).
- **A gallery picker in `ScanScreen`** — camera only. `ModelTestScreen` has one.
- **Any code producing `graded_by = "frontier_api"`.** The Prisma enum and the
  Zod schema accept the value; nothing writes it.
- **`docker-compose.yml`** — absent from both repos.
- **Any guard preventing an image being sent to a text-only model** (§6.3).

---

# 2. Server — `ASC_Capstone/server`

## 2.1 Stack (from `package.json`)

Dependencies: `@prisma/client` ^6.10.1, `bcrypt` ^6.0.0, `cors` ^2.8.6,
`dotenv` ^17.4.2, `express` ^5.2.1, `express-rate-limit` ^8.5.2, `helmet`
^8.3.0, `jsonwebtoken` ^9.0.3, `multer` ^2.2.0, `zod` ^4.4.3.

Dev: `prisma` ^6.10.1, `ts-node-dev` ^2.0.0, `typescript` ^5.6.3 (resolved
5.9.3 at runtime), plus `@types/*`.

Scripts: `dev` = `ts-node-dev --respawn --transpile-only src/index.ts`,
`build` = `tsc`, `start` = `node dist/index.js`, `test` = a failing stub.

`tsconfig.json`: target ES2020, commonjs, `strict: true`, `rootDir: src`,
`outDir: dist`.

## 2.2 Environment (`src/config/env.ts`)

Parsed with Zod at import time; `process.exit(1)` on failure. `.env` exists and
is gitignored; all seven variables are set.

| Var | Schema |
|---|---|
| `NODE_ENV` | enum development/test/production, default `development` |
| `PORT` | coerced positive int, default 3000 |
| `DATABASE_URL` | string min 1, **required** |
| `JWT_SECRET` | string min 16, **required** |
| `JWT_EXPIRES_IN` | string, default `"1d"` |
| `CONFIDENCE_THRESHOLD` | coerced number 0..1, default 0.8 |
| `STUDENT_SELF_REGISTER` | optional string, transformed to `value !== "false"` |

`CONFIDENCE_THRESHOLD` is read in exactly one place: `grading.service.ts`, which
nothing imports (§2.8).

## 2.3 Wiring (`src/app.ts`)

```
helmet() → cors() → express.json({ limit: "2mb" })
         → static /uploads from <cwd>/uploads
         → /api              healthRouter
         → /api/auth         authRouter
         → /api/assignments  assignmentRouter
         → /api/submissions  submissionRouter
         → /api/grades       gradeRouter
         → notFoundHandler → errorHandler
```

`src/index.ts` awaits `prisma.$connect()` before `app.listen`, and exits 1 if
the connection throws.

**Auth** (`middleware/auth.ts`): `authenticate` requires
`Authorization: Bearer <jwt>`, verifies it, and sets
`req.user = { id: Number(payload.sub), role: payload.role }`. Failure → 401.
`authorize(...roles)` → 401 when `req.user` is absent, 403 on a role mismatch.
`assignmentRouter`, `submissionRouter` and `gradeRouter` each call
`.use(authenticate)` globally.

**Validation** (`middleware/validate.ts`): `validate(schema)` calls
`schema.parse({ body, params, query })` and lets the `ZodError` propagate.

**Errors** (`middleware/error-handler.ts`): `HttpError` → its own status;
`ZodError` → 400 `{ error: "Validation failed", issues }`; anything with
`code === "P2002"` → 409; otherwise `console.error` and 500.

**JWT** (`utils/jwt.ts`): payload is `{ sub: String(userId), role }`, signed with
`env.JWT_SECRET` and `expiresIn: env.JWT_EXPIRES_IN`.

## 2.4 Route inventory

All 14 routes were called during this audit.

| Method | Path | Auth | Role | Zod schema | Notes |
|---|---|---|---|---|---|
| GET | `/api/health` | none | — | none | `{ status: "ok" }` |
| POST | `/api/auth/register` | none | — | `registerSchema` | 403 for `student` when `STUDENT_SELF_REGISTER=false`; bcrypt cost 10; 201 |
| POST | `/api/auth/login` | none | — | `loginSchema` | rate limited 10 / 15 min / IP; identical "Invalid credentials" for unknown email and wrong password |
| POST | `/api/assignments` | JWT | teacher | `createAssignmentSchema` | nested `questions: { create }`; 201 |
| GET | `/api/assignments` | JWT | both | `studentAssignmentQuerySchema` | branches on role |
| GET | `/api/assignments/:id` | JWT | both | `assignmentIdParamSchema` | branches on role |
| POST | `/api/submissions` | JWT | student | `createSubmissionSchema` | `multipart/form-data`, `upload.any()` |
| GET | `/api/submissions/me` | JWT | student | none | newest first, derived `status` |
| GET | `/api/submissions/me/:id` | JWT | student | none (manual `Number.isInteger` → 400) | ownership in the where-clause |
| GET | `/api/submissions/assignment/:assignmentId` | JWT | teacher | none | 404 unknown, 403 not owner |
| POST | `/api/submissions/:id/grade` | JWT | student | `createSubmissionGradeSchema` | §2.5 |
| GET | `/api/grades/me` | JWT | student | none | includes `assignment` and `submission` |
| GET | `/api/grades/assignment/:assignmentId` | JWT | teacher | none | 404 unknown, 403 not owner |
| PATCH | `/api/grades/:id/override` | JWT | teacher | `overrideGradeSchema` | sets `graded_by=teacher_override`, `confidence=null` |

The four routes with no Zod schema pass `Number(req.params.…)` straight to
Prisma without an integer check.

### Role branching on the assignment routes

`GET /api/assignments` — student branch selects per question **only**
`{ id, question_text }`, plus `submissions: { where: { student_id } }` reduced to
`submission_status` = `"completed"` when at least one submission exists,
otherwise `"pending"`; filtered in JS by `?status=pending|completed`. Teacher
branch returns `where: { teacher_id }` with the full `questions` relation.

`GET /api/assignments/:id` — student branch selects
`{ id, title, description, total_marks, created_at, updated_at, questions: { id,
question_text, marks, model_answer, rubric } }`. **`model_answer` and `rubric`
are exposed to the student here on purpose** (comment at
`assignment.routes.ts:174-178`: the Android client grades on-device and needs
something to grade against). Teacher branch includes `questions` and a teacher
summary, and enforces ownership with a 403.

### `POST /api/submissions`

Reads `assignment_id`, an `answers` JSON string (parsed in-handler when it
arrives as a string), and one file per question named `image_<question_id>`.
`multer.diskStorage` writes to `<cwd>/uploads/answers` with a
`${Date.now()}-${random}${ext}` filename; the `fileFilter` rejects any mimetype
not starting with `image/` (400).

Validation order: assignment exists (404) → every `question_id` belongs to it
(400) → no duplicate `question_id` (400) → every image fieldname parses to a
positive int (400) → no two images for one question (400) → every answer has an
image (400).

One `prisma.$transaction` creates the Submission with nested Answers, each
carrying `answer_image_path = /uploads/answers/<filename>`. **No grade row is
created.** Responds 201 with the submission including `answers[]`.

Line 117 calls `validate(createSubmissionSchema)(req, res, () => undefined)` and
discards the result; the `createSubmissionSchema.shape.body.parse` two lines
later is what actually validates.

## 2.5 `POST /api/submissions/:id/grade`

`submission.routes.ts:412-549`. Student-only. Records what the phone reports;
the server grades nothing here.

Request body (`schemas/submission.schemas.ts:30-41`):

```jsonc
{
  "obtained_marks": 7,           // int >= 0, required
  "feedback": "…",               // string, required (not optional)
  "confidence": 0.82,            // number 0..1, required
  "graded_by": "local_model",    // "local_model" | "frontier_api", default "local_model"
  "answers": [                   // optional, defaults to []
    { "question_id": 41, "transcription": "…" }   // transcription optional, default ""
  ]
}
```

`graded_by` does not accept `teacher_override`; that value is only ever written
by `PATCH /api/grades/:id/override`.

Validation order: submission exists (404) → `submission.student_id === req.user.id`
(403, checked after existence so the two are distinguishable) →
`obtained_marks <= assignment.total_marks` (400) → every `question_id` belongs to
this assignment (400) → no duplicate `question_id` (400) → every `question_id`
already has an Answer row on this submission (400).

Persistence, one `prisma.$transaction`: each listed Answer's `answer_text` is
updated to its `transcription`, then `grade.findFirst({ where: { submission_id } })`
decides between `update` and `create`. `Grade` has **no unique constraint on
`submission_id`**, so upsert is unavailable and the find-then-update is what keeps
repeat calls from leaving two grade rows. Response: the Grade row, **201 when
created, 200 when updated** (both observed).

## 2.6 Prisma schema (`prisma/schema.prisma`)

```prisma
enum Role          { teacher, student }
enum QuestionType  { mcq, short_answer, essay }
enum GradingSource { local_model, frontier_api, teacher_override }

model User {            // @@map("users")
  id Int @id @default(autoincrement())
  email String @unique @db.VarChar(255)
  password_hash String @db.Text
  role Role
  created_at DateTime @default(now())
  updated_at DateTime @updatedAt
  created_assignments Assignment[]
  submissions Submission[]
  grades Grade[]
}

model Assignment {      // @@map("assignments")
  id Int @id @default(autoincrement())
  teacher_id Int
  title String @db.VarChar(255)
  description String? @db.Text
  total_marks Int @db.SmallInt
  created_at DateTime @default(now())
  updated_at DateTime @updatedAt
  teacher User @relation(fields: [teacher_id], references: [id], onDelete: Cascade)
  questions Question[]
  grades Grade[]
  submissions Submission[]
}

model Question {        // @@map("questions")
  id Int @id @default(autoincrement())
  assignment_id Int
  question_text String @db.Text
  marks Int @db.SmallInt
  question_type QuestionType
  model_answer String @db.Text     // NOT NULL
  rubric String? @db.Text          // nullable
  created_at DateTime @default(now())
  updated_at DateTime @updatedAt
  assignment Assignment @relation(..., onDelete: Cascade)
  answers Answer[]
}

model Submission {      // @@map("submissions")
  id Int @id @default(autoincrement())
  assignment_id Int
  student_id Int
  submitted_at DateTime @default(now())
  updated_at DateTime @updatedAt
  assignment Assignment @relation(..., onDelete: Cascade)
  student User @relation(..., onDelete: Cascade)
  answers Answer[]
  grades Grade[]
}

model Answer {          // @@map("answers")
  id Int @id @default(autoincrement())
  submission_id Int
  question_id Int
  answer_text String @db.Text      // NOT NULL, "" allowed
  answer_image_path String? @db.Text
  created_at DateTime @default(now())
  updated_at DateTime @updatedAt
  submission Submission @relation(..., onDelete: Cascade)
  question Question @relation(..., onDelete: Cascade)
}

model Grade {           // @@map("grades")
  id Int @id @default(autoincrement())
  assignment_id Int
  submission_id Int                // NO unique constraint
  student_id Int
  obtained_marks Int @db.SmallInt
  feedback String? @db.Text
  confidence Float?                // nullable: teacher override has none
  graded_by GradingSource
  graded_at DateTime @default(now())
  updated_at DateTime @updatedAt
  assignment Assignment @relation(..., onDelete: Cascade)
  submission Submission @relation(..., onDelete: Cascade)
  student User @relation(..., onDelete: Cascade)
}
```

Constraints that shape the rest of the system:

- **`Grade` is per-submission, not per-question.** There is no `question_id` on
  it, and only one `obtained_marks`, one `feedback` and one `confidence` per
  submission. Per-question detail can only live in `Answer.answer_text`.
- **`Grade.submission_id` is not unique**, which is why the grade route does
  find-then-update rather than upsert. Nothing in the database prevents a second
  grade row for one submission; only that handler does.
- **`Grade.confidence` is nullable** and `PATCH …/override` is the only writer of
  `null`.
- **`Question.model_answer` is NOT NULL, `rubric` is nullable.**
- Every relation is `onDelete: Cascade`; deleting a User removes their
  assignments, submissions, answers and grades (confirmed during audit cleanup).
- The only unique index in the database is `users_email_key`.

## 2.7 Migrations

1. `20260719193603_idk/migration.sql` — 117 lines: three enums, six tables, the
   `users_email_key` unique index, nine foreign keys, all `ON DELETE CASCADE`.
2. `20260818000000_add_answer_image_path/migration.sql` —
   `ALTER TABLE "answers" ADD COLUMN "answer_image_path" TEXT;`

`migration_lock.toml` → `provider = "postgresql"`.
**Applied: VERIFIED** — `prisma migrate status` reports the schema up to date.

## 2.8 Dead and deliberately retained code

- **`src/services/grading.service.ts`** — 72 lines, exports `gradeSubmission()`,
  a bag-of-words token-overlap scorer that reads `env.CONFIDENCE_THRESHOLD` and
  picks `local_model` vs `frontier_api` from it. **Unreferenced** —
  `grep -rn "services/\|gradeSubmission" src --include=*.ts` returns only its own
  definition. Its file header states why it is kept: a planned server-side
  grading fallback and a lexical-overlap baseline for evaluation. It is the only
  reader of `CONFIDENCE_THRESHOLD`, which is why that variable is still in
  `env.ts` despite no route using it.
- **`submission.routes.ts:117`** — the discarded `validate(...)` call described
  in §2.4.
- `src/types/express.d.ts` declares `Request.files?: Multer.File[]`; the
  submission handler casts `req.files` to `Express.Multer.File[]` anyway.

---

# 3. Android — `Capstone_Android`

## 3.1 Build configuration

`app/build.gradle.kts`: `namespace com.example.capstone`, `compileSdk 37`,
`minSdk 26`, `targetSdk 35`, Java 17. `buildFeatures { compose = true;
buildConfig = true }`.

`buildConfigField("String", "BASE_URL", …)` is defined for both build types and
is `"http://localhost:3000/api/"` in each; the release entry is commented as a
placeholder because no server is deployed. `AppContainer` reads
`BuildConfig.BASE_URL`; no URL is hardcoded in Kotlin.

`testOptions.unitTests`: `isIncludeAndroidResources = true` (Robolectric),
`isReturnDefaultValues = true` (so `android.util.Log` no-ops in JVM tests).

Versions (`gradle/libs.versions.toml`): AGP 9.2.1, Kotlin 2.1.0, Compose BOM
2026.02.01, Retrofit 2.11.0, OkHttp logging 4.12.0, DataStore 1.1.1,
navigation-compose 2.8.5, Coil 2.6.0, CameraX 1.4.0, **litertlm 0.16.1**,
exifinterface 1.4.2, Truth 1.4.5, Robolectric 4.16.1, JUnit 4.13.2.

`AndroidManifest.xml`: permissions `INTERNET` and `CAMERA`; `uses-feature
android.hardware.camera required="false"`; inside `<application>`, two
`<uses-native-library>` entries — `libvndksupport.so` and `libOpenCL.so`, both
`required="false"`, commented as required by the LiteRT-LM GPU (OpenCL) vision
backend. `network_security_config.xml` sets `cleartextTrafficPermitted="true"`
for the base config.

## 3.2 Source inventory (line counts)

```
CapstoneApplication.kt                     14
MainActivity.kt                           128
data/local/LocalGradingService.kt         246   untracked
data/local/LocalModelProvider.kt          322   untracked
data/local/ModelSpec.kt                    91   untracked
data/local/TokenManager.kt                 33
data/remote/ApiService.kt                  33
data/remote/AssignmentModels.kt            46   modified
data/remote/AuthModels.kt                  31   modified
data/remote/GradeModels.kt                 13
data/repository/AssignmentRepository.kt   119
data/repository/AuthRepository.kt         100   modified
di/AppContainer.kt                        104   modified
domain/grading/GradingService.kt           59   untracked
domain/model/Assignment.kt                 15   modified
domain/model/Grade.kt                       9
domain/model/Submission.kt                  8
ui/screens/AssignmentDetailScreen.kt      105   modified
ui/screens/AssignmentDetailViewModel.kt    61
ui/screens/HomeScreen.kt                  119   modified
ui/screens/HomeViewModel.kt                74   modified
ui/screens/LoginScreen.kt                  83   modified
ui/screens/LoginViewModel.kt               64   modified
ui/screens/ModelTestScreen.kt             213   untracked
ui/screens/ModelTestViewModel.kt          277   untracked
ui/screens/RegisterScreen.kt               93   modified
ui/screens/RegisterViewModel.kt            94   modified
ui/screens/ResultScreen.kt                 86
ui/screens/ResultViewModel.kt              50
ui/screens/ScanScreen.kt                  201
ui/screens/ScanViewModel.kt                29
ui/screens/SubmitScreen.kt                100
ui/screens/SubmitViewModel.kt              60
ui/theme/{Color,Theme,Type}.kt      10 / 57 / 33
util/ImagePrep.kt                         200   untracked

test/…/data/local/GradeParsingTest.kt     222   untracked
test/…/util/ImagePrepTest.kt              121   untracked
test/…/util/ExifOrientationTest.kt         97   untracked
test/…/ExampleUnitTest.kt                  16
androidTest/…/ExampleInstrumentedTest.kt   23
```

## 3.3 Dependency graph (`di/AppContainer.kt`)

`CapstoneApplication.onCreate` builds one `DefaultAppContainer`. It exposes
`authRepository`, `assignmentRepository`, `tokenManager`, `localModelProvider`
and `gradingService`, all `by lazy`.

OkHttp carries an `HttpLoggingInterceptor(Level.BODY)` and an auth interceptor
that reads the token from DataStore with `runBlocking { tokenManager.token
.firstOrNull() }` and adds `Authorization: Bearer <token>` when non-null.
Timeouts: connect 10s, read 60s, write 60s. Retrofit uses
`GsonConverterFactory`.

`localModelProvider` is on the interface with a comment saying it is exposed
only so `ModelTestScreen` can probe the engine directly. `gradingService` is
declared as the `GradingService` interface and constructed as
`LocalGradingService(localModelProvider)`.

## 3.4 Navigation (`MainActivity.kt`)

```
login → register
login → home            (popUpTo login inclusive)
home  → assignment_detail/{assignmentId} → scan/{assignmentId} → submit/{assignmentId}
home  → results
home  → model_test      (marked TEMPORARY in both the route and the button)
```

`scan → submit` passes the photo as a `ByteArray` through
`savedStateHandle["photo_bytes"]`: written on `currentBackStackEntry`, read from
`previousBackStackEntry`, then assigned onto the `SubmitViewModel` via `.apply`.

`model_test` is reachable only from the `HomeScreen` TopAppBar action labelled
"Model"; both that action and the route carry `// TEMPORARY … Remove with
ModelTestScreen`.

## 3.5 Network layer

`ApiService` declares **7 methods against the server's 14 routes**:

```
POST auth/login            @Body Map<String,String>   → Response<AuthResponse>
POST auth/register         @Body Map<String,String>   → Response<AuthResponse>
GET  assignments           ?status=                   → List<AssignmentDto>
GET  assignments/{id}                                 → AssignmentDto
GET  submissions/me                                   → List<SubmissionDto>
GET  submissions/me/{id}                              → SubmissionDto
POST submissions           @Multipart PartMap + Parts → Response<SubmissionDto>
```

DTOs:

- `QuestionDto` — `id`, `questionText`. Nothing else.
- `AssignmentDto` — `id`, `title`, `description: String?` (with a comment
  explaining Gson writes null into non-null Kotlin fields), `totalMarks`,
  `questions: List<QuestionDto>?`, `submissionStatus: String?`.
- `SubmissionAnswerDto` — `id`, `questionId`, `answerText`,
  `answerImagePath: String` (non-null against a nullable column).
- `SubmissionDto` — `id`, `assignmentId`, `studentId`, `submittedAt`,
  `answers?`, `grades?`, `status?`.
- `GradeDto` — `id`, `submissionId`, `assignmentId`, `grade: String`,
  `feedback: String`.
- `AuthResponse`, `UserDto`, `ErrorResponse`, and `ServerError` /
  `ServerErrorIssue` — the latter pair models both server error shapes with every
  field nullable.

`AuthRepository` saves the token on success and, on failure, builds a message
through `describeFailure()`: always names the HTTP status, flattens Zod
`issues[]` into `path: message` pairs, falls back to the raw body truncated at
300 chars. `register()` hardcodes `"role" to "student"`.

`AssignmentRepository`:

- `getAssignments()` / `getAssignmentDetail(id)` map each question to
  `Question(q.id, q.questionText)` — `marks`, `model_answer` and `rubric` are
  dropped at both sites, `getAssignmentDetail` included.
- `getMySubmissions()` maps to `Submission(id, assignmentId, status ?: "pending",
  submittedAt)`.
- `getMyGrades()` calls `getMySubmissions()` and flattens `dto.grades`.
- `submitAssignment(assignmentId, questionImages: Map<Int, Uri>)` is a stub:
  `return Result.failure(Exception("Not implemented for Uri"))` under a
  `// ... (existing implementation)` comment. No callers.
- `submitAssignmentWithBytes(assignmentId, questionIds, imageBytes)` builds the
  multipart body, returns `Result<Unit>` and **discards the `SubmissionDto`
  body, including the submission id**. `answer_text` is hardcoded `""` for every
  question; one part per question id is built from the **same** `imageBytes`,
  declared `"image/jpeg"` with filename `"capture.jpg"`. `ImagePrep` is not
  applied on this path.

Domain models: `Question(id, text)`, `Assignment(id, title, description: String?,
questions, isCompleted)`, `Submission(id, assignmentId, status, submittedAt)`,
`Grade(id, submissionId, assignmentId, grade: String, feedback: String)`.

## 3.6 Screens and ViewModels

- **Login / Register** — both run navigation inside `LaunchedEffect(uiState)`,
  with a comment noting that calling it from the composition body fires on every
  recomposition.
- **Home** — two tabs (Pending / Completed) computed in `HomeViewModel` by
  intersecting assignment ids with submission ids from two parallel `async`
  calls; the error state names the underlying exception message.
- **AssignmentDetail** — renders title, `description.orEmpty()`, and a
  `LazyColumn` of `question.text`. `QuestionItem` contains a stray
  `PaddingValues(16.dp)` expression statement whose value is discarded.
- **Scan** — CameraX only (`PreviewView` + `ImageCapture`, `DEFAULT_BACK_CAMERA`),
  writes to `cacheDir/capture-<millis>.jpg` and reads the raw bytes back.
  `ImageCapture.OnImageSavedCallback.onError` is `= Unit`, so a failed capture is
  silently ignored. Requests `CAMERA` at runtime; there is no gallery path.
- **Submit** — shows the decoded photo and one "Upload and Submit" button, which
  calls `viewModel.submit(questions.map { it.id })`. It reaches its own
  `AssignmentDetailViewModel` for the question list.
  `SubmitScreen.kt:95-97` calls `onSubmissionSuccess()` from the `Column` body
  when the state is `Success`, i.e. during composition rather than from a
  `LaunchedEffect` — the one screen where that pattern remains.
- **Result** — lists `Grade` cards rendering `"Grade: ${grade.grade}"` and
  `grade.feedback`.

Nothing in any of these screens references `GradingService`, `ImagePrep`,
`LocalModelProvider` or a grade upload.

## 3.7 Grading interface and implementation

`domain/grading/GradingService.kt`:

```kotlin
interface GradingService {
    suspend fun grade(
        photoPng: ByteArray,
        questionText: String,
        modelAnswer: String,
        rubric: String?,
        maxMarks: Int
    ): GradeResult                    // documented as never throwing

    suspend fun gradeRaw(             // debug: no extraction, no parsing, no trimming
        photoPng: ByteArray,
        questionText: String,
        modelAnswer: String,
        maxMarks: Int
    ): String
}

data class GradeResult(
    val transcription: String,
    val legible: Boolean,
    val marks: Int,                   // documented as within 0..maxMarks
    val certainty: Double,            // 0.0..1.0
    val feedback: String,
    val needsManualReview: Boolean    // when true, `marks` carries no meaning
)
```

**One implementation exists: `data/local/LocalGradingService.kt`.** There is no
cloud implementation, no fake, and no test double.

- Sampler: `SamplerConfig(topK = TOP_K 5, topP = TOP_P 0.95, temperature =
  TEMPERATURE 0.1)`.
- A **fresh `Conversation` per inference**, closed via `use`, so no context
  carries between questions.
- Retry policy: exactly two attempts — the base prompt, then the base prompt plus
  `RETRY_REMINDER`. Inference exceptions are logged and the loop continues;
  `CancellationException` is rethrown. After both attempts fail →
  `manualReviewResult()`.
- `runPrompt` always sends `Contents.of(Content.ImageBytes(photoPng),
  Content.Text(prompt))` — image first, text last, with a comment attributing the
  ordering requirement to Gemma 3n. The image is attached unconditionally,
  independent of `spec.supportsVision`.

The prompt built by `buildPrompt()` (the rubric line is omitted when the rubric
is null or blank):

```
You are marking one handwritten answer from a photo of a student worksheet.

Question: $questionText
Correct answer: $modelAnswer
Marking rubric: $rubric          ← only when rubric is not null/blank
Maximum marks: $maxMarks

Do this:
1. Read the handwriting in the image and transcribe the student's answer exactly as written.
2. If the handwriting is unclear, cut off, or you are not certain what it says, set "legible" to false and do NOT guess. Never invent an answer you cannot actually read.
3. Compare the transcription with the correct answer and award a whole number of marks from 0 to $maxMarks.
4. Write one short sentence of feedback addressed to the student.
5. Set "certainty" to how confident you are in this mark, from 0.0 to 1.0.

Reply with ONLY this JSON object. No preamble, no explanation, no markdown code fences:
{"transcription":"...","legible":true,"marks":0,"certainty":0.0,"feedback":"..."}
```

`RETRY_REMINDER`:

```
Your previous reply could not be parsed. Reply with the single JSON object and nothing else. It must start with { and end with }.
```

**JSON extraction** — `extractFirstJsonObject(raw)`: a hand-written brace-balance
scan from the first `{`, tracking `inString` and backslash escapes, returning the
first balanced object. Not a regex, so it survives markdown fences, preambles,
trailing commentary and braces inside string values.

**Parsing** — `parseGrade(raw, maxMarks)`: Gson into a private `GradeDto` whose
fields are all nullable. Returns `null` (→ retry, then manual review) when there
is no JSON object, when Gson throws, or when `marks` is absent — a missing mark
is treated as a parse failure, never a silent zero. `marks` is `Double?` because
models emit `2.0` and `"2"`. Then `marks.roundToInt().coerceIn(0, maxMarks)`,
`certainty.coerceIn(0.0, 1.0)`, `legible = dto.legible ?: false`,
`needsManualReview = !legible`.

`manualReviewResult()` returns `marks = 0` with a comment stating the zero is a
placeholder for a non-null Int, not a grade the model gave;
`needsManualReview` is the signal.

Both `parseGrade` and `manualReviewResult` are `internal` on the companion
object, which is how `GradeParsingTest` reaches them without an engine.

## 3.8 Model registry — `data/local/ModelSpec.kt`

An enum with two entries and no other source of model configuration:

```kotlin
LLAVA_OV_05B(
    fileName       = "LLaVA-OneVision-0.5B.litertlm",
    displayName    = "LLaVA-OneVision 0.5B (multimodal, small)",
    supportsVision = true,
    minRamGb       = 4,
    maxNumTokens   = 2048,        // commented: this model spends 730 tokens per image
    expectedBytes  = 829_262_144L
),
GEMMA3_1B(                        // documented as a text-only fallback for isolating
    fileName       = "gemma3-1b-it-int4.litertlm",   // engine faults from vision faults
    displayName    = "Gemma 3 1B (text only)",
    supportsVision = false,
    minRamGb       = 6,
    maxNumTokens   = 1024,
    expectedBytes  = 584_417_280L
);
```

- `MIN_VISION_TOKENS = 1500`; `hasTightVisionBudget = supportsVision &&
  maxNumTokens < MIN_VISION_TOKENS`. The companion `init` block logs a warning
  for every entry that trips it. **Neither current entry does** — LLaVA has 2048
  tokens, and Gemma3-1B is not a vision model.
- `minRamGb` is documented as advisory; nothing reads it except the debug
  screen's status dump.
- `DEFAULT = LLAVA_OV_05B`, mirrored by `LocalModelProvider.DEFAULT_SPEC`.
- `expectedBytes` is documented as guarding against a truncated `adb push`,
  because native LiteRT-LM tends to abort rather than throw on one.

## 3.9 Engine lifecycle — `data/local/LocalModelProvider.kt`

- `MODEL_DIR = "/data/local/tmp/llm"`, a companion constant and the single source
  of truth for `modelFile()`, `isModelPresent()` and `modelSizeBytes()`. Its
  KDoc states it is a DEV-ONLY path: outside the app sandbox, shared with other
  apps' debug tooling, and chosen because Android 11+ scoped storage blocks the
  adb shell user from writing into the app's external files dir.
- Exactly **one `Engine` per process**, created lazily by `initialize()` under
  `initMutex` with a double-check inside the lock. Construction runs on
  `Dispatchers.IO`. A failed `initialize()` calls `close()` on the half-built
  engine before rethrowing.
- `EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(),
  visionBackend = Backend.GPU(), maxNumTokens = spec.maxNumTokens, cacheDir =
  context.cacheDir.path)`.
- `createEngine()` runs two `check()`s **before** touching native code:
  `status.usable`, then `status.sizeMatchesExpected`.
- `withInference { }` serializes every decode behind `inferenceMutex` on
  `Dispatchers.IO`.
- `release()` runs under `NonCancellable`, takes `initMutex` then
  `inferenceMutex`, nulls the field before closing, and is idempotent.
- `useSpec(newSpec)` returns early when unchanged, otherwise releases first and
  then swaps the spec; the replacement engine is built lazily on the next
  `initialize()`.
- `runRawPrompt(prompt, imagePng?)` is a debug helper documented for
  `ModelTestScreen`. It inlines the sampler values `5 / 0.95 / 0.1` as literals
  rather than sharing `LocalGradingService`'s constants, and attaches the image
  only when one is passed.
- `Message.textOrEmpty()` — an `internal` extension that flattens `Content.Text`
  parts with no trimming.

`ModelFileStatus` records `path`, `expectedBytes`, `parentExists`,
`parentTraversable`, `exists`, `isFile`, `readable` and `sizeBytes` as separate
facts, with `usable`, `sizeMatchesExpected` and a `diagnosis` string that names
the most likely cause and the command that addresses it. The separation is
documented as necessary because `File.isFile` returns false both for an absent
file and for one the app's uid cannot stat. Logging: `I/LocalModelProvider: model
OK: …` or `W/LocalModelProvider: model NOT usable: <status> -- <diagnosis>`.

## 3.10 Image preprocessing — `util/ImagePrep.kt`

`object ImagePrep`, `MAX_LONG_EDGE = 1024`.
`toGradingPng(source: ByteArray, maxLongEdge: Int = MAX_LONG_EDGE): ByteArray?`:

1. Pass 1 — `inJustDecodeBounds = true`; returns null when the bounds come back
   non-positive.
2. Pass 2 — decode with `inSampleSize` (`calculateInSampleSize` picks the largest
   power of two keeping the long edge ≥ target) and
   `inPreferredConfig = ARGB_8888`.
3. EXIF orientation applied **before** resizing, so the resize sees the true
   edges.
4. `scaleToLongEdge` — exact downscale to the long edge, aspect preserved,
   **never upscales**.
5. `Bitmap.CompressFormat.PNG`. The header comment states PNG rather than JPEG
   because JPEG ringing destroys thin pen strokes.

Returns `null` rather than throwing for empty input, undecodable data, a failed
compress, or `OutOfMemoryError` (caught at three separate points; the rotate and
scale helpers fall back to the untransformed bitmap rather than failing).
Intermediate bitmaps are recycled.

`orientationTransform(Int): OrientationTransform?` is an `internal` pure lookup
over the eight EXIF tags, split out so it can be tested without a graphics
stack; `ORIENTATION_NORMAL`, `ORIENTATION_UNDEFINED` and anything unexpected map
to `null`. `exifMatrix` applies rotation first, then flips.

## 3.11 `ModelTestScreen` / `ModelTestViewModel` — debug harness

Both files open with a comment marking them temporary and not part of the
student flow.

Model picker: one `FilterChip` per `ModelSpec.entries`; selecting one calls
`useSpec`, which releases the engine.

One picker button — "Pick image from gallery" / "Pick a different image", using
`ActivityResultContracts.PickVisualMedia` (no storage permission). The picked
bytes go through `ImagePrep.toGradingPng` before anything else sees them.

Six probes, in the order laid out on screen:

| | left | right |
|---|---|---|
| row 1 | Check model file | Load engine |
| row 2 | Text only test | Describe image |
| row 3 | Transcribe handwriting | Grade it |

- "Check model file" dumps every `ModelFileStatus` field plus the diagnosis, off
  the main thread.
- "Load engine" calls `initialize()`.
- "Text only test" sends `"Reply with exactly the two characters: OK"` with no
  image.
- "Describe image" uses `DESCRIBE_PROMPT = "Describe what you see in this
  image."`.
- "Transcribe handwriting" is a **pure OCR probe**: it calls
  `LocalModelProvider.runRawPrompt` with `TRANSCRIBE_PROMPT` and does not go
  through `GradingService`. Its KDoc explains why — every grading prompt contains
  the expected answer and the marks available, so a model handed the answer key
  can reproduce it without reading a pen stroke, making the result worthless as
  transcription evidence.
- "Grade it" is the only probe that calls `gradingService.grade()`, and the only
  one that deliberately shows the model the expected answer. Its output is a
  harness-formatted summary of the parsed `GradeResult`.

Test constants, used only by "Grade it": `TEST_QUESTION = "What is 7 x 8?"`,
`TEST_MODEL_ANSWER = "56"`, `TEST_MAX_MARKS = 5`.

Output pane: `rawOutput` is `String?`, where null means no probe has completed —
kept distinct from a probe that returned `""`. `outputSource` is an
`OutputSource` enum (`NONE` / `MODEL` / `HARNESS` / `ERROR`) and the heading
names the source: `MODEL OUTPUT - verbatim, N chars` / `HARNESS OUTPUT -
composed by the app, not the model` / `HARNESS ERROR - stack trace` / `Output -
nothing run yet`. An empty model response gets an explicit error-coloured line.
The pane applies no `ifBlank`, no fallback text and no trimming. Alongside it:
a status line, `elapsed: N ms`, and a `LinearProgressIndicator` while busy.
`launchOperation` ignores taps while another probe runs and routes any
`Throwable` into the pane as a stack trace.

## 3.12 Test coverage — 38 unit tests, all passing (VERIFIED)

| Suite | Tests | Kind | Covers |
|---|---:|---|---|
| `data/local/GradeParsingTest` | 19 | plain JVM | `LocalGradingService.parseGrade` + `manualReviewResult` |
| `util/ImagePrepTest` | 7 | Robolectric, `@Config(sdk=[34])`, `@GraphicsMode(NATIVE)` | `ImagePrep.toGradingPng` |
| `util/ExifOrientationTest` | 11 | plain JVM | `ImagePrep.orientationTransform` |
| `ExampleUnitTest` | 1 | plain JVM | `2 + 2 == 4` placeholder |
| **total** | **38** | | 0 failures, 0 errors |

`GradeParsingTest` cases: clean JSON; markdown fences; chatty preamble; trailing
commentary; two objects (first wins); braces inside string values; truncated
JSON; malformed JSON; missing `marks` field yields no grade rather than a silent
zero; empty/whitespace/prose response; the unparseable fallback flagged for
manual review with no marks; marks above max clamped; negative marks clamped to
zero; fractional marks rounded then clamped; certainty above 1 and below 0
clamped; illegible answer flagged; illegible-with-confident-marks still flagged;
missing `legible` field flagged.

`ImagePrepTest` cases: 4000×3000 → 1024 long edge; aspect ratio preserved;
800×600 left alone (never upscaled); portrait 3000×4000 keeps its long edge on
the height; no output edge exceeds the limit across a set of sizes; output is
valid PNG bytes; undecodable input returns null instead of throwing. Robolectric
runs with native graphics so the bytes are produced by real Skia encode/decode.

`ExifOrientationTest` pins all eight defined EXIF tags individually, plus
normal, undefined, out-of-range, and a property test that every defined tag maps
to a quarter turn.

`androidTest/ExampleInstrumentedTest` — 1 test, requires a device, **never run**.

## 3.13 Dead and deliberately retained code

- **`GradingService.gradeRaw` has no callers.** It is declared on the interface
  and implemented in `LocalGradingService`; nothing invokes it. (The debug probe
  that used to call it now uses `runRawPrompt` with a grading-free prompt —
  see §3.11.)
- **`AssignmentRepository.submitAssignment(Int, Map<Int, Uri>)`** — a stub
  returning `Result.failure`, no callers.
- **`ModelTestScreen` / `ModelTestViewModel` and the `model_test` route** —
  explicitly marked temporary in four places (both file headers, the HomeScreen
  button, the NavHost route).
- `ModelTestViewModel` imports `LocalModelProvider` and talks to it directly,
  which its KDoc calls a deliberate exception for a throwaway screen.
- `AppContainer.localModelProvider` is on the interface with a comment stating it
  is exposed only for that screen and that production code must not use it.
- `QuestionItem` in `AssignmentDetailScreen` evaluates and discards a
  `PaddingValues(16.dp)`.
- `ModelTestViewModel` declares `OutputSource.NONE` for the "nothing run yet"
  state, which is set both initially and at the start of each probe.

---

# 4. Client ↔ server contract, as coded

Divergences between what the server sends and what the client declares. These
are statements about current behaviour, not a defect list.

| Server sends | Client declares | Consequence in the code as written |
|---|---|---|
| `questions[].marks`, `model_answer`, `rubric` on student `GET /assignments/:id` | `QuestionDto` has only `id`, `questionText` | Gson discards the three fields; `AssignmentRepository` maps to `Question(id, text)`. `GradingService.grade()` requires `modelAnswer` and `maxMarks`, which never reach the app. |
| Grade row with `obtained_marks: Int`, `confidence`, `graded_by` | `GradeDto` declares `grade: String`, plus `feedback` only | No `grade` key exists in the response, so Gson leaves the field null; `ResultScreen` renders `"Grade: ${grade.grade}"`. `confidence` and `graded_by` are not modelled. |
| `description: string \| null` | `AssignmentDto.description: String?`, `domain.Assignment.description: String?`, `.orEmpty()` at both render sites | Handled. |
| `answer_image_path: string \| null` | `SubmissionAnswerDto.answerImagePath: String` (non-null) | Gson can write null into the non-null field. |
| 201 with the submission id and `answers[]` | `submitAssignmentWithBytes` returns `Result<Unit>` | The submission id — the value `POST /submissions/:id/grade` is keyed on — is discarded at the point of receipt. |
| expects one image per `question_id` | one captured photo is duplicated into one part per question id, `answer_text` hardcoded `""`, mimetype `"image/jpeg"`, filename `"capture.jpg"`, `ImagePrep` not applied | The server stores the same full-size camera JPEG under every question. |

The multipart shape the client actually sends:

```
assignment_id : 12
answers       : [{"question_id":41,"answer_text":""},{"question_id":42,"answer_text":""}]
image_41      : <same bytes>
image_42      : <same bytes>
```

---

# 5. LiteRT-LM API surface actually called

`com.google.ai.edge.litertlm:litertlm-android:0.16.1`. Every signature below is
called from `LocalModelProvider` or `LocalGradingService` and therefore exists —
`compileDebugKotlin` succeeds against the real artifact (VERIFIED).

```kotlin
Backend.CPU()                                    // no-arg form used
Backend.GPU()                                    // no-arg

EngineConfig(                                    // named arguments used
    modelPath     = …,
    backend       = …,
    visionBackend = …,
    maxNumTokens  = …,
    cacheDir      = …
)

Engine(config)          : AutoCloseable
Engine.initialize()                              // blocking; called on Dispatchers.IO
Engine.createConversation(ConversationConfig)
Engine.close()

SamplerConfig(topK = Int, topP = Double, temperature = Double)
ConversationConfig(samplerConfig = …)            // only this parameter is passed

Contents.of(vararg Content)
Content.Text(text: String)
Content.ImageBytes(bytes: ByteArray)

Conversation.sendMessage(contents: Contents): Message   // blocking
Conversation.close()                                    // via `use`

Message.contents.contents : List<Content>
```

Not called anywhere in the app: `sendMessageAsync` in either form, `Session` /
`SessionConfig`, `ResponseFormat`, `ThinkingConfig`, `LoraConfig`, tool calling,
`Engine.isInitialized()`, `getBenchmarkInfo()`, `getTokenCount()`,
`cancelProcess()`, `Backend.NPU`, `Backend.GOOGLE_TENSOR`, `Content.ImageFile`,
`Content.AudioBytes`, `Content.AudioFile`, `audioBackend`, `maxNumImages`.

---

# 6. Where the on-device path stops

Stated as reachability, not as a plan.

- `LocalModelProvider`, `LocalGradingService`, `ModelSpec`, `ImagePrep` and
  `GradingService` are constructed by `AppContainer` and consumed by exactly one
  screen: `ModelTestScreen`, reachable only from the temporary "Model" button on
  `HomeScreen`.
- The student flow — `home → assignment_detail → scan → submit` — touches none of
  them. `SubmitViewModel.submit()` uploads the photo and sets `Success`.
- `POST /api/submissions/:id/grade` is complete and works (VERIFIED by curl), and
  has no client.
- `Grade` carries one mark, one feedback string and one confidence per
  submission, with no `question_id`; nothing in the app computes those from
  per-question results.
