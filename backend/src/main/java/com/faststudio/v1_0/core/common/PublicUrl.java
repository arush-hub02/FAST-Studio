package com.faststudio.v1_0.core.common;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Guard for outbound requests to addresses that came from outside (ad tags, VAST responses, creative files):
 * http(s) only, and never a loopback, private or link-local host, so an ad server can't make us fetch internal services.
 * ponytail: checked at resolve time; DNS rebinding between check and connect needs a pinned-address HTTP client.
 */
public final class PublicUrl {
    private PublicUrl() {}

    public static URI check(String url) {
        URI u;
        try {
            u = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.bad("Not a valid media/provider address.");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) throw ApiException.bad("Only http(s) addresses are allowed.");
        if (u.getHost() == null || u.getUserInfo() != null) throw ApiException.bad("Use a valid host without embedded credentials.");
        try {
            for (InetAddress a : InetAddress.getAllByName(u.getHost()))
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || a.isMulticastAddress() || (a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc))
                    throw ApiException.bad("The address points at a private network: " + u.getHost());
        } catch (UnknownHostException e) {
            throw ApiException.bad("Unknown host: " + u.getHost());
        }
        return u;
    }
}
