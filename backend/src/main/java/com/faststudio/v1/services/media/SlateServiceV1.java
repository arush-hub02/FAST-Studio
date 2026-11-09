package com.faststudio.v1.services.media;

import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.media.AssetDto.AssetView;
import com.faststudio.v1.repositories.media.AssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * The project's own slate (Ads → Slate): the video its channels show while nothing else can air, instead of the
 * platform's standby screen. Uploaded to FAST Studio, converted once into the tenant's quality levels and kept in R2 only.
 * A new upload takes over once it is ready; removing it brings back the platform standby.
 */
@Service
public class SlateServiceV1 {
    private final AssetRepository assets;
    private final AssetServiceV1 assetService;
    private final Audit audit;

    public SlateServiceV1(AssetRepository assets, AssetServiceV1 assetService, Audit audit) {
        this.assets = assets;
        this.assetService = assetService;
        this.audit = audit;
    }

    /** The project's slates, newest first (the newest ready one airs). */
    public List<AssetView> list(Tenant t) {
        return assets.slates(t.orgId()).stream().map(id -> assetService.get(t, id)).toList();
    }

    public AssetView upload(Tenant t, MultipartFile file) throws IOException {
        t.require(Tenant.Perm.ASSETS_EDIT);
        UUID id = assets.insertSlate(t.orgId());
        try {
            AssetView v = assetService.upload(t, id, file);
            audit.log(t, "slate.upload", "asset", id, "project slate uploaded");
            return v;
        } catch (IOException | RuntimeException e) {
            assets.archive(t.orgId(), id);   // a refused file leaves no half-made slate behind
            throw e;
        }
    }

    /** Removes every project slate: channels go back to the platform's standby screen. */
    public void remove(Tenant t) {
        t.require(Tenant.Perm.ASSETS_EDIT);
        for (UUID id : assets.slates(t.orgId())) assetService.archive(t, id);
        audit.log(t, "slate.remove", "asset", null, "back to the platform standby");
    }
}
