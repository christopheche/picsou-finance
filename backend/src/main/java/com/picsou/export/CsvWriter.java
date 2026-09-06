package com.picsou.export;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * RFC 4180 CSV writer.
 *
 * Writes a UTF-8 BOM up front so Excel auto-detects the encoding (without
 * the BOM, accents in IBANs/labels render as mojibake on Windows). Quotes
 * fields that contain commas, quotes, CR or LF, and doubles up internal
 * quote characters per RFC 4180.
 *
 * <p>Also neutralises spreadsheet formula injection (OWASP "CSV injection"):
 * a field starting with {@code =}, {@code @}, tab or CR — or with {@code +}/{@code -}
 * unless it is a plain number — is prefixed with a single quote and quoted, so a
 * counterparty-controlled transaction label such as {@code =HYPERLINK(...)} opens
 * as text rather than being evaluated. Plain numbers (the exporters write
 * {@code BigDecimal.toPlainString()}) keep their leading sign untouched. The
 * quote is deliberately not stripped by the importer's {@code CsvReader}: it is
 * a display escape for spreadsheet apps, not part of the data model.
 *
 * Not thread-safe — one instance per ZIP entry.
 */
final class CsvWriter implements Closeable {

    private static final String LINE_END = "\r\n";
    private static final Pattern PLAIN_NUMBER = Pattern.compile("[+-]?[0-9]+(\\.[0-9]+)?");

    private final BufferedWriter writer;
    private boolean bomWritten;

    CsvWriter(OutputStream out) {
        this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    void writeRow(List<String> fields) throws IOException {
        if (!bomWritten) {
            writer.write('﻿');
            bomWritten = true;
        }
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) writer.write(',');
            writer.write(escape(fields.get(i)));
        }
        writer.write(LINE_END);
    }

    void flush() throws IOException {
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.flush();
    }

    private static String escape(String field) {
        if (field == null) return "";
        boolean formula = startsFormula(field);
        if (formula) {
            field = "'" + field;
        }
        boolean needsQuoting = formula
            || field.indexOf(',') >= 0
            || field.indexOf('"') >= 0
            || field.indexOf('\n') >= 0
            || field.indexOf('\r') >= 0;
        if (!needsQuoting) return field;
        return "\"" + field.replace("\"", "\"\"") + "\"";
    }

    /** OWASP CSV-injection triggers; a signed plain number is data, not a formula. */
    static boolean startsFormula(String field) {
        if (field.isEmpty()) return false;
        char c = field.charAt(0);
        if (c == '=' || c == '@' || c == '\t' || c == '\r') return true;
        if (c == '+' || c == '-') return !PLAIN_NUMBER.matcher(field).matches();
        return false;
    }
}
