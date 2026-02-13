package com.moyuan.sar.data;

public class JavaDataPlaneValidationException extends RuntimeException {
    private final String field;

    public JavaDataPlaneValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
