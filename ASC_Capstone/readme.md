# AI-Academic Evaluation Platform

A robust, asynchronous system for managing academic assignments, automated grading via LLMs, and performance tracking.

## Overview

This platform allows teachers to create assignments with custom grading rubrics, students to submit work, and an AI-driven pipeline to provide automated feedback and scoring.

## Core Features

* **AI-Driven Grading:** Multi-tier LLM failover system (Primary -> Secondary -> Tertiary) to ensure reliability.
* **Real-time Feedback:** Uses WebSockets to push status updates from the server to the student's browser.
* **Teacher Controls:** Customizable model selection per assignment and manual grade override capabilities.
* **Audit-Ready:** Maintains full history of both AI-generated grades and teacher manual overrides.
* **Security & Access:** Attribute-Based Access Control (ABAC) to ensure granular permission management.

## System Architecture

## Data Schema Highlights

* **Assignments:** Configurable rubrics and model selection.
* **Submissions:** Tracks lifecycle from `PENDING` to `GRADED` or `MANUAL_REVIEW_REQUIRED`.
* **Grades:** Distinct fields for AI-generated scores and manual teacher overrides.
* **UsageLogs:** Tracks token consumption for monthly billing per teacher.

## Security & Reliability

* **Authentication:** Integrated via third-party providers (e.g., Clerk/Auth0) to ensure secure identity management.
* **Failover Protocol:** If an LLM fails to output valid JSON, the system automatically tries the next configured model in the pipeline before flagging for manual review.
* **Separation of Concerns:** Grading logic, authentication, and route handling are strictly decoupled to maintain code maintainability.

## Technology Stack

* **Backend:** Node.js / Express
* **Database:** PostgreSQL
* **Real-time:** Socket.io
* **Auth:** Clerk / Auth0
* **Infrastructure:** Docker (for future sandbox execution)

## API Testing

For step-by-step endpoint testing (auth, assignments, submissions, grades), see [docs/api-testing.md](docs/api-testing.md).