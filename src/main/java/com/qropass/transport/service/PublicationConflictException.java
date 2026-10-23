package com.qropass.transport.service;

/** Конфликт состояния или ожидаемого содержимого при публикации. */
public final class PublicationConflictException extends RuntimeException {
    /** Создаёт конфликт с описанием причины. */
    public PublicationConflictException(String message) { super(message); }
}
