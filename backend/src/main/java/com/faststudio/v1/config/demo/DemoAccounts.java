package com.faststudio.v1.config.demo;

import com.faststudio.v1.controllers.auth.AuthControllerV1;

import java.util.List;

/** Fictional development-only identities. Seeded only when faststudio.demo=true. */
public final class DemoAccounts {
    private DemoAccounts() {}

    public static final String PASSWORD = "demo1234";

    public static final List<AuthControllerV1.DemoAccount> ALL = List.of(
            new AuthControllerV1.DemoAccount("owner@aurora.test", "Asha Rao", "Organization owner", "Sees everything at Aurora Media; also a read-only analyst at Northwind"),
            new AuthControllerV1.DemoAccount("operator@aurora.test", "Ravi Menon", "Channel operator", "Builds schedules, publishes, goes live"),
            new AuthControllerV1.DemoAccount("content@aurora.test", "Meera Iyer", "Content manager", "Uploads videos, sets rights"),
            new AuthControllerV1.DemoAccount("ads@aurora.test", "Dev Kapoor", "Ad operations", "Runs campaigns and ad breaks"),
            new AuthControllerV1.DemoAccount("analyst@aurora.test", "Lena Fischer", "Analyst (read-only)", "Can look, cannot change anything"),
            new AuthControllerV1.DemoAccount("owner@northwind.test", "Tom Hale", "Owner of another company", "Proves tenants can't see each other"),
            new AuthControllerV1.DemoAccount("partner@partnertv.test", "Sam Lee", "Partner (TV platform)", "Partner TV app: sees only the 2 channels Aurora shares, read-only"),
            new AuthControllerV1.DemoAccount("admin@platform.test", "Platform Admin", "Platform super admin", "Manages customer organizations"));
}
