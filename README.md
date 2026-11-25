# Branch.
**Micro-finance loan & EMI tracker.** A self-hosted website for micro-finance companies.
CGB1201 Java Programming project.

## Run it
    ./run.sh            # macOS / Linux / Git Bash   (needs JDK 17+)
    run.bat             # Windows (Command Prompt)
    docker compose up -d --build
    cmd //c run.bat

Open **http://localhost:8080**. Nothing is pre-loaded: you start with a clean website.
Click **Get started**, create your account, and your private workspace is ready.

Settings (environment variables): `PORT` (8080), `BRANCH_DB` (data/branch.db).

## How accounts work
- **Sign up** creates an organisation and its administrator. **Log in** uses email + password.
- Every organisation is a private workspace. Its borrowers, loans (type, amount, tenure, who it was
  issued to, who issued it), repayments and team are stored under that organisation only.
  Another account can never see or change them.
- Administrators add team members (loan officers or administrators) and can reset their passwords.
  The **Team** page shows how many loans each person issued and how much they collected.

## Self-hosting
One process, one SQLite file (`data/branch.db`). No cloud, no CDN. A daily backup is written to
`data/backups` (last 14 kept). Put it behind HTTPS (Caddy / nginx) for internet use; cookies become
`Secure` automatically when `X-Forwarded-Proto: https` is sent.

## Project layout
    web/            index.html, app.css, app.js   (the website: landing, sign up, log in, app)
    src/branch/     the Java server
      Main.java       starts the HTTP server, serves files, daily backups   (threads, file I/O)
      Api.java        JSON endpoints                                         (nested class, lambdas, regex)
      Loans.java      loan rules: create, repay, overdue, penalty             (static methods, overloading)
      Product.java    abstract class + Individual / Group / Emergency        (inheritance, polymorphism)
      Calculator.java interface with a default method                        (interface)
      LoanStatus.java enum                                                   (enum)
      Auth.java       password hashing, sessions, lockout                    (encapsulation, collections)
      Db.java         SQLite schema and transactions                         (JDBC, generics, varargs, try-with-resources)
      Json.java       small JSON reader/writer                               (recursion, StringBuilder)
      ApiException.java custom exception                                     (exceptions)

## Java concepts covered
Classes & objects · Encapsulation · Inheritance · Polymorphism (overriding) · Abstract class ·
Interface (with default method) · Enum · Records · Method overloading (`Loans.rupees`) · Varargs
(`Db.rows`) · Generics (`Db.Work<T>`) · Collections (List, Map, Set) · Lambdas & streams ·
Exceptions (custom + try/catch/finally + try-with-resources) · Nested/inner classes · Static & final ·
Packages · String handling & regex · Date/Time API · File I/O · JDBC / SQL · Threads, thread pool,
`synchronized` · Input validation.

## Safeguards
- Money is stored as whole paise; schedules always add up exactly.
- All writes are serialized transactions. Overpayments, future-dated payments and loans for borrowers
  with overdue dues are refused.
- Passwords: PBKDF2-SHA256. 5 wrong attempts lock that email for 5 minutes.
- HttpOnly + SameSite=Strict cookies, CSRF header check, Content-Security-Policy, CSV formula guard.
- Every database query is filtered by the signed-in user's organisation.
