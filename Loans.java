package branch;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static branch.Db.l;
import static branch.Db.s;

/**
 * Loan rules: creating loans, taking repayments, overdue and penalty.
 * Every method takes the organisation id, so one account can never touch another's data.
 * Money is stored in paise (integers) so there are no rounding surprises.
 */
public final class Loans {
    private Loans() {}

    /** Penalty: 0.10% of the unpaid installment, per day late. */
    public static final int PENALTY_BP_PER_DAY = 10;
    public static final Set<String> METHODS = Set.of("CASH", "UPI", "BANK");

    private static final String BASE = """
        SELECT l.*, b.name AS borrower_name, b.phone AS phone, b.code AS borrower_code,
          (SELECT name FROM users WHERE id=l.created_by) AS issued_by,
          (SELECT COALESCE(SUM(paid),0) FROM installments WHERE loan_id=l.id) AS paid,
          (SELECT COALESCE(SUM(emi-paid),0) FROM installments WHERE loan_id=l.id AND due_date<?1) AS overdue,
          (SELECT MIN(due_date) FROM installments WHERE loan_id=l.id AND paid<emi AND due_date<?1) AS oldest_due,
          (SELECT MIN(due_date) FROM installments WHERE loan_id=l.id AND paid<emi) AS next_due,
          (SELECT emi-paid FROM installments WHERE loan_id=l.id AND paid<emi ORDER BY no LIMIT 1) AS next_amount,
          (SELECT COALESCE(SUM((emi-paid)*(julianday(?1)-julianday(due_date))),0)
             FROM installments WHERE loan_id=l.id AND paid<emi AND due_date<?1) AS pen_base
        FROM loans l JOIN borrowers b ON b.id=l.borrower_id
        WHERE l.org_id=?2""";

    public static Map<String, Object> enrich(Map<String, Object> r, LocalDate today) {
        long out = l(r.get("total_payable")) - l(r.get("paid"));
        r.put("outstanding", out);
        r.put("status", LoanStatus.of(out, l(r.get("overdue"))).name());
        String oldest = s(r.get("oldest_due"));
        r.put("days_overdue", oldest == null ? 0 : ChronoUnit.DAYS.between(LocalDate.parse(oldest), today));
        double base = r.get("pen_base") == null ? 0 : ((Number) r.get("pen_base")).doubleValue();
        r.put("penalty", Math.round(base * PENALTY_BP_PER_DAY / 10000.0));
        r.remove("pen_base");
        Product p = Product.get(s(r.get("product")));
        r.put("product_label", p == null ? s(r.get("product")) : p.label());
        return r;
    }

    public static List<Map<String, Object>> list(Db.Conn c, long org, LocalDate today, String q) throws SQLException {
        String clean = q == null ? "" : q.replaceAll("[%_]", "").trim();
        List<Map<String, Object>> rows = clean.isEmpty()
                ? c.rows(BASE + " ORDER BY l.id DESC", today.toString(), org)
                : c.rows(BASE + " AND (l.code LIKE ?3 OR b.name LIKE ?3 OR b.phone LIKE ?3 OR b.code LIKE ?3) ORDER BY l.id DESC",
                         today.toString(), org, "%" + clean + "%");
        for (Map<String, Object> r : rows) enrich(r, today);
        return rows;
    }

    public static List<Map<String, Object>> forBorrower(Db.Conn c, long org, long borrowerId, LocalDate today) throws SQLException {
        List<Map<String, Object>> rows = c.rows(BASE + " AND l.borrower_id=?3 ORDER BY l.id DESC", today.toString(), org, borrowerId);
        for (Map<String, Object> r : rows) enrich(r, today);
        return rows;
    }

    public static Map<String, Object> get(Db.Conn c, long org, long id, LocalDate today) throws SQLException {
        Map<String, Object> r = c.one(BASE + " AND l.id=?3", today.toString(), org, id);
        if (r == null) throw ApiException.notFound("Loan not found.");
        return enrich(r, today);
    }

