package com.faststudio.v1.core.common;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Function;

/**
 * Guard for outbound requests to addresses that came from outside (ad tags, VAST responses, creative and media files):
 * http(s) only, and never a loopback, private, link-local, carrier-grade NAT or other non-public host, so an ad server or
 * a pasted media URL can't make us fetch internal services or cloud metadata. Redirects are followed by hand with
 * {@link #send}, checking every hop.
 * ponytail: checked at resolve time; DNS rebinding between check and connect needs a pinned-address HTTP client.
 */
public final class PublicUrl {
    private PublicUrl() {}

    /** Redirect hops followed before giving up. */
    static final int MAX_REDIRECTS = 5;

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
                if (!isPublic(a)) throw ApiException.bad("The address points at a private network: " + u.getHost());
        } catch (UnknownHostException e) {
            throw ApiException.bad("Unknown host: " + u.getHost());
        }
        return u;
    }

    static boolean isPublic(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress())
            return false;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff, second = b[1] & 0xff;
            return first != 0                                        // 0.0.0.0/8 "this network"
                    && !(first == 100 && (second & 0xc0) == 64)      // 100.64.0.0/10 carrier-grade NAT (also some cloud internals)
                    && !(first == 192 && second == 0 && (b[2] & 0xff) == 0)   // 192.0.0.0/24 protocol assignments
                    && !(first == 198 && (second & 0xfe) == 18)      // 198.18.0.0/15 benchmarking
                    && first < 224;                                  // 224/4 multicast and 240/4 reserved, broadcast
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return false;                 // fc00::/7 unique local
            if ((b[0] & 0xff) == 0xfe && (b[1] & 0xc0) == 0xc0) return false;   // fec0::/10 old site-local
            boolean v4Embedded = isZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff   // ::ffff:a.b.c.d
                    || (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;   // 64:ff9b::/96 NAT64
            if (v4Embedded) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) if (b[i] != 0) return false;
        return true;
    }

    /**
     * Sends a request and follows up to {@value #MAX_REDIRECTS} redirects itself, checking every address with
     * {@link #check}. The client must be built with {@code HttpClient.Redirect.NEVER}, or redirects would skip the check.
     */
    public static <T> HttpResponse<T> send(HttpClient http, String url, Function<URI, HttpRequest> request,
                                           HttpResponse.BodyHandler<T> body) throws IOException, InterruptedException {
        URI next = check(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<T> res = http.send(request.apply(next), body);
            int s = res.statusCode();
            var location = res.headers().firstValue("Location");
            if (s / 100 != 3 || s == 304 || location.isEmpty()) return res;
            next = check(next.resolve(location.get().trim()).toString());
        }
        throw new IOException("too many redirects");
    }

    /** The address a URL finally redirects to (every hop checked), for tools such as ffmpeg that fetch by themselves. */
    public static URI resolve(HttpClient http, String url, java.time.Duration timeout) throws IOException, InterruptedException {
        HttpResponse<Void> res = send(http, url, u -> HttpRequest.newBuilder(u).timeout(timeout).header("Range", "bytes=0-0").GET().build(),
                HttpResponse.BodyHandlers.discarding());
        return res.request().uri();
    }
}
