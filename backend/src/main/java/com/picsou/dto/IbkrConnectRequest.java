package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for storing Interactive Brokers Flex Web Service credentials.
 *
 * <p>The {@code @Size} bounds are on the <em>plaintext</em> while {@code ibkr_connection.token}
 * and {@code .query_id} hold the AES-GCM ciphertext: Base64 turns n bytes into roughly
 * {@code 4/3 * (n + 28)} characters, so 200 &rarr; ~304, inside {@code varchar(500)}. Raising
 * either bound without widening the column turns a long-but-valid credential into a 500 at
 * INSERT — the same trap documented on {@code CryptoExchangeController.AddExchangeRequest}.
 * Real Flex tokens are ~20 digits and query ids ~7, so 200 is already generous.
 *
 * @param token   Flex Web Service token generated in Client Portal (read-only, 6h–1y)
 * @param queryId id of the pre-built "Open Positions" Flex Query
 */
public record IbkrConnectRequest(
    @NotBlank @Size(max = 200) String token,
    @NotBlank @Size(max = 200) String queryId
) {}
