package com.faststudio.v1.controllers.platform.tenancy;

import com.faststudio.v1.dto.tenancy.CapabilityDto.*;
import com.faststudio.v1.services.tenancy.CapabilityServiceV1;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Platform administration of the global features and permissions catalogue. */
@RestController
@RequestMapping("/api/platform/capabilities")
public class CapabilityControllerV1 {
    private final CapabilityServiceV1 service;

    public CapabilityControllerV1(CapabilityServiceV1 service) { this.service = service; }

    @GetMapping
    List<Capability> list() { return service.global(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Capability create(@Valid @RequestBody CreateRequest r) { return service.createGlobal(r); }

    @PutMapping("/{key}")
    Capability update(@PathVariable String key, @Valid @RequestBody UpdateRequest r) { return service.updateGlobal(key, r); }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String key) { service.deleteGlobal(key); }
}
