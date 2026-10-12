package com.fintech.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Wrapper;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Counts JDBC statement executions so tests can fail an N+1 regression. */
public class CountingDataSource extends DelegatingDataSource {

    private static final AtomicInteger QUERIES = new AtomicInteger();

    public CountingDataSource(DataSource target) {
        super(target);
    }

    public static void reset() {
        QUERIES.set(0);
    }

    public static int count() {
        return QUERIES.get();
    }

    public static void assertAtMost(int max) {
        int actual = count();
        if (actual > max) {
            throw new AssertionError("Expected at most " + max + " SQL statements, ran " + actual);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(Connection.class, super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(Connection.class, super.getConnection(username, password));
    }

    @SuppressWarnings("unchecked")
    private static <T> T wrap(Class<T> type, T delegate) {
        return (T) Proxy.newProxyInstance(CountingDataSource.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "counting[" + delegate + "]";
                        };
                    }
                    if ("unwrap".equals(method.getName())) {
                        Class<?> iface = (Class<?>) args[0];
                        if (iface.isInstance(delegate)) {
                            return delegate;
                        }
                        if (delegate instanceof Wrapper wrapper) {
                            return wrapper.unwrap(iface);
                        }
                        throw new SQLException("Not a wrapper for " + iface.getName());
                    }
                    if ("isWrapperFor".equals(method.getName())) {
                        Class<?> iface = (Class<?>) args[0];
                        if (iface.isInstance(delegate)) {
                            return true;
                        }
                        return delegate instanceof Wrapper wrapper && wrapper.isWrapperFor(iface);
                    }
                    if (method.getName().startsWith("execute")) {
                        QUERIES.incrementAndGet();
                    }
                    final Object result;
                    try {
                        result = method.invoke(delegate, args);
                    } catch (InvocationTargetException ex) {
                        Throwable cause = ex.getCause();
                        if (cause instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        if (cause instanceof Error error) {
                            throw error;
                        }
                        if (cause instanceof SQLException sql) {
                            throw sql;
                        }
                        throw ex;
                    }
                    if (result instanceof PreparedStatement statement) {
                        return wrap(PreparedStatement.class, statement);
                    }
                    if (result instanceof CallableStatement statement) {
                        return wrap(CallableStatement.class, statement);
                    }
                    if (result instanceof Statement statement) {
                        return wrap(Statement.class, statement);
                    }
                    return result;
                });
    }
}
