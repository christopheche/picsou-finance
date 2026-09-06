package com.picsou.export;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    private String write(List<String> header, List<List<String>> rows) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             CsvWriter csv = new CsvWriter(baos)) {
            csv.writeRow(header);
            for (List<String> row : rows) csv.writeRow(row);
            csv.flush();
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    private static final String BOM = "﻿";

    @Test
    void writesPlainRows_withCrlfLineEndings() throws Exception {
        String out = write(List.of("a", "b"), List.of(List.of("1", "2"), List.of("3", "4")));
        assertThat(out).isEqualTo(BOM + "a,b\r\n1,2\r\n3,4\r\n");
    }

    @Test
    void quotesFieldsContainingComma() throws Exception {
        String out = write(List.of("a", "b"), List.of(List.of("hello, world", "x")));
        assertThat(out).contains("\"hello, world\",x\r\n");
    }

    @Test
    void quotesFieldsContainingQuotes_andDoublesInternalQuotes() throws Exception {
        String out = write(List.of("a"), List.of(List.of("she said \"hi\"")));
        assertThat(out).contains("\"she said \"\"hi\"\"\"\r\n");
    }

    @Test
    void quotesFieldsContainingNewlines() throws Exception {
        String out = write(List.of("a"), List.of(List.of("line1\nline2")));
        assertThat(out).contains("\"line1\nline2\"\r\n");
    }

    @Test
    void writesNullAsEmpty() throws Exception {
        String out = write(List.of("a", "b"), List.of(java.util.Arrays.asList(null, "x")));
        assertThat(out).isEqualTo(BOM + "a,b\r\n,x\r\n");
    }

    @Test
    void emitsBomForUtf8ExcelCompat() throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             CsvWriter csv = new CsvWriter(baos)) {
            csv.writeRow(List.of("éà", "ü"));
            csv.flush();
            byte[] bytes = baos.toByteArray();
            assertThat(bytes[0]).isEqualTo((byte) 0xEF);
            assertThat(bytes[1]).isEqualTo((byte) 0xBB);
            assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        }
    }

    // --- Formula injection (OWASP CSV injection) ------------------------------------------

    @Test
    void neutralisesLeadingEqualsSign() throws Exception {
        String out = write(List.of("label"), List.of(List.of("=HYPERLINK(\"http://evil\";\"click\")")));
        assertThat(out).contains("\"'=HYPERLINK(\"\"http://evil\"\";\"\"click\"\")\"\r\n");
    }

    @Test
    void neutralisesLeadingAtTabAndCr() throws Exception {
        String out = write(List.of("a", "b", "c"),
            List.of(List.of("@SUM(A1)", "\tcmd", "\rcmd")));
        assertThat(out).contains("\"'@SUM(A1)\",\"'\tcmd\",\"'\rcmd\"\r\n");
    }

    @Test
    void neutralisesSignedFormulas_butKeepsSignedNumbers() throws Exception {
        String out = write(List.of("amount", "label", "label2"),
            List.of(List.of("-853.00", "-2+3+cmd|' /C calc'!A0", "+cmd|' /C calc'!A0")));
        assertThat(out).contains("-853.00,\"'-2+3+cmd|' /C calc'!A0\",\"'+cmd|' /C calc'!A0\"\r\n");
    }

    @Test
    void startsFormula_signedPlainNumbersAreData() {
        assertThat(CsvWriter.startsFormula("-1")).isFalse();
        assertThat(CsvWriter.startsFormula("+12.50")).isFalse();
        assertThat(CsvWriter.startsFormula("-0.00000001")).isFalse();
        assertThat(CsvWriter.startsFormula("")).isFalse();
        assertThat(CsvWriter.startsFormula("-1E+3")).isTrue();
        assertThat(CsvWriter.startsFormula("=1")).isTrue();
    }
}
