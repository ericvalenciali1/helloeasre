package org.example.zshield

/**
 * Thrown for any failure encountered while running zShield actions
 * against a .nwproj file.
 */
class ZShieldException extends Exception {
    ZShieldException(String message) {
        super(message)
    }

    ZShieldException(String message, Throwable cause) {
        super(message, cause)
    }
}
