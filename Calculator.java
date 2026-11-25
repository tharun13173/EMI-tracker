package branch;

/** Interface: anything that can work out an installment. Product implements it. */
public interface Calculator {
    long emi(long principal, int months);
    default String describe() { return "EMI calculator"; }
}
