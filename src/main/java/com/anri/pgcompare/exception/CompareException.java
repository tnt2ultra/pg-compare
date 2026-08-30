package com.anri.pgcompare.exception;

/**
 * Единственная ошибка приложения: неполадки подключения, чтения каталога и записи отчёта.
 * Непроверяемое исключение — пробрасывается до CLI, где печатается в stderr и даёт exit code 2.
 */
public class CompareException extends RuntimeException {

    /**
     * Создаёт исключение с готовым сообщением.
     *
     * @param message сообщение для пользователя
     */
    public CompareException(String message) {
        super(message);
    }

    /**
     * Создаёт исключение с сообщением и исходной ошибкой.
     *
     * @param message сообщение для пользователя
     * @param cause   ошибка, ставшая причиной
     */
    public CompareException(String message, Throwable cause) {
        super(message, cause);
    }
}
