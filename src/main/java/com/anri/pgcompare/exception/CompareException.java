package com.anri.pgcompare.exception;

public class CompareException extends RuntimeException {

    public CompareException(String message) {
        super(message);
    }

    public CompareException(String message, Throwable cause) {
        super(message, cause);
    }
}
