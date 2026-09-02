# Student API Endpoints

Base path: `/api`

All protected endpoints require header:

- `Authorization: Bearer <token>`

## 1) Student Registration

- Method: `POST`
- Path: `/api/auth/register`
- Auth required: `No`
- Request body (JSON):

```json
{
  "email": "student@example.com",
  "password": "strongpassword",
  "role": "student"
}
```

- Response `201`:

```json
{
  "user": {
    "id": 1,
    "email": "student@example.com",
    "role": "student"
  },
  "token": "<jwt>"
}
```

## 2) Student Login

- Method: `POST`
- Path: `/api/auth/login`
- Auth required: `No`
- Request body (JSON):

```json
{
  "email": "student@example.com",
  "password": "strongpassword"
}
```

- Response `200`:

```json
{
  "user": {
    "id": 1,
    "email": "student@example.com",
    "role": "student"
  },
  "token": "<jwt>"
}
```

## 3) Student Assignment List (with pending/completed)

- Method: `GET`
- Path: `/api/assignments`
- Auth required: `Yes (student token)`
- Query params:
  - `status=pending` or `status=completed` (optional)

- Response `200` (array):

```json
[
  {
    "id": 10,
    "title": "Assignment 1",
    "description": "...",
    "total_marks": 100,
    "created_at": "2026-08-18T10:00:00.000Z",
    "updated_at": "2026-08-18T10:00:00.000Z",
    "questions": [
      {
        "id": 101,
        "question_text": "Explain ..."
      }
    ],
    "submission_status": "pending"
  }
]
```

`submission_status` is derived from whether a submission exists for `student_id + assignment_id`.

## 4) Student Single Assignment (question_text only)

- Method: `GET`
- Path: `/api/assignments/:id`
- Auth required: `Yes (student token)`
- Request body: `None`

- Response `200`:

```json
{
  "id": 10,
  "title": "Assignment 1",
  "description": "...",
  "total_marks": 100,
  "created_at": "2026-08-18T10:00:00.000Z",
  "updated_at": "2026-08-18T10:00:00.000Z",
  "questions": [
    {
      "id": 101,
      "question_text": "Explain ..."
    }
  ]
}
```

No `model_answer` or `rubric` is returned to student.

## 5) Student Submit Answers with Images

- Method: `POST`
- Path: `/api/submissions`
- Auth required: `Yes (student token)`
- Content type: `multipart/form-data`

Send fields as:

- `assignment_id`: number (form field)
- `answers`: JSON string of array, each item:
  - `question_id`: number
  - `answer_text`: string (can be empty)
- For each question image, send one file field named exactly:
  - `image_<question_id>`

Example:

- `assignment_id = 10`
- `answers = [{"question_id":101,"answer_text":""},{"question_id":102,"answer_text":""}]`
- `image_101 = <file>`
- `image_102 = <file>`

Rules:

- One image per question in `answers` is required.
- Only image MIME types are accepted.
- Duplicate answers for the same question are rejected.

- Response `201`:

```json
{
  "id": 55,
  "assignment_id": 10,
  "student_id": 1,
  "submitted_at": "2026-08-18T10:10:00.000Z",
  "updated_at": "2026-08-18T10:10:00.000Z",
  "answers": [
    {
      "id": 201,
      "submission_id": 55,
      "question_id": 101,
      "answer_text": "",
      "answer_image_path": "/uploads/answers/1723983012345-123456789.jpg",
      "created_at": "2026-08-18T10:10:00.000Z",
      "updated_at": "2026-08-18T10:10:00.000Z"
    }
  ]
}
```

Important: Submission creation does not create a grade.

## 6) Student Get One Own Submission

- Method: `GET`
- Path: `/api/submissions/me/:id`
- Auth required: `Yes (student token)`
- Request body: `None`

- Response `200`:

```json
{
  "id": 55,
  "assignment_id": 10,
  "student_id": 1,
  "submitted_at": "2026-08-18T10:10:00.000Z",
  "updated_at": "2026-08-18T10:10:00.000Z",
  "assignment": {
    "id": 10,
    "title": "Assignment 1",
    "description": "...",
    "total_marks": 100
  },
  "answers": [
    {
      "id": 201,
      "submission_id": 55,
      "question_id": 101,
      "answer_text": "",
      "answer_image_path": "/uploads/answers/1723983012345-123456789.jpg",
      "created_at": "2026-08-18T10:10:00.000Z",
      "updated_at": "2026-08-18T10:10:00.000Z"
    }
  ],
  "grades": [],
  "status": "pending"
}
```

`status` is derived from grades:

- `pending` when no grade row exists
- `completed` when at least one grade row exists

## 7) Student Get Own Submission List

- Method: `GET`
- Path: `/api/submissions/me`
- Auth required: `Yes (student token)`
- Request body: `None`

- Response `200`: array of submission objects, each includes `assignment`, `answers`, `grades`, and derived `status`.
