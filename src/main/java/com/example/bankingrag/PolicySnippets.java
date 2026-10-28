package com.example.bankingrag;

import java.util.List;

/** A small, hand-written corpus of banking policy sentences, each tagged with a topic. */
public final class PolicySnippets {

    /** {@code feeAmount} is the flat fee in dollars the policy charges, or null when it has none. */
    public record Snippet(long id, String topic, String text, Double feeAmount) {}

    public static final List<Snippet> ALL = List.of(
            new Snippet(1, "overdraft", "An overdraft fee of $35 is charged when a transaction exceeds the available balance.", 35.0),
            new Snippet(2, "overdraft", "Customers can opt in to overdraft protection by linking a savings account to cover shortfalls.", null),
            new Snippet(3, "overdraft", "No more than three $35 overdraft fees will be charged to an account in a single business day.", 35.0),
            new Snippet(4, "overdraft", "Overdrawn balances must be repaid within 30 days or the account may be closed.", null),
            new Snippet(5, "fees", "The $12 monthly maintenance fee is waived when the average daily balance stays above $1,500.", 12.0),
            new Snippet(6, "fees", "Out-of-network ATM withdrawals incur a $3 fee in addition to any operator surcharge.", 3.0),
            new Snippet(7, "fraud", "Report a lost or stolen debit card immediately to limit liability for unauthorized charges.", null),
            new Snippet(8, "fraud", "The bank will never ask for your PIN or full password by phone, email, or text message.", null),
            new Snippet(9, "loans", "Personal loan applicants need a minimum credit score of 650 and verifiable income.", null),
            new Snippet(10, "loans", "Late loan payments are charged a fee of 5% of the missed installment after a 10-day grace period.", null),
            new Snippet(11, "savings", "Savings accounts earn interest compounded daily and credited at the end of each month.", null),
            new Snippet(12, "transfers", "International wire transfers cost $25 and those sent after 4 PM Eastern are processed the next business day.", 25.0)
    );

    private PolicySnippets() {}
}
