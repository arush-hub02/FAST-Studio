package com.faststudio.v1.core.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Stamps {@link TenantScope} onto each connection as it is borrowed from the pool, so PostgreSQL row-level
 * security sees the right tenant. Every checkout overwrites both settings, so nothing leaks between borrowers.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return stamp(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return stamp(super.getConnection(username, password));
    }

    private static Connection stamp(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.org_id', ?, false), set_config('app.bypass', ?, false)")) {
            ps.setString(1, TenantScope.org() == null ? "" : TenantScope.org().toString());
            ps.setString(2, TenantScope.bypassing() ? "on" : "off");
            ps.execute();
            return c;
        } catch (SQLException e) {
            c.close();
            throw e;
        }
    }
}
