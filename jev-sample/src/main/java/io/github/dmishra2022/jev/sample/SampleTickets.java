package io.github.dmishra2022.jev.sample;

import java.util.List;

/** A small inbox covering the interesting routes: confident, ambiguous, urgent, and at-risk customers. */
public final class SampleTickets {

    public static final List<Ticket> ALL = List.of(
            new Ticket("T-1001", "Acme Corp", "enterprise", "Charged twice this month",
                    "Our invoice shows a double charge for March. Please refund the duplicate payment."),
            new Ticket("T-1002", "Globex", "pro", "API returns 500 errors",
                    "Since this morning every API call fails with errors. Production is down and we are "
                            + "losing orders. We need this fixed immediately!!"),
            new Ticket("T-1003", "Initech", "free", "Can't log in",
                    "Password reset email never arrives so I have no access to my profile."),
            new Ticket("T-1004", "Umbrella", "pro", "Upgrade pricing",
                    "What is the pricing for upgrading our plan to enterprise? Can you send a quote?"),
            new Ticket("T-1005", "Hooli", "enterprise", "This is the third time",
                    "The integration crashed again. This is unacceptable and ridiculous. If it's not fixed today "
                            + "we will cancel and switch to a competitor."),
            new Ticket("T-1006", "Vandelay", "free", "Question",
                    "Hi, quick question about my account and the invoice for the upgrade."),
            new Ticket("T-1007", "Stark Industries", "enterprise", "Payout failed",
                    "Our payouts failed overnight and the payment dashboard shows an error."),
            new Ticket("T-1008", "Wayne Enterprises", "pro", "Thanks!",
                    "Just wanted to say the new dashboard is great."));

    private SampleTickets() {
    }
}
