package ru.rea.report.exception;

public class BadTemplateException extends RuntimeException {
    public BadTemplateException(String message) { super(message); }
    public BadTemplateException(String message, Throwable cause) { super(message, cause); }
}