    public static Map<String, Object> detail(Db.Conn c, long org, long id, LocalDate today) throws SQLException {
        Map<String, Object> loan = get(c, org, id, today);
        List<Map<String, Object>> inst = c.rows(
                "SELECT no, due_date, emi, principal, interest, paid FROM installments WHERE loan_id=? ORDER BY no", id);
        for (Map<String, Object> i : inst) {
            long emi = l(i.get("emi")), paid = l(i.get("paid"));
            LocalDate due = LocalDate.parse(s(i.get("due_date")));
            String st = paid >= emi ? "PAID" : due.isBefore(today) ? "OVERDUE" : due.equals(today) ? "DUE" : "UPCOMING";
            long pen = (paid < emi && due.isBefore(today))
                    ? Math.round((emi - paid) * ChronoUnit.DAYS.between(due, today) * PENALTY_BP_PER_DAY / 10000.0) : 0;
            i.put("status", st);
            i.put("penalty", pen);
        }
        loan.put("schedule", inst);
        loan.put("payments", c.rows("""
            SELECT p.id, p.receipt_no, p.amount, p.paid_on, p.method, p.note, u.name AS received_by
            FROM payments p LEFT JOIN users u ON u.id=p.received_by
            WHERE p.loan_id=? ORDER BY p.paid_on DESC, p.id DESC""", id));
        return loan;
    }

    public static Map<String, Object> preview(String productKey, long principal, int months, LocalDate start) {
        Product p = Product.get(productKey);
        if (p == null) throw ApiException.bad("Choose a loan product.");
        checkTerms(p, principal, months);
        List<Product.Row> rows = p.schedule(principal, months, start);
        long total = rows.stream().mapToLong(Product.Row::emi).sum();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("emi", p.emi(principal, months));
        m.put("rate_bp", p.rateBp());
        m.put("total_payable", total);
        m.put("total_interest", total - principal);
        m.put("last_due", rows.get(rows.size() - 1).due().toString());
        return m;
    }

    private static void checkTerms(Product p, long principal, int months) {
        if (principal < 100_000 || principal > 50_000_000) throw ApiException.bad("Loan amount must be between ₹1,000 and ₹5,00,000.");
        if (months < 1 || months > p.maxMonths()) throw ApiException.bad(p.label() + " loans run 1 to " + p.maxMonths() + " months.");
    }

