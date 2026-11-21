# Contributing to Credit-Guard (ණය-රක්ෂක)

Thank you for your interest in contributing to **Credit-Guard**! Whether you are fixing a bug, adding a new feature (like WhatsApp/Telegram bots, OTP verification, or the Chrome extension), improving documentation, or reporting an issue, your help is welcome.

---

## Table of Contents
1. [Project Structure](#1-project-structure)
2. [Prerequisites](#2-prerequisites)
3. [Local Development Setup](#3-local-development-setup)
4. [Development Workflow](#4-development-workflow)
5. [Coding Standards & Guidelines](#5-coding-standards--guidelines)
6. [Security & Environment Variables](#6-security--environment-variables)
7. [Reporting Bugs & Suggesting Features](#7-reporting-bugs--suggesting-features)

---

## 1. Project Structure

```text
credit-guard/
├── docker-compose.yml      # Optional MySQL 8 container setup
├── backend/                # Spring Boot REST API (Java 17+, Maven, Port 8080)
└── frontend/               # React + Vite SPA (Port 5173)
```

---

## 2. Prerequisites

Before setting up the project locally, ensure you have the following installed:
- **Java**: JDK 17 or higher
- **Maven**: 3.9+
- **Node.js**: v18+ and `npm`
- **Database**: MySQL 8.0+ (local installation) or **Docker** (`docker compose`)
- **Google Gemini API Key**: A free key from [Google AI Studio](https://aistudio.google.com/apikey)

---

## 3. Local Development Setup

### Step 1: Clone & Fork
1. Fork the repository to your GitHub account.
2. Clone your fork locally:
   ```bash
   git clone https://github.com/<your-username>/credit-guard.git
   cd credit-guard
   ```

### Step 2: Start MySQL
- **Using Docker (Recommended):**
  ```bash
  docker compose up -d
  ```
- **Using Local MySQL:**
  Ensure MySQL is running on port `3306` and update `DB_USERNAME` and `DB_PASSWORD` in `backend/.env`. The `creditguard` database and schema will be generated automatically on startup.

### Step 3: Configure Environment Variables
1. In `backend/.env`, set your Gemini API key and database credentials:
   ```env
   GEMINI_API_KEY=your_gemini_api_key_here
   ```
2. In `frontend/.env`, verify the backend API URL:
   ```env
   VITE_API_URL=http://localhost:8080
   ```

### Step 4: Run the Application
Run the backend and frontend in two separate terminals:

**Terminal 1 – Backend:**
```bash
cd backend
mvn spring-boot:run
```

**Terminal 2 – Frontend:**
```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173` in your browser to verify everything works.

---

## 4. Development Workflow

1. **Create a Branch**: Always create a new branch from `main` for your work.
   - Feature branches: `feat/short-description` (e.g., `feat/whatsapp-bot-notifications`)
   - Bugfix branches: `fix/short-description` (e.g., `fix/utr-duplicate-validation`)
   - Docs branches: `docs/short-description` (e.g., `docs/api-examples`)
   ```bash
   git checkout -b feat/your-feature-name
   ```

2. **Make & Test Your Changes**:
   - Test both backend endpoints and frontend flows thoroughly.
   - Verify end-to-end flows (Vendor KYB -> Invoice Upload -> Gemini Extraction -> Buyer Confirm/Dispute -> Bank Slip Settlement) when modifying core logic.

3. **Commit Messages**: Use clear, descriptive commit messages following [Conventional Commits](https://www.conventionalcommits.org/):
   - `feat: add OTP verification for cash settlements`
   - `fix: handle Gemini 429 rate limit gracefully`
   - `docs: update setup instructions in README`
   - `refactor: simplify buyer risk score calculation`

4. **Open a Pull Request (PR)**:
   - Push your branch to your fork:
     ```bash
     git push origin feat/your-feature-name
     ```
   - Open a Pull Request against the `main` branch.
   - Clearly describe **what** changed, **why** it changed, and **how to test** it (include screenshots if UI changes were made).

---

## 5. Coding Standards & Guidelines

### Backend (Spring Boot / Java)
- Keep controllers thin; place business rules, score calculations, and AI verification checks inside service classes.
- Ensure proper HTTP status codes and clear error messages are returned from REST endpoints.
- Maintain privacy boundaries: buyer risk checks (`/api/risk/{vendorId}`) must **never** expose seller identities or confidential invoice parties.

### Frontend (React + Vite)
- Keep components modular, clean, and easy to read.
- Handle loading and error states clearly (especially during multipart file uploads and Gemini AI processing).
- Use `VITE_API_URL` from environment configuration rather than hardcoding `http://localhost:8080`.

---

## 6. Security & Environment Variables

- **Never commit real API keys or secrets.** Double-check that your `GEMINI_API_KEY`, database passwords, or custom `ADMIN_KEY` are not staged in git before committing.
- If you add a new environment variable to `backend/.env` or `frontend/.env`, document it in `README.md` with a safe placeholder value.

---

## 7. Reporting Bugs & Suggesting Features

- **Bug Reports**: Open an issue with steps to reproduce, expected behavior, actual behavior, and relevant logs/screenshots (e.g., Spring Boot stack trace or browser console error).
- **Feature Ideas**: Some planned roadmap items include:
  - Chrome extension for quick buyer risk lookups
  - Telegram / WhatsApp bot notifications for invoice confirmation
  - 2FA / OTP authentication and cash-payment OTP verification
  Feel free to open an issue to discuss design details before starting work on a large feature!
