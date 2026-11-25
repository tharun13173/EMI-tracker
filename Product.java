package branch;

import java.time.LocalDate;
import java.util.*;

/**
 * A loan product. Each subclass owns its EMI and interest logic (polymorphism);
 * the shared schedule builder lives in the base class (inheritance). Money is in paise.
 */
public abstract class Product implements Calculator {
    public record Row(int no, LocalDate due, long emi, long principal, long interest, long balance) {}

    public abstract String key();
    public abstract String label();
    public abstract String blurb();
    public abstract int rateBp();                                   // 2400 = 24.00%
    public int maxMonths() { return 60; }
    protected abstract long interest(long openingBalance, long principal, int months, int n);

    public final List<Row> schedule(long principal, int months, LocalDate start) {
        long emi = emi(principal, months), balance = principal;
        List<Row> rows = new ArrayList<>();
        for (int n = 1; n <= months; n++) {
            long interest = interest(balance, principal, months, n);
            long prin = (n == months) ? balance : emi - interest;   // last row clears rounding drift
            balance -= prin;
            rows.add(new Row(n, start.plusMonths(n), prin + interest, prin, interest, balance));
        }
        return rows;
    }

    static abstract class Reducing extends Product {
        @Override public long emi(long p, int n) {
            double r = rateBp() / 120000.0, f = Math.pow(1 + r, n);
            return Math.round(p * r * f / (f - 1));
        }
        @Override protected long interest(long open, long p, int n, int i) { return Math.round(open * rateBp() / 120000.0); }
    }

    public static final class Individual extends Reducing {
        public String key() { return "INDIVIDUAL"; }
        public String label() { return "Individual"; }
        public String blurb() { return "For one borrower. Interest on the reducing balance."; }
        public int rateBp() { return 2400; }
    }

    public static final class Group extends Reducing {
        public String key() { return "GROUP"; }
        public String label() { return "Group"; }
        public String blurb() { return "For self-help groups. A lower rate, on the reducing balance."; }
        public int rateBp() { return 1800; }
    }

    public static final class Emergency extends Product {
        public String key() { return "EMERGENCY"; }
        public String label() { return "Emergency"; }
        public String blurb() { return "Short and fast. Flat rate, up to 12 months."; }
        public int rateBp() { return 3000; }
        @Override public int maxMonths() { return 30; }
        private long totalInterest(long p, int n) { return Math.round(p * rateBp() / 10000.0 * n / 12.0); }
        @Override public long emi(long p, int n) { return Math.round((p + totalInterest(p, n)) / (double) n); }
        @Override protected long interest(long open, long p, int n, int i) {
            long t = totalInterest(p, n); return t / n + (i == n ? t % n : 0);
        }
    }

    private static final Map<String, Product> ALL = new LinkedHashMap<>();
    static { for (Product p : List.of(new Individual(), new Group(), new Emergency())) ALL.put(p.key(), p); }
    public static Product get(String key) { return key == null ? null : ALL.get(key); }
    public static Collection<Product> all() { return ALL.values(); }
}
