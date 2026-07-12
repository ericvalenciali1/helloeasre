package org.example.guardsquare

/**
 * Thrown for any failure encountered while running iXGuard or DexGuard.
 */
class GuardsquareException extends Exception {
    GuardsquareException(String message) {
        super(message)
    }

    GuardsquareException(String message, Throwable cause) {
        super(message, cause)
    }
}
