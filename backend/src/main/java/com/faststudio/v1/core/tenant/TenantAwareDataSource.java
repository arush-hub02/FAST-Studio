package com.faststudio.v1.core.tenant;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Hands out connections for the current {@link TenantScope}. Request work gets a connection from the restricted app
 * pool with the organization stamped on it, so PostgreSQL row-level security only shows that organization's rows
 * (every checkout overwrites it, so nothing leaks between borrowers). Work inside {@link TenantScope#bypass} gets a
 * connection from the system pool, whose login the row policies trust (migration V18); request connections can't
 * turn into it.
 */
public class TenantAwareDataSource extends AbstractDataSource implements AutoCloseable {
    private final DataSource app, system;

    public TenantAwareDataSource(DataSource app, DataSource system) {
        this.app = app;
        this.system = system;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return TenantScope.bypassing() ? system.getConnection() : stamp(app.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) {
        throw new UnsupportedOperationException("connections use the pool logins");
    }

    @Override
    public void close() throws Exception {
        for (DataSource d : new DataSource[]{app, system}) if (d instanceof AutoCloseable c) c.close();
    }

    private static Connection stamp(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.org_id', ?, false)")) {
            ps.setString(1, TenantScope.org() == null ? "" : TenantScope.org().toString());
            ps.execute();
            return c;
        } catch (SQLException e) {
            c.close();
            throw e;
        }
    }
}
