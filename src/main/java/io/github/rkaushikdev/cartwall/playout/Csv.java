package io.github.rkaushikdev.cartwall.playout;

import java.util.ArrayList;
import java.util.List;

/**
 * Delimited values, read the way the automation exports write them: double quotes around a field
 * that holds the delimiter, a doubled quote for a quote, and a blank line skipped rather than read
 * as an empty row. The main automation separates with commas and the backup playout with tabs;
 * nothing else about the two differs at this level.
 */
final class Csv {

    private Csv() {}

    /** Every non-blank comma separated record in {@code text}, each a list of its fields. */
    static List<List<String>> records(String text) {
        return records(text, ',');
    }

    /** Every non-blank record in {@code text} split on {@code delimiter}, each a list of its fields. */
    static List<List<String>> records(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        int index = 0;
        while (index < text.length()) {
            char next = text.charAt(index);
            if (quoted) {
                if (next == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    field.append('"');
                    index += 2;
                    continue;
                }
                if (next == '"') {
                    quoted = false;
                } else {
                    field.append(next);
                }
            } else if (next == '"' && field.isEmpty()) {
                quoted = true;
                started = true;
            } else if (next == delimiter) {
                fields.add(field.toString());
                field.setLength(0);
                started = true;
            } else if (next == '\r' || next == '\n') {
                if (next == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                finish(records, fields, field, started);
                fields = new ArrayList<>();
                started = false;
            } else {
                field.append(next);
                started = true;
            }
            index++;
        }
        finish(records, fields, field, started);
        return records;
    }

    private static void finish(List<List<String>> records, List<String> fields, StringBuilder field, boolean started) {
        if (started) {
            fields.add(field.toString());
            records.add(List.copyOf(fields));
        }
        field.setLength(0);
    }
}
