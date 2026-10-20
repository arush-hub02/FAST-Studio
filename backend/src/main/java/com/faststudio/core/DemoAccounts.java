package com.faststudio.core;

import java.util.List;

/** Fictional development-only identities. Seeded only when faststudio.demo=true. */
public final class DemoAccounts {
    private DemoAccounts() {}

    public static final String PASSWORD = "demo1234";

    public static final List<AuthController.DemoAccount> ALL = List.of(
            new AuthController.DemoAccount("owner@aurora.test", "Asha Rao", "Organization owner", "Sees everything at Aurora Media; also a read-only analyst at Northwind"),
            new AuthController.DemoAccount("operator@aurora.test", "Ravi Menon", "Channel operator", "Builds schedules, publishes, goes live"),
            new AuthController.DemoAccount("content@aurora.test", "Meera Iyer", "Content manager", "Uploads videos, sets rights"),
            new AuthController.DemoAccount("ads@aurora.test", "Dev Kapoor", "Ad operations", "Runs campaigns and ad breaks"),
            new AuthController.DemoAccount("analyst@aurora.test", "Lena Fischer", "Analyst (read-only)", "Can look, cannot change anything"),
            new AuthController.DemoAccount("owner@northwind.test", "Tom Hale", "Owner of another company", "Proves tenants can't see each other"),
            new AuthController.DemoAccount("partner@partnertv.test", "Sam Lee", "Partner (TV platform)", "Partner TV app: sees only the 2 channels Aurora shares, read-only"),
            new AuthController.DemoAccount("admin@platform.test", "Platform Admin", "Platform super admin", "Manages customer organizations"));
}
