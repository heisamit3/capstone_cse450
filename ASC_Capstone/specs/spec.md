# Automatic Script Checking — Technical Overview

## 1. System Architecture

The system consists of three main components:

### 1. Teacher Web Application

**Stack:** React + Express + PostgreSQL

**Functions:**

* Teacher authentication
* Upload raw questions and answers
* Convert uploaded content into a structured schema
* Generate printable student PDFs
* Manage questions, rubrics, submissions, and grades

### 2. Backend API

**Stack:** Express + PostgreSQL

**Responsibilities:**

* Central source of truth for all data
* Authentication and role-based authorization
* Schema validation
* Submission management
* AI grading orchestration
* PDF generation and delivery

### 3. Android Application

**Purpose:**

* Student authentication
* View assigned questions
* Submit answers
* Display grades and feedback

**Grading Workflow:**

* Perform on-device AI grading using a lightweight language model.
* If the model's confidence is below a predefined threshold, the submission is sent to the backend for cloud-based grading.
* Final grades are synchronized with the backend.

**Implementation Options:**

* **Kotlin + Jetpack Compose (preferred):** Better support for on-device ML libraries.
* **React Native:** Reuses web development experience but still requires native integration for AI models.

---

## 2. Authentication & Security

* JWT-based authentication
* Two user roles: Teacher and Student
* Password hashing using bcrypt
* Role-based authorization
* Input validation using Zod
* Login rate limiting
* Environment-based secret management

---

## 3. PDF Generation

Teachers upload raw questions and answers, which are converted into a structured format. The system then generates printable PDFs using HTML templates and Puppeteer.

---

## 4. On-Device AI Grading

A lightweight language model performs grading directly on the student's device.

If the model produces a high-confidence result, the grade is returned immediately. Otherwise, the submission is forwarded to the backend for cloud-based grading.

Before implementation, a **feasibility study** should evaluate:

* Inference speed on target Android devices
* Grading accuracy
* Hardware requirements
* Frequency of cloud fallback

If the results are unsatisfactory, the grading strategy can be revised to a cloud-first approach.

---

## 5. Key Design Decisions

* Backend acts as the single source of truth.
* Local-first grading with cloud fallback.
* Native Android (Kotlin) is preferred for better AI integration, with React Native as an alternative.

---

## 6. Questions for Supervisor

1. Should grading work completely offline, or is cloud fallback acceptable?
2. Which Android framework is preferred: Kotlin or React Native?
3. Should teachers review AI-generated grades before they are finalized?
4. Can students self-register, or will teachers create student accounts?

