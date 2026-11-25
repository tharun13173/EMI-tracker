package branch;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static branch.Db.l;
import static branch.Db.s;

/** All JSON endpoints under /api. Every data query is scoped to the signed-in user's organisation. */
public final class Api {
    private Api() {}

    public record Raw(String type, byte[] data, String filename) {}

    // ---------- request context (a nested class) ----------
    static final class Ctx {
        final HttpExchange ex; final String method, path; final Map<String, String> query = new HashMap<>();
        Map<String, Object> body; Auth.Session user; String token;

        Ctx(HttpExchange ex) {
            this.ex = ex; this.method = ex.getRequestMethod(); this.path = ex.getRequestURI().getPath();
            String raw = ex.getRequestURI().getRawQuery();
            if (raw != null) for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                String k = i < 0 ? kv : kv.substring(0, i), v = i < 0 ? "" : kv.substring(i + 1);
                query.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            }
            String cookies = ex.getRequestHeaders().getFirst("Cookie");
            if (cookies != null) for (String c : cookies.split(";")) {
                String[] p = c.trim().split("=", 2);
                if (p.length == 2 && p[0].equals("sid")) token = p[1];
            }
            user = Auth.get(token);
        }
        Matcher m(String meth, String regex) {
            if (!method.equals(meth)) return null;
            Matcher mt = Pattern.compile(regex).matcher(path);
            return mt.matches() ? mt : null;
        }
        void needUser() { if (user == null) throw new ApiException(401, "Please sign in."); }
        void needAdmin() { needUser(); if (!"ADMIN".equals(user.role())) throw new ApiException(403, "Only an administrator can do that."); }

