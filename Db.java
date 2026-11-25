package branch;

import java.io.File;
import java.sql.*;
import java.util.*;

/** SQLite access: schema, tiny query helpers and transactions. */
public final class Db {
    private Db() {}
    private static String url;
    private static final Object WRITE_LOCK = new Object();

    public static void init(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            File f = new File(path).getAbsoluteFile();
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            url = "jdbc:sqlite:" + f.getPath();
            archiveOldSchema(f);
            try (Connection c = open(); Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                for (String ddl : SCHEMA.split(";\\s*\n")) if (!ddl.isBlank()) s.execute(ddl);
            }
        } catch (Exception e) { throw new RuntimeException("Database start-up failed: " + e.getMessage(), e); }
    }

    /** A database from before accounts existed is kept aside (never deleted) and a fresh one is started. */
    private static void archiveOldSchema(File f) throws Exception {
        if (!f.exists()) return;
        boolean old = false;
        try (Connection c = open(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA table_info(users)")) {
            boolean any = false, has = false;
            while (rs.next()) { any = true; if ("org_id".equals(rs.getString("name"))) has = true; }
            old = any && !has;
        }
        if (!old) return;
        try (Connection c = open(); Statement s = c.createStatement()) { s.execute("PRAGMA wal_checkpoint(TRUNCATE)"); }
        File keep = new File(f.getParentFile(), "branch-before-accounts-" + System.currentTimeMillis() + ".db");
        java.nio.file.Files.move(f.toPath(), keep.toPath());
        new File(f.getPath() + "-wal").delete(); new File(f.getPath() + "-shm").delete();
        System.out.println("  Your earlier test data was moved to " + keep.getName());
    }

    private static Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=8000");
        }
        return c;
    }

    private static final String SCHEMA = """
        CREATE TABLE IF NOT EXISTS orgs(
          id INTEGER PRIMARY KEY,
          name TEXT NOT NULL,
          created_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS users(
          id INTEGER PRIMARY KEY,
          org_id INTEGER NOT NULL REFERENCES orgs(id),
          email TEXT NOT NULL UNIQUE COLLATE NOCASE,
          name TEXT NOT NULL,
          role TEXT NOT NULL CHECK(role IN ('ADMIN','OFFICER')),
          salt TEXT NOT NULL, hash TEXT NOT NULL,
          active INTEGER NOT NULL DEFAULT 1,
          created_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS borrowers(
          id INTEGER PRIMARY KEY,
          org_id INTEGER NOT NULL REFERENCES orgs(id),
          code TEXT NOT NULL,
          name TEXT NOT NULL,
          phone TEXT NOT NULL,
          address TEXT NOT NULL,
          occupation TEXT, id_proof TEXT,
          created_by INTEGER REFERENCES users(id),
          created_at TEXT NOT NULL,
          UNIQUE(org_id, code), UNIQUE(org_id, phone));
        CREATE TABLE IF NOT EXISTS loans(
          id INTEGER PRIMARY KEY,
          org_id INTEGER NOT NULL REFERENCES orgs(id),
          code TEXT NOT NULL,
          borrower_id INTEGER NOT NULL REFERENCES borrowers(id),
          product TEXT NOT NULL,
          principal INTEGER NOT NULL CHECK(principal > 0),
          rate_bp INTEGER NOT NULL,
          months INTEGER NOT NULL CHECK(months BETWEEN 1 AND 60),
          emi INTEGER NOT NULL,
          total_payable INTEGER NOT NULL,
          start_date TEXT NOT NULL,
          purpose TEXT,
          created_by INTEGER REFERENCES users(id),
          created_at TEXT NOT NULL,
          UNIQUE(org_id, code));
        CREATE TABLE IF NOT EXISTS installments(
          id INTEGER PRIMARY KEY,
          loan_id INTEGER NOT NULL REFERENCES loans(id),
          no INTEGER NOT NULL,
          due_date TEXT NOT NULL,
          emi INTEGER NOT NULL, principal INTEGER NOT NULL, interest INTEGER NOT NULL,
          paid INTEGER NOT NULL DEFAULT 0 CHECK(paid >= 0),
          UNIQUE(loan_id, no));
        CREATE TABLE IF NOT EXISTS payments(
          id INTEGER PRIMARY KEY,
          receipt_no TEXT NOT NULL UNIQUE,
          loan_id INTEGER NOT NULL REFERENCES loans(id),
          amount INTEGER NOT NULL CHECK(amount > 0),
          paid_on TEXT NOT NULL,
          method TEXT NOT NULL,
          note TEXT,
          received_by INTEGER REFERENCES users(id),
          created_at TEXT NOT NULL);
        CREATE INDEX IF NOT EXISTS idx_users_org ON users(org_id);
        CREATE INDEX IF NOT EXISTS idx_borrowers_org ON borrowers(org_id);
        CREATE INDEX IF NOT EXISTS idx_loans_org ON loans(org_id, borrower_id);
        CREATE INDEX IF NOT EXISTS idx_inst_loan ON installments(loan_id, due_date);
        CREATE INDEX IF NOT EXISTS idx_pay_loan ON payments(loan_id);
        CREATE INDEX IF NOT EXISTS idx_pay_date ON payments(paid_on);
        """;

    public static long l(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    public static String s(Object o) { return o == null ? null : o.toString(); }

    public static final class Conn implements AutoCloseable {
        final Connection c;
        Conn(Connection c) { this.c = c; }

        private static void bind(PreparedStatement ps, Object[] p) throws SQLException {
            for (int i = 0; i < p.length; i++) {
                if (p[i] == null) ps.setNull(i + 1, Types.NULL); else ps.setObject(i + 1, p[i]);
            }
        }
        public List<Map<String, Object>> rows(String sql, Object... p) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, p);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) m.put(md.getColumnLabel(i), rs.getObject(i));
                        out.add(m);
                    }
                    return out;
                }
            }
        }
        public Map<String, Object> one(String sql, Object... p) throws SQLException {
            List<Map<String, Object>> r = rows(sql, p); return r.isEmpty() ? null : r.get(0);
        }
        public long num(String sql, Object... p) throws SQLException {
            Map<String, Object> m = one(sql, p);
            return m == null ? 0 : l(m.values().iterator().next());
        }
        public int exec(String sql, Object... p) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(sql)) { bind(ps, p); return ps.executeUpdate(); }
        }
        public long insert(String sql, Object... p) throws SQLException {
            exec(sql, p); return num("SELECT last_insert_rowid()");
        }
        @Override public void close() { try { c.close(); } catch (SQLException ignored) {} }
    }

    public interface Work<T> { T run(Conn c) throws Exception; }

    public static <T> T read(Work<T> w) {
        try (Conn c = new Conn(open())) { return w.run(c); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** One transaction; all writers are serialized so money never races. */
    public static <T> T write(Work<T> w) {
        synchronized (WRITE_LOCK) {
            try (Conn c = new Conn(open())) {
                c.c.setAutoCommit(false);
                try { T r = w.run(c); c.c.commit(); return r; }
                catch (Throwable e) { try { c.c.rollback(); } catch (SQLException ignored) {} throw e; }
            } catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    public static void backupTo(String file) {
        read(c -> { c.exec("VACUUM INTO '" + file.replace("'", "''") + "'"); return null; });
    }
}
