package com.faststudio.v1.core.common;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

/** Outbound address guard: which hosts an ad server or a pasted media URL may make us contact. */
class PublicUrlTest {
    static boolean pub(String ip) throws Exception { return PublicUrl.isPublic(InetAddress.getByName(ip)); }

    @Test
    void privateAndSpecialAddressesAreBlocked() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "0.1.2.3", "100.64.0.1",
                "100.127.255.254", "192.0.0.170", "198.18.0.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
                "::1", "fc00::1", "fd12::1", "fe80::1", "fec0::1", "::ffff:10.0.0.1", "64:ff9b::a00:1", "64:ff9b::7f00:1"})
            assertFalse(pub(ip), ip);
    }

    @Test
    void publicAddressesAreAllowed() throws Exception {
        for (String ip : new String[]{"8.8.8.8", "1.1.1.1", "100.128.0.1", "198.20.0.1", "2606:4700:4700::1111", "64:ff9b::808:808"})
            assertTrue(pub(ip), ip);
    }

    @Test
    void onlyPlainHttpUrlsToPublicHostsPass() {
        assertThrows(ApiException.class, () -> PublicUrl.check("file:///etc/passwd"));
        assertThrows(ApiException.class, () -> PublicUrl.check("http://user:pw@example.com/"));
        assertThrows(ApiException.class, () -> PublicUrl.check("http://169.254.169.254/latest/meta-data/"));
        assertThrows(ApiException.class, () -> PublicUrl.check("http://[::ffff:127.0.0.1]/"));
    }
}
