package com.anri.pgcompare.connection;

import com.anri.pgcompare.exception.CompareException;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Creates one-shot JDBC connections for CLI use; no pool — the process lives seconds.
 */
@Component
public class ConnectionProvider {

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
            throw new CompareException("Cannot connect to %s as %s: %s".formatted(url, user, e.getMessage()), e);
        }
    }
}
