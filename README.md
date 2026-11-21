# Credit-Guard (ණය-රක්ෂක) – simple version

React (frontend) + Spring Boot (backend) + MySQL + Google Gemini API.

```
credit-guard/
├── docker-compose.yml      (optional MySQL)
├── backend/                (Spring Boot, port 8080)   <- settings in backend/.env
└── frontend/               (React + Vite, port 5173)  <- settings in frontend/.env
```

## What is included (from your document)
| Document part | In this project |
|---|---|
| Ch.1 KYB registration + approval | Register page -> Admin approves -> Vendor ID (V0001...) + login token. Admin can **Revoke** (token dies at once) |
| Ch.2 Credit bill + 3 layers | SHA-256 duplicate check -> Gemini reads bill (seller, buyer, amount, date, seal) -> status `PENDING_CONFIRMATION` (score unchanged) -> buyer **Confirm / Dispute** -> `ACTIVE_DEBT` |
| Ch.3 Default + warning | Nightly job (01:00) + admin "Run overdue check" button: past-due -> `OVERDUE`, buyer score -> 30. **Check a buyer's risk** page: score + Gemini advice, no seller names |
| Ch.4 Settlement | Buyer uploads bank slip -> Gemini extracts UTR/amount/account/edit-signs -> 4 checks (account matches seller, UTR unused, not edited, amount covers) -> `SETTLED_AI_VERIFIED` |
| Ch.5 Admin portal | Approvals, Revoke, Dispute desk, Community risk dashboard |

**Not included (kept simple):** Chrome extension, Telegram/WhatsApp bot, OTP/2FA, cash-payment OTP. Bot messages are replaced by the web pages (Confirm/Dispute buttons). These can be added later.

## 1. Requirements
Java 17+, Maven 3.9+, Node 18+, MySQL 8 (or Docker).

## 2. MySQL
Option A – Docker (easiest): `docker compose up -d` in the project root  
Option B – local MySQL: just make sure user `root` / password `root123` works (or change `DB_USERNAME` / `DB_PASSWORD` in `backend/.env`). The database `creditguard` and all tables are created automatically.

## 3. Gemini API key  (you must create this yourself – it is free)
1. Open https://aistudio.google.com/apikey and sign in with a Google account
2. Click **Create API key**, copy it
3. Paste it in `backend/.env` as `GEMINI_API_KEY=...`
Never share this key or upload `.env` to GitHub.

## 4. Variables

### backend/.env
| Variable | Value now | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/creditguard?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` | MySQL connection |
| `DB_USERNAME` | `root` | MySQL user |
| `DB_PASSWORD` | `root123` | MySQL password (same as docker-compose) |
| `GEMINI_API_KEY` | `PASTE_YOUR_GEMINI_API_KEY_HERE` | **put your key here** |
| `GEMINI_MODEL` | `gemini-3.5-flash-lite` | any Gemini model that accepts images/PDF, e.g. `gemini-3.8-flash` for better reading |
| `ADMIN_KEY` | `admin123` | password for the Admin portal – change it |
| `SERVER_PORT` | `8080` | backend port |
| `CORS_ORIGIN` | `http://localhost:5173` | React address allowed to call the API |

### frontend/.env
| Variable | Value | Meaning |
|---|---|---|
| `VITE_API_URL` | `http://localhost:8080` | backend address |

## 5. Run
```bash
# terminal 1 – backend
cd backend
mvn spring-boot:run

# terminal 2 – frontend
cd frontend
npm install
npm run dev
```
Open http://localhost:5173

## 6. Demo walk-through (Kasun / Saman)
1. **Register** two vendors (Kasun Traders, Saman Hardware) – use real-looking bank account numbers.
2. Click **Admin portal** (top right) -> key `admin123` -> **Approve** both.
3. Login as Kasun -> **Add credit invoice** -> buyer Saman, upload a sample invoice (PDF/photo) -> Gemini reads it.
4. Login as Saman (use another browser/incognito) -> **Confirm** the invoice -> `ACTIVE_DEBT`.
5. Admin -> **Run overdue check now** (set credit days = 1 and wait a day, or edit `due_date` in MySQL to a past date first) -> Saman's score drops to 30.
6. Login as a 3rd vendor (Nimal) -> **Check a buyer's risk** -> Saman -> HIGH RISK + advice, no seller name shown.
7. Saman -> upload a bank slip on the overdue invoice -> 4 checks -> `SETTLED_AI_VERIFIED`. For the slip test, the slip's beneficiary account must match Kasun's registered account.
8. Try a **Dispute** (instead of Confirm) -> appears in Admin dispute desk.

## 7. API list
Vendor calls need `Authorization: Bearer <token>` (token comes from login). Admin calls need `X-Admin-Key: <ADMIN_KEY>`.

| Method | URL | Who |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | public |
| GET | `/api/auth/me`, `/api/vendors`, `/api/invoices` | vendor |
| POST | `/api/invoices` (multipart: `buyerId`, `creditDays`, `file`) | seller |
| POST | `/api/invoices/{id}/confirm`, `/dispute` | buyer |
| POST | `/api/invoices/{id}/settle` (multipart: `slip`) | buyer |
| GET | `/api/risk/{vendorId}?amount=500000` | vendor |
| GET | `/api/admin/vendors`, `/disputes`, `/dashboard` | admin |
| POST | `/api/admin/vendors/{id}/approve`, `/revoke` | admin |
| POST | `/api/admin/invoices/{id}/resolve?decision=UPHOLD\|CANCEL` | admin |
| POST | `/api/admin/run-overdue-check` | admin |

## 8. Troubleshooting
- `GEMINI_API_KEY is not set` -> edit `backend/.env`, restart backend.
- `Gemini API error 404` -> model name changed; set another one in `GEMINI_MODEL`.
- `Gemini API error 429` -> free quota used; wait a minute.
- `Access denied for user 'root'` -> `DB_PASSWORD` does not match your MySQL.
- Login says "waiting for admin approval" -> approve it in the Admin portal.

## 9. Contributing
Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details on setting up the project and submitting pull requests.

## 10. License
This project is licensed under the **MIT License** – see the [LICENSE](LICENSE) file for details.
