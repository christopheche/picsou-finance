package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one mapping rule that decides whether a DEGIRO position can be keyed as a holding at
 * all: a sidecar field that is absent, JSON {@code null} or blank must reach the service as
 * {@code null}. Jackson's {@code asText()} answers {@code "null"} for a JSON null and {@code ""}
 * for a missing key, and both used to travel on as a ticker.
 */
class DegiroAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void textOrNull_isNullForAJsonNull_notTheStringNull() throws Exception {
        // What the sidecar sends for a symbol it sanitised away (DEGIRO's literal "NULL").
        JsonNode position = MAPPER.readTree("{\"symbol\": null}");

        assertThat(DegiroAdapter.textOrNull(position.path("symbol"))).isNull();
    }

    @Test
    void textOrNull_isNullForAMissingOrBlankField() throws Exception {
        JsonNode position = MAPPER.readTree("{\"name\": \"  \"}");

        assertThat(DegiroAdapter.textOrNull(position.path("symbol"))).isNull();
        assertThat(DegiroAdapter.textOrNull(position.path("name"))).isNull();
    }

    @Test
    void textOrNull_keepsARealValue() throws Exception {
        JsonNode position = MAPPER.readTree("{\"symbol\": \"IWDA\"}");

        assertThat(DegiroAdapter.textOrNull(position.path("symbol"))).isEqualTo("IWDA");
    }
}