    public static long createBorrower(Db.Conn c, long org, String name, String phone, String address,
                                      String occupation, String idProof, long userId) throws SQLException {
        if (!phone.matches("[6-9]\\d{9}")) throw ApiException.bad("Enter a valid 10-digit mobile number.");
        if (c.num("SELECT COUNT(*) FROM borrowers WHERE org_id=? AND phone=?", org, phone) > 0)
            throw ApiException.conflict("A borrower with this phone number already exists.");
        long next = c.num("SELECT COUNT(*)+1 FROM borrowers WHERE org_id=?", org);
        return c.insert("INSERT INTO borrowers(org_id,code,name,phone,address,occupation,id_proof,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                org, String.format("BR%04d", next), name, phone, address, occupation, idProof, userId, LocalDateTime.now().toString());
    }

    public static long create(Db.Conn c, long org, long borrowerId, String productKey, long principal, int months,
                              LocalDate start, String purpose, long userId, LocalDate today) throws SQLException {
        Product p = Product.get(productKey);
        if (p == null) throw ApiException.bad("Choose a loan product.");
        if (c.one("SELECT id FROM borrowers WHERE id=? AND org_id=?", borrowerId, org) == null) throw ApiException.notFound("Borrower not found.");
        checkTerms(p, principal, months);
        if (start.isAfter(today)) throw ApiException.bad("The disbursement date can't be in the future.");
        if (start.isBefore(today.minusYears(2))) throw ApiException.bad("The disbursement date is too far in the past.");
        if (c.num("""
                SELECT COUNT(*) FROM installments i JOIN loans l ON l.id=i.loan_id
                WHERE l.borrower_id=? AND i.paid<i.emi AND i.due_date<?""", borrowerId, today.toString()) > 0)
            throw ApiException.conflict("This borrower has an overdue loan. Clear it before issuing a new one.");

        List<Product.Row> rows = p.schedule(principal, months, start);
        long total = rows.stream().mapToLong(Product.Row::emi).sum();
        long next = c.num("SELECT COUNT(*)+1 FROM loans WHERE org_id=?", org);
        long id = c.insert("""
            INSERT INTO loans(org_id,code,borrower_id,product,principal,rate_bp,months,emi,total_payable,start_date,purpose,created_by,created_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                org, String.format("LN%04d", next), borrowerId, p.key(), principal, p.rateBp(), months, p.emi(principal, months),
                total, start.toString(), purpose, userId, LocalDateTime.now().toString());
        for (Product.Row r : rows)
            c.exec("INSERT INTO installments(loan_id,no,due_date,emi,principal,interest) VALUES(?,?,?,?,?,?)",
                    id, r.no(), r.due().toString(), r.emi(), r.principal(), r.interest());
        return id;
    }

    /** Records a repayment and applies it to the oldest unpaid installments first. */
    public static Map<String, Object> pay(Db.Conn c, long org, long loanId, long amount, LocalDate on, String method,
                                          String note, long userId, LocalDate today) throws SQLException {
        Map<String, Object> loan = get(c, org, loanId, today);
        if (amount <= 0) throw ApiException.bad("Enter an amount greater than zero.");
        if (!METHODS.contains(method)) throw ApiException.bad("Choose how the payment was received.");
        if (on.isAfter(today)) throw ApiException.bad("The payment date can't be in the future.");
        if (on.isBefore(LocalDate.parse(s(loan.get("start_date"))))) throw ApiException.bad("The payment can't be dated before the loan started.");
        long outstanding = l(loan.get("outstanding"));
        if (outstanding <= 0) throw ApiException.conflict("This loan is already closed.");
        if (amount > outstanding) throw ApiException.bad("That is more than the outstanding balance of " + rupees(outstanding) + ".");

        long remaining = amount;
        for (Map<String, Object> i : c.rows("SELECT id, emi, paid FROM installments WHERE loan_id=? AND paid<emi ORDER BY no", loanId)) {
            if (remaining == 0) break;
            long take = Math.min(l(i.get("emi")) - l(i.get("paid")), remaining);
            c.exec("UPDATE installments SET paid=paid+? WHERE id=?", take, l(i.get("id")));
            remaining -= take;
        }
        long pid = c.insert("INSERT INTO payments(receipt_no,loan_id,amount,paid_on,method,note,received_by,created_at) VALUES('PENDING-'||hex(randomblob(6)),?,?,?,?,?,?,?)",
                loanId, amount, on.toString(), method, note, userId, LocalDateTime.now().toString());
        String receipt = String.format("RC%06d", pid);
        c.exec("UPDATE payments SET receipt_no=? WHERE id=?", receipt, pid);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receipt_no", receipt);
        out.put("amount", amount);
        out.put("paid_on", on.toString());
        out.put("method", method);
        out.put("note", note);
        out.put("loan_id", loanId);
        out.put("loan_code", loan.get("code"));
        out.put("borrower_name", loan.get("borrower_name"));
        out.put("outstanding_after", outstanding - amount);
        out.put("received_by", c.one("SELECT name FROM users WHERE id=?", userId).get("name"));
        return out;
    }

    /** Method overloading: with or without the rupee sign. */
    public static String rupees(long paise) { return rupees(paise, true); }

    public static String rupees(long paise, boolean symbol) {
        long r = paise / 100, p = paise % 100;
        String s = Long.toString(r), head = s.length() > 3 ? s.substring(0, s.length() - 3) : "", tail = s.substring(Math.max(0, s.length() - 3));
        StringBuilder g = new StringBuilder();
        for (int i = 0; i < head.length(); i++) { if (i > 0 && (head.length() - i) % 2 == 0) g.append(','); g.append(head.charAt(i)); }
        String grouped = head.isEmpty() ? tail : g + "," + tail;
        return (symbol ? "₹" : "") + grouped + (p == 0 ? "" : String.format(".%02d", p));
    }
}
