package io.github.rkaushikdev.cartwall.reports;

import java.util.ArrayList;
import java.util.List;

/**
 * The layout every piece of house paperwork is typed in: a heading, the facts down a labelled
 * column, then whatever the document is about.
 *
 * <p>Agencies file the affidavits, the confirmations and the political file entries side by side
 * and read them by eye, so the label column is the same width on all of them and the label is
 * always capitals. A document that drifts a space off the others is the one somebody calls about.
 */
final class Form {

    /** Wide enough for {@code DISPOSITION} and two spaces after it. */
    static final int LABEL_WIDTH = 13;

    private final List<String> lines = new ArrayList<>();

    /** A form that opens with nothing; the statement starts straight into its fields. */
    Form() {}

    /** A form that opens with its title and a blank line under it. */
    Form(String title) {
        lines.add(title);
        lines.add("");
    }

    /** One fact in the label column. */
    Form field(String label, Object value) {
        lines.add(labelled(label) + value);
        return this;
    }

    /** One fact, only when there is something to say. */
    Form fieldIfAny(String label, String value) {
        return value.isEmpty() ? this : field(label, value);
    }

    /** The same label once for each value, which is how repeated rows are typed. */
    Form fields(String label, List<?> values) {
        values.forEach(value -> field(label, value));
        return this;
    }

    /** A line of prose, flush left. */
    Form text(String text) {
        lines.add(text);
        return this;
    }

    /** A row of a list, indented under whatever introduced it. */
    Form row(Object row) {
        lines.add("  " + row);
        return this;
    }

    /** Every row of a list, or one note when there are none. */
    Form rows(List<?> rows, String whenEmpty) {
        rows.forEach(this::row);
        if (rows.isEmpty()) {
            row(whenEmpty);
        }
        return this;
    }

    Form blank() {
        lines.add("");
        return this;
    }

    /** The document, a newline after every line. */
    String render() {
        return String.join("\n", lines) + "\n";
    }

    private static String labelled(String label) {
        return ("%-" + LABEL_WIDTH + "s").formatted(label);
    }
}