        @SuppressWarnings("unchecked")
        Map<String, Object> body() {
            if (body == null) {
                try {
                    byte[] b = ex.getRequestBody().readNBytes(1_000_001);
                    if (b.length > 1_000_000) throw ApiException.bad("Request too large.");
                    String t = new String(b, StandardCharsets.UTF_8).trim();
                    Object o = t.isEmpty() ? new LinkedHashMap<String, Object>() : Json.parse(t);
                    if (!(o instanceof Map)) throw ApiException.bad("Invalid request.");
                    body = (Map<String, Object>) o;
                } catch (IOException | IllegalArgumentException e) { throw ApiException.bad("Invalid request."); }
            }
            return body;
        }
        String str(String k, String label, int min, int max) {
            Object v = body().get(k); String t = v == null ? "" : v.toString().trim();
            if (t.length() < min) throw ApiException.bad(label + " is required.");
            if (t.length() > max) throw ApiException.bad(label + " is too long.");
            return t;
        }
        String opt(String k, int max) { String t = str(k, "", 0, max); return t.isEmpty() ? null : t; }
        long paise(String k, String label) {
            if (!(body().get(k) instanceof Number n) || !(n.doubleValue() > 0) || n.doubleValue() > 1e8) throw ApiException.bad(label + " must be a positive amount.");
            return Math.round(n.doubleValue() * 100);
        }
        int integer(String k, String label) {
            if (!(body().get(k) instanceof Number n)) throw ApiException.bad(label + " is required.");
            return n.intValue();
        }
        long id(String k, String label) {
            if (!(body().get(k) instanceof Number n)) throw ApiException.bad(label + " is required.");
            return n.longValue();
        }
        LocalDate date(String k, LocalDate def) {
            String t = opt(k, 10); if (t == null) return def;
            try { return LocalDate.parse(t); } catch (Exception e) { throw ApiException.bad("Enter a valid date."); }
        }
    }

    // ---------- entry point ----------
    public static void handle(HttpExchange ex) throws IOException {
        int status = 200; Object result; Map<String, String> headers = new HashMap<>();
        try {
            Ctx x = new Ctx(ex);
            if (!x.method.equals("GET") && !"branch".equals(ex.getRequestHeaders().getFirst("X-Requested-With")))
                throw new ApiException(403, "Forbidden.");
            result = route(x, headers);
        } catch (ApiException e) { status = e.status; result = Map.of("error", e.getMessage());
        } catch (Exception e) {
            e.printStackTrace(); status = 500; result = Map.of("error", "Something went wrong on our side. Please try again.");
        }
        if (result instanceof Raw r) {
            ex.getResponseHeaders().set("Content-Type", r.type());
            if (r.filename() != null) ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + r.filename() + "\"");
            ex.sendResponseHeaders(status, r.data().length);
            ex.getResponseBody().write(r.data());
        } else {
            byte[] out = Json.write(result).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
        }
    }

    private static String cookie(Ctx x, String token, int maxAge) {
        boolean https = "https".equalsIgnoreCase(x.ex.getRequestHeaders().getFirst("X-Forwarded-Proto"));
        return "sid=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge + (https ? "; Secure" : "");
    }

    private static Map<String, Object> userView(Auth.Session u) {
        return Map.of("id", u.userId(), "email", u.email(), "name", u.name(), "role", u.role(), "org", u.orgName());
    }

    // ---------- routes ----------
    private static Object route(Ctx x, Map<String, String> headers) {
        Matcher m;
        LocalDate today = LocalDate.now();

        // public
        if (x.m("GET", "/api/status") != null) return status(x);
        if (x.m("POST", "/api/signup") != null) return signup(x, headers);
        if (x.m("POST", "/api/login") != null) return login(x, headers);
        if (x.m("POST", "/api/logout") != null) {
            Auth.close(x.token); headers.put("Set-Cookie", cookie(x, "", 0)); return Map.of("ok", true);
        }

        // everything below needs a signed-in user and only ever sees their own organisation
        x.needUser();
        final long org = x.user.orgId(), me = x.user.userId();

        if (x.m("GET", "/api/dashboard") != null) return Db.read(c -> dashboard(c, org, today));

        if (x.m("GET", "/api/borrowers") != null) return Db.read(c -> {
            String q = x.query.getOrDefault("q", "").replaceAll("[%_]", "").trim();
            return c.rows("""
                SELECT b.*, (SELECT COUNT(*) FROM loans WHERE borrower_id=b.id) AS loans
                FROM borrowers b WHERE b.org_id=?2 AND (b.name LIKE ?1 OR b.phone LIKE ?1 OR b.code LIKE ?1) ORDER BY b.id DESC""",
                    "%" + q + "%", org);
        });
        if (x.m("POST", "/api/borrowers") != null) {
            String name = x.str("name", "Name", 2, 80), phone = x.str("phone", "Phone", 1, 15), addr = x.str("address", "Address", 3, 200);
            String occ = x.opt("occupation", 80), idp = x.opt("id_proof", 30);
            return Db.write(c -> Map.of("id", Loans.createBorrower(c, org, name, phone, addr, occ, idp, me)));
        }
        if ((m = x.m("GET", "/api/borrowers/(\\d+)")) != null) { long id = Long.parseLong(m.group(1)); return Db.read(c -> {
            Map<String, Object> b = c.one("SELECT * FROM borrowers WHERE id=? AND org_id=?", id, org);
            if (b == null) throw ApiException.notFound("Borrower not found.");
            b.put("loans", Loans.forBorrower(c, org, id, today)); return b; }); }
        if ((m = x.m("PUT", "/api/borrowers/(\\d+)")) != null) {
            long id = Long.parseLong(m.group(1));
            String name = x.str("name", "Name", 2, 80), phone = x.str("phone", "Phone", 1, 15), addr = x.str("address", "Address", 3, 200);
            String occ = x.opt("occupation", 80), idp = x.opt("id_proof", 30);
            if (!phone.matches("[6-9]\\d{9}")) throw ApiException.bad("Enter a valid 10-digit mobile number.");
            return Db.write(c -> {
                if (c.num("SELECT COUNT(*) FROM borrowers WHERE org_id=? AND phone=? AND id<>?", org, phone, id) > 0) throw ApiException.conflict("Another borrower already uses this phone number.");
                if (c.exec("UPDATE borrowers SET name=?, phone=?, address=?, occupation=?, id_proof=? WHERE id=? AND org_id=?", name, phone, addr, occ, idp, id, org) == 0) throw ApiException.notFound("Borrower not found.");
                return Map.of("ok", true); });
        }

        if (x.m("GET", "/api/loans") != null) return Db.read(c -> {
            List<Map<String, Object>> all = Loans.list(c, org, today, x.query.get("q"));
            String st = x.query.getOrDefault("status", "");
            if (!st.isEmpty()) all.removeIf(r -> !st.equalsIgnoreCase(s(r.get("status"))));
            return all; });
        if (x.m("POST", "/api/loans/preview") != null) return Loans.preview(x.str("product", "Product", 1, 20), x.paise("amount", "Amount"), x.integer("months", "Tenure"), x.date("start_date", today));
        if (x.m("POST", "/api/loans") != null) {
            long borrower = x.id("borrower_id", "Borrower"), principal = x.paise("amount", "Amount");
            String product = x.str("product", "Product", 1, 20), purpose = x.opt("purpose", 120);
            int months = x.integer("months", "Tenure"); LocalDate start = x.date("start_date", today);
            return Db.write(c -> Map.of("id", Loans.create(c, org, borrower, product, principal, months, start, purpose, me, today)));
        }
        if ((m = x.m("GET", "/api/loans/(\\d+)")) != null) { long id = Long.parseLong(m.group(1)); return Db.read(c -> Loans.detail(c, org, id, today)); }
        if ((m = x.m("POST", "/api/loans/(\\d+)/payments")) != null) {
            long id = Long.parseLong(m.group(1)), amount = x.paise("amount", "Amount");
            String method = x.str("method", "Method", 1, 10); LocalDate on = x.date("paid_on", today); String note = x.opt("note", 120);
            return Db.write(c -> Loans.pay(c, org, id, amount, on, method, note, me, today));
        }

        if (x.m("GET", "/api/collections") != null) return Db.read(c -> collections(c, org, today));
        if (x.m("GET", "/api/export/loans.csv") != null) return Db.read(c -> exportCsv(c, org, today));

        if (x.m("POST", "/api/password") != null) {
            String cur = x.str("current", "Current password", 1, 200), next = x.str("password", "New password", 1, 200);
            Auth.checkPassword(next);
            return Db.write(c -> {
                Map<String, Object> u = c.one("SELECT salt, hash FROM users WHERE id=?", me);
                if (!Auth.verify(cur, s(u.get("salt")), s(u.get("hash")))) throw ApiException.bad("Your current password is incorrect.");
                String[] h = Auth.hash(next);
                c.exec("UPDATE users SET salt=?, hash=? WHERE id=?", h[0], h[1], me);
                return Map.of("ok", true); });
        }

        // ----- team (administrators only) -----
        if (x.m("GET", "/api/users") != null) { x.needAdmin(); return Db.read(c -> c.rows("""
            SELECT u.id, u.email, u.name, u.role, u.active, u.created_at,
              (SELECT COUNT(*) FROM loans WHERE created_by=u.id) AS loans_issued,
              (SELECT COALESCE(SUM(amount),0) FROM payments WHERE received_by=u.id) AS collected
            FROM users u WHERE u.org_id=? ORDER BY u.id""", org)); }
        if (x.m("POST", "/api/users") != null) {
            x.needAdmin();
            String email = x.str("email", "Email", 5, 120).toLowerCase(), name = x.str("name", "Name", 2, 60),
                   role = x.str("role", "Role", 1, 10), pw = x.str("password", "Password", 1, 200);
            Auth.checkEmail(email); Auth.checkPassword(pw);
            if (!role.equals("ADMIN") && !role.equals("OFFICER")) throw ApiException.bad("Choose a role.");
            String[] h = Auth.hash(pw);
            return Db.write(c -> {
                if (c.num("SELECT COUNT(*) FROM users WHERE email=?", email) > 0) throw ApiException.conflict("That email already has an account.");
                return Map.of("id", c.insert("INSERT INTO users(org_id,email,name,role,salt,hash,created_at) VALUES(?,?,?,?,?,?,?)",
                        org, email, name, role, h[0], h[1], LocalDateTime.now().toString())); });
        }
        if ((m = x.m("POST", "/api/users/(\\d+)/active")) != null) {
            x.needAdmin(); long id = Long.parseLong(m.group(1));
            boolean active = Boolean.TRUE.equals(x.body().get("active"));
            if (id == me) throw ApiException.bad("You can't disable your own account.");
            return Db.write(c -> {
                if (c.exec("UPDATE users SET active=? WHERE id=? AND org_id=?", active ? 1 : 0, id, org) == 0) throw ApiException.notFound("User not found.");
                if (!active) Auth.closeAllFor(id);
                return Map.of("ok", true); });
        }
        if ((m = x.m("POST", "/api/users/(\\d+)/password")) != null) {
            x.needAdmin(); long id = Long.parseLong(m.group(1));
            String pw = x.str("password", "Password", 1, 200); Auth.checkPassword(pw);
            String[] h = Auth.hash(pw);
            return Db.write(c -> {
                if (c.exec("UPDATE users SET salt=?, hash=? WHERE id=? AND org_id=?", h[0], h[1], id, org) == 0) throw ApiException.notFound("User not found.");
                if (id != me) Auth.closeAllFor(id);
                return Map.of("ok", true); });
        }
        throw ApiException.notFound("Not found.");
    }

    // ---------- handlers ----------
    private static Object status(Ctx x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", x.user == null ? null : userView(x.user));
        m.put("penalty_bp_per_day", Loans.PENALTY_BP_PER_DAY);
        List<Map<String, Object>> ps = new ArrayList<>();
        for (Product p : Product.all()) ps.add(Map.of("key", p.key(), "label", p.label(), "blurb", p.blurb(), "rate_bp", p.rateBp(), "max_months", p.maxMonths()));
        m.put("products", ps);
        return m;
    }

    /** Creating an account also creates that lender's private workspace. */
    private static Object signup(Ctx x, Map<String, String> headers) {
        String name = x.str("name", "Your name", 2, 60), orgName = x.str("org", "Organisation name", 2, 80),
               email = x.str("email", "Email", 5, 120).toLowerCase(), pw = x.str("password", "Password", 1, 200);
        Auth.checkEmail(email); Auth.checkPassword(pw);
        String[] h = Auth.hash(pw);
        long[] ids = Db.write(c -> {
            if (c.num("SELECT COUNT(*) FROM users WHERE email=?", email) > 0)
                throw ApiException.conflict("An account with this email already exists. Try signing in.");
            long oid = c.insert("INSERT INTO orgs(name,created_at) VALUES(?,?)", orgName, LocalDateTime.now().toString());
            long uid = c.insert("INSERT INTO users(org_id,email,name,role,salt,hash,created_at) VALUES(?,?,?,?,?,?,?)",
                    oid, email, name, "ADMIN", h[0], h[1], LocalDateTime.now().toString());
            return new long[]{oid, uid}; });
        String token = Auth.open(ids[1], email, name, "ADMIN", ids[0], orgName);
        headers.put("Set-Cookie", cookie(x, token, 43200));
        return Map.of("user", userView(Auth.get(token)));
    }

    private static Object login(Ctx x, Map<String, String> headers) {
        String email = x.str("email", "Email", 1, 120).toLowerCase(), pw = x.str("password", "Password", 1, 200);
        Auth.checkNotLocked(email);
        Map<String, Object> u = Db.read(c -> c.one("SELECT u.*, o.name AS org_name FROM users u JOIN orgs o ON o.id=u.org_id WHERE u.email=?", email));
        boolean ok = u != null && l(u.get("active")) == 1 && Auth.verify(pw, s(u.get("salt")), s(u.get("hash")));
        if (u == null) Auth.dummyVerify(pw);
        if (!ok) { Auth.fail(email); throw new ApiException(401, "Wrong email or password."); }
        Auth.succeed(email);
        String token = Auth.open(l(u.get("id")), email, s(u.get("name")), s(u.get("role")), l(u.get("org_id")), s(u.get("org_name")));
        headers.put("Set-Cookie", cookie(x, token, 43200));
        return Map.of("user", userView(Auth.get(token)));
    }

    private static Object dashboard(Db.Conn c, long org, LocalDate today) throws Exception {
        List<Map<String, Object>> loans = Loans.list(c, org, today, "");
        long disbursed = 0, outstanding = 0, overdueAmt = 0, atRisk = 0, penalty = 0;
        Map<String, Integer> counts = new LinkedHashMap<>(Map.of("ACTIVE", 0, "OVERDUE", 0, "CLOSED", 0));
        for (Map<String, Object> r : loans) {
            long out = l(r.get("outstanding")); String st = s(r.get("status"));
            disbursed += l(r.get("principal")); outstanding += out; counts.merge(st, 1, Integer::sum);
            if (st.equals("OVERDUE")) { overdueAmt += l(r.get("overdue")); penalty += l(r.get("penalty")); }
            if (l(r.get("days_overdue")) > 30) atRisk += out;
        }
        List<Map<String, Object>> months = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth ym = YearMonth.from(today).minusMonths(i);
            months.add(Map.of("label", ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    "amount", c.num("SELECT COALESCE(SUM(p.amount),0) FROM payments p JOIN loans l ON l.id=p.loan_id WHERE l.org_id=? AND substr(p.paid_on,1,7)=?", org, ym.toString())));
        }
        List<Map<String, Object>> attention = new ArrayList<>();
        loans.stream().filter(r -> s(r.get("status")).equals("OVERDUE"))
             .sorted((a, b) -> Long.compare(l(b.get("days_overdue")), l(a.get("days_overdue")))).limit(5).forEach(attention::add);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("borrowers", c.num("SELECT COUNT(*) FROM borrowers WHERE org_id=?", org));
        out.put("loans", loans.size());
        out.put("disbursed", disbursed);
        out.put("collected", c.num("SELECT COALESCE(SUM(p.amount),0) FROM payments p JOIN loans l ON l.id=p.loan_id WHERE l.org_id=?", org));
        out.put("outstanding", outstanding);
        out.put("overdue", overdueAmt);
        out.put("penalty", penalty);
        out.put("par30", outstanding == 0 ? 0 : Math.round(atRisk * 1000.0 / outstanding) / 10.0);
        out.put("due_this_week", c.num("SELECT COALESCE(SUM(i.emi-i.paid),0) FROM installments i JOIN loans l ON l.id=i.loan_id WHERE l.org_id=? AND i.paid<i.emi AND i.due_date>=? AND i.due_date<=?", org, today.toString(), today.plusDays(7).toString()));
        out.put("counts", counts);
        out.put("months", months);
        out.put("attention", attention);
        out.put("recent", c.rows("""
            SELECT p.receipt_no, p.amount, p.paid_on, p.method, l.id AS loan_id, l.code, b.name AS borrower_name
            FROM payments p JOIN loans l ON l.id=p.loan_id JOIN borrowers b ON b.id=l.borrower_id
            WHERE l.org_id=? ORDER BY p.paid_on DESC, p.id DESC LIMIT 6""", org));
        return out;
    }

    private static Object collections(Db.Conn c, long org, LocalDate today) throws Exception {
        List<Map<String, Object>> overdue = Loans.list(c, org, today, "");
        overdue.removeIf(r -> !s(r.get("status")).equals("OVERDUE"));
        overdue.sort((a, b) -> Long.compare(l(b.get("days_overdue")), l(a.get("days_overdue"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overdue", overdue);
        out.put("upcoming", c.rows("""
            SELECT i.loan_id, l.code, b.name AS borrower_name, b.phone, i.due_date, i.emi - i.paid AS amount
            FROM installments i JOIN loans l ON l.id=i.loan_id JOIN borrowers b ON b.id=l.borrower_id
            WHERE l.org_id=?3 AND i.paid<i.emi AND i.due_date>=?1 AND i.due_date<=?2 ORDER BY i.due_date, b.name""",
                today.toString(), today.plusDays(7).toString(), org));
        return out;
    }

    private static String cell(Object o) {
        String t = o == null ? "" : o.toString();
        if (!t.isEmpty() && "=+-@".indexOf(t.charAt(0)) >= 0) t = "'" + t;      // block spreadsheet formula injection
        return "\"" + t.replace("\"", "\"\"") + "\"";
    }
    private static String money(Object paise) { return Loans.rupees(l(paise), false).replace(",", ""); }

    private static Object exportCsv(Db.Conn c, long org, LocalDate today) throws Exception {
        StringBuilder sb = new StringBuilder("Loan,Borrower,Phone,Product,Principal,Rate %,Months,EMI,Total payable,Paid,Outstanding,Overdue,Days overdue,Penalty,Status,Start date,Issued by\r\n");
        for (Map<String, Object> r : Loans.list(c, org, today, "")) {
            sb.append(String.join(",", cell(r.get("code")), cell(r.get("borrower_name")), cell(r.get("phone")), cell(r.get("product_label")),
                    money(r.get("principal")), String.valueOf(l(r.get("rate_bp")) / 100.0), String.valueOf(l(r.get("months"))), money(r.get("emi")),
                    money(r.get("total_payable")), money(r.get("paid")), money(r.get("outstanding")), money(r.get("overdue")),
                    String.valueOf(l(r.get("days_overdue"))), money(r.get("penalty")), cell(r.get("status")), cell(r.get("start_date")), cell(r.get("issued_by")))).append("\r\n");
        }
        return new Raw("text/csv; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8), "branch-loans-" + today + ".csv");
    }
}
