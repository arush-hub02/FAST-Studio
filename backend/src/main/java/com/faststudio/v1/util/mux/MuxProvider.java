package com.faststudio.v1.util.mux;

import com.faststudio.v1.util.mediaProvider.Capability;
import com.faststudio.v1.util.mediaProvider.IDirectUpload;
import com.faststudio.v1.util.mediaProvider.IMediaProvider;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/** Mux as a media provider: the neutral catalogue and direct-upload calls, translated to {@link MuxClientService}. */
@Component
public class MuxProvider implements IMediaProvider, IDirectUpload {
    public static final String KEY = "mux";
    private final MuxClientService mux;

    public MuxProvider(MuxClientService mux) { this.mux = mux; }

    @Override
    public String key() { return KEY; }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.CATALOGUE, Capability.DIRECT_UPLOAD, Capability.SOURCE_FILE, Capability.WEBHOOKS);
    }

    static MuxClientService.Account account(Credentials c) { return new MuxClientService.Account(c.id(), c.secret(), null, "", c.scope()); }

    /** A Mux asset in neutral terms (listings and webhook payloads). */
    public static RemoteAsset normalize(MuxClientService.Asset x) {
        String status = switch (x.status()) {
            case "ready" -> "READY";
            case "errored" -> "ERRORED";
            default -> "PREPARING";
        };
        return new RemoteAsset(x.id(), status, x.title(), x.durationMs(), x.uploadId());
    }

    @Override
    public void check(Credentials c) throws InterruptedException { mux.page(account(c), null); }

    @Override
    public Page page(Credentials c, String checkpoint) throws InterruptedException {
        MuxClientService.Page p = mux.page(account(c), checkpoint);
        return new Page(p.assets().stream().map(MuxProvider::normalize).toList(), p.next());
    }

    @Override
    public Upload upload(Credentials c, String corsOrigin, String title) throws InterruptedException {
        MuxClientService.Upload u = mux.upload(account(c), corsOrigin, title);
        return new Upload(u.id(), u.url());
    }
}
