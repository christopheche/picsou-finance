package com.picsou.exception;

/**
 * Thrown when operator-supplied key material (an Enable Banking private-key PEM)
 * cannot be used: wrong container format, wrong algorithm, undecodable body.
 *
 * <p>Mapped to {@code 422 Unprocessable Entity} by {@code GlobalExceptionHandler}.
 * The message is fixed, operator-facing English; the parsing cause travels as the
 * exception cause and is logged, never concatenated into the message.
 */
public class InvalidKeyMaterialException extends RuntimeException {

    public InvalidKeyMaterialException(String message) {
        super(message);
    }

    public InvalidKeyMaterialException(String message, Throwable cause) {
        super(message, cause);
    }
}
