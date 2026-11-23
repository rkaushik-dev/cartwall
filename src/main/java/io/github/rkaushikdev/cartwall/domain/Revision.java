package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * What changed between two versions of one order, line by line: the "rev 2" traffic has to act on.
 *
 * <p>Sellers rewrite pending orders constantly, and the agency checks the new confirmation against
 * the old one by eye. Saying which lines were added, which dropped and what moved on the rest is
 * the difference between a two minute check and a call. Lines are matched by number, because the
 * number is what both sides write on their copies; a line renumbered is a line dropped and a line
 * added, which is what it looks like on paper too.
 *
 * @param order the order number
 * @param changes every difference, in line number order
 * @param was what the earlier version was worth
 * @param now what this version is worth
 */
public record Revision(String order, List<Change> changes, Money was, Money now) {

    /** One difference between the two versions. */
    public sealed interface Change permits Added, Dropped, Changed {

        /** The line it is about. */
        int line();
    }

    /**
     * A line the new version has and the old one did not.
     *
     * @param written the line as it now stands
     */
    public record Added(Line written) implements Change {

        @Override
        public int line() {
            return written.number();
        }

        @Override
        public String toString() {
            return "line " + line() + " added: " + written;
        }
    }

    /**
     * A line the old version had and the new one does not.
     *
     * @param was the line as it was
     */
    public record Dropped(Line was) implements Change {

        @Override
        public int line() {
            return was.number();
        }

        @Override
        public String toString() {
            return "line " + line() + " dropped: " + was;
        }
    }

    /**
     * A line on both versions that is not the same on both.
     *
     * @param line the line number
     * @param what each thing that moved, written {@code rate $60.00 to $55.00}
     */
    public record Changed(int line, List<String> what) implements Change {

        public Changed {
            what = List.copyOf(what);
        }

        @Override
        public String toString() {
            return "line " + line + ": " + String.join(", ", what);
        }
    }

    public Revision {
        changes = List.copyOf(changes);
    }

    /** Compare two versions of the same order. */
    public static Revision between(Order before, Order after) {
        if (!before.number().equals(after.number())) {
            throw new OrderError("order " + before.number() + " cannot be revised into order " + after.number());
        }
        TreeSet<Integer> numbers = new TreeSet<>();
        before.lines().forEach(line -> numbers.add(line.number()));
        after.lines().forEach(line -> numbers.add(line.number()));
        List<Change> changes = new ArrayList<>();
        for (int number : numbers) {
            var was = before.findLine(number);
            var now = after.findLine(number);
            if (was.isEmpty()) {
                changes.add(new Added(now.orElseThrow()));
            } else if (now.isEmpty()) {
                changes.add(new Dropped(was.get()));
            } else {
                List<String> what = differences(was.get(), now.get());
                if (!what.isEmpty()) {
                    changes.add(new Changed(number, what));
                }
            }
        }
        return new Revision(before.number(), changes, before.value(), after.value());
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /** What the rewrite did to the order's value; negative when it came down. */
    public Money valueChange() {
        return now.minus(was);
    }

    /** One line for the audit trail and the confirmation. */
    public String summary() {
        if (isEmpty()) {
            return "no change";
        }
        return changes.stream().map(Change::toString).collect(Collectors.joining("; "));
    }

    private static List<String> differences(Line was, Line now) {
        List<String> what = new ArrayList<>();
        note(what, "station", was.station(), now.station());
        note(what, "daypart", was.daypart(), now.daypart());
        note(what, "class", was.grade().value(), now.grade().value());
        note(what, "length", was.length(), now.length());
        note(what, "rate", was.rate(), now.rate());
        note(what, "flight", was.flight(), now.flight());
        note(what, "copy", String.join(" ", was.carts()), String.join(" ", now.carts()));
        return what;
    }

    private static void note(List<String> what, String name, Object was, Object now) {
        if (!Objects.equals(was, now)) {
            what.add(name + " " + shown(was) + " to " + shown(now));
        }
    }

    private static String shown(Object value) {
        String text = value.toString();
        return text.isEmpty() ? "none" : text;
    }
}
