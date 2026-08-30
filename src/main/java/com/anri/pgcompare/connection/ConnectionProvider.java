package com.anri.pgcompare.connection;

import com.anri.pgcompare.exception.CompareException;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Открывает одноразовые JDBC-подключения для CLI-режима: пул не нужен, процесс живёт секунды.
 */
@Component
public class ConnectionProvider {

    /**
     * Подключение сразу переводится в read-only: утилита только читает {@code pg_catalog},
     * и защита от случайной записи дешевле, чем разбор «кто изменил базу» после диффа.
     *
     * @param url      JDBC URL базы
     * @param user     имя пользователя
     * @param password пароль; {@code null} драйверу не передаётся — это доверяет аутентификацию
     *                 окружению (trust, .pgpass, GSSAPI и т. п.)
     * @return открытое подключение; закрывает его вызывающий код
     * @throws CompareException если подключение не установилось
     */
    public Connection open(String url, String user, String password) {
        Properties props = new Properties();
        props.setProperty("user", user);
        if (password != null) {
            props.setProperty("password", password);
        }
        try {
            Connection connection = DriverManager.getConnection(url, props);
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException e) {
            throw new CompareException("Не удалось подключиться к %s под пользователем %s: %s"
                    .formatted(url, user, e.getMessage()), e);
        }
    }
}
