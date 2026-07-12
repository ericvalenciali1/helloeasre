package org.example.intune

/**
 * Thrown for any failure encountered while wrapping an app with the
 * Microsoft Intune App Wrapping Tool (iOS or Android).
 */
class IntuneWrapperException extends Exception {
    IntuneWrapperException(String message) {
        super(message)
    }

    IntuneWrapperException(String message, Throwable cause) {
        super(message, cause)
    }
}
