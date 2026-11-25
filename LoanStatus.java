package branch;

/** Enum: the three states a loan can be in. */
public enum LoanStatus {
    ACTIVE("On track"), OVERDUE("Overdue"), CLOSED("Closed");

    private final String label;
    LoanStatus(String label) { this.label = label; }
    public String label() { return label; }

    public static LoanStatus of(long outstanding, long overdue) {
        return outstanding <= 0 ? CLOSED : overdue > 0 ? OVERDUE : ACTIVE;
    }
}
