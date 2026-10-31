package com.faststudio.ads;

import com.faststudio.catalog.MediaSettings;
import com.faststudio.core.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Viewer applications sign short-lived context on their server; playback never trusts unsigned audience claims. */
@Service
public class ViewerContext {
    private final MediaSettings settings;
    private final Secrets secrets;
    private final JsonMapper json;
    public ViewerContext(MediaSettings settings, Secrets secrets, JsonMapper json) { this.settings=settings; this.secrets=secrets; this.json=json; }
    public record Claims(String org, String sub, List<String> audiences, boolean consent, long iat, long exp) {}
    public record Verified(String key, String audiences, OffsetDateTime until) {
        public static final Verified CONTEXTUAL=new Verified(null,"",null);
    }
    public Verified verify(UUID org, String token) {
        if(token==null || token.isBlank()) return Verified.CONTEXTUAL;
        MediaSettings.Row row=settings.row(org);
        if(row==null || !row.personalizedAds()) return Verified.CONTEXTUAL;
        try {
            if(token.length()>8192 || row.viewerSecret()==null) throw new IllegalArgumentException();
            String[] parts=token.split("\\.",-1);
            if(parts.length!=2) throw new IllegalArgumentException();
            String key=secrets.open(row.viewerSecret());
            if(!MessageDigest.isEqual(hmac(key,parts[0]),Base64.getUrlDecoder().decode(parts[1]))) throw new IllegalArgumentException();
            Claims c=json.readValue(Base64.getUrlDecoder().decode(parts[0]),Claims.class);
            long now=Instant.now().getEpochSecond();
            if(!org.toString().equals(c.org()) || c.sub()==null || c.sub().isBlank() || c.sub().length()>200
                    || c.iat()>now+30 || c.iat()<now-3600 || c.exp()<=now || c.exp()>c.iat()+3600 || c.exp()<=c.iat()) throw new IllegalArgumentException();
            if(!c.consent()) return Verified.CONTEXTUAL;
            String audiences=audiences(c.audiences());
            String viewer=HexFormat.of().formatHex(hmac(key,"viewer:"+org+":"+c.sub()));
            return new Verified(viewer,audiences,Instant.ofEpochSecond(c.exp()).atOffset(ZoneOffset.UTC));
        } catch(Exception e) { throw new ApiException(HttpStatus.FORBIDDEN,"INVALID_VIEWER_CONTEXT","Viewer context is invalid or expired. Obtain a new token from your viewer application."); }
    }
    public static String audiences(List<String> values) {
        if(values==null) return "";
        if(values.size()>32 || values.stream().anyMatch(v->v==null || !v.matches("[A-Za-z][A-Za-z0-9_-]{0,39}"))) throw ApiException.bad("Use at most 32 audience keys with letters, numbers, underscores or hyphens.");
        return String.join(",",values.stream().map(v->v.toUpperCase(Locale.ROOT)).distinct().sorted().toList());
    }
    static byte[] hmac(String key,String value) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
}
