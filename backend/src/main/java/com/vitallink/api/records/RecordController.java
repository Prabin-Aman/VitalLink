package com.vitallink.api.records;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.records.RecordDtos.DownloadOut;
import com.vitallink.api.records.RecordDtos.HealthRecord;
import com.vitallink.api.records.RecordDtos.LabPanel;
import com.vitallink.api.records.RecordDtos.LabPanelIn;
import com.vitallink.api.records.RecordDtos.LatestLab;
import com.vitallink.api.records.RecordDtos.RecordIn;
import com.vitallink.api.records.RecordDtos.RecordType;
import com.vitallink.api.records.RecordDtos.RecordsOverview;
import com.vitallink.api.records.RecordDtos.UploadIn;
import com.vitallink.api.records.RecordDtos.UploadOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Medical records, their files through signed links, and lab results. */
@RestController
@RequestMapping("/v1/me")
@Tag(name = "records")
public class RecordController {

    private final RecordService service;

    public RecordController(RecordService service) {
        this.service = service;
    }

    @GetMapping("/records/overview")
    @Operation(summary = "Counts by type, the 6 most recent records, and the live shares.")
    public RecordsOverview overview(CurrentUser me) {
        return service.overview(me.id(), me.user().timezone());
    }

    @GetMapping("/records")
    public List<HealthRecord> list(CurrentUser me, @RequestParam(required = false) RecordType type) {
        return service.list(me.id(), me.user().timezone(), type);
    }

    @PostMapping("/records")
    @ResponseStatus(HttpStatus.CREATED)
    public HealthRecord create(CurrentUser me, @Valid @RequestBody RecordIn body) {
        return service.create(me, body);
    }

    @GetMapping("/records/{id}")
    public HealthRecord get(CurrentUser me, @PathVariable UUID id) {
        return service.get(me.id(), me.user().timezone(), id);
    }

    @PatchMapping("/records/{id}")
    public HealthRecord update(CurrentUser me, @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return service.update(me, id, body);
    }

    @DeleteMapping("/records/{id}")
    @Operation(summary = "Deletes the record and its stored file.")
    public ResponseEntity<Void> delete(CurrentUser me, @PathVariable UUID id) {
        service.delete(me, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/records/{id}/upload")
    @Operation(summary = "A signed URL (5 min) to PUT the file to; the record's file is pending until confirmed.")
    public UploadOut upload(CurrentUser me, @PathVariable UUID id, @Valid @RequestBody UploadIn body) {
        return service.startUpload(me, id, body);
    }

    @PostMapping("/records/{id}/confirm")
    @Operation(summary = "The server checks the stored file (type, size); it becomes attached.")
    public HealthRecord confirm(CurrentUser me, @PathVariable UUID id) {
        return service.confirmUpload(me, id);
    }

    @GetMapping("/records/{id}/download")
    @Operation(summary = "A signed download URL (5 min) for an attached file.")
    public DownloadOut download(CurrentUser me, @PathVariable UUID id) {
        return service.download(me.id(), id);
    }

    // ---------------------------------------------------------------- labs

    @GetMapping("/labs")
    @Operation(summary = "Lab panels, newest first; each result has the previous value of the same test.")
    public List<LabPanel> labs(CurrentUser me) {
        return service.labs(me.id(), me.user().timezone());
    }

    @PostMapping("/labs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Type in a lab report. Blood type and blood group results are refused.")
    public LabPanel createLab(CurrentUser me, @Valid @RequestBody LabPanelIn body) {
        return service.createLab(me, body);
    }

    @GetMapping("/labs/latest-result")
    @Operation(summary = "The latest panel's first result (dashboard card); 404 when there are no labs.")
    public LatestLab latestResult(CurrentUser me) {
        return service.latestResult(me.id(), me.user().timezone())
                .orElseThrow(() -> new ApiError(404, "no_labs", "No lab results yet."));
    }

    @GetMapping("/labs/{id}")
    public LabPanel lab(CurrentUser me, @PathVariable UUID id) {
        return service.lab(me.id(), me.user().timezone(), id);
    }

    @PutMapping("/labs/{id}")
    @Operation(summary = "Replace a panel's details and results.")
    public LabPanel replaceLab(CurrentUser me, @PathVariable UUID id, @Valid @RequestBody LabPanelIn body) {
        return service.replaceLab(me, id, body);
    }

    @DeleteMapping("/labs/{id}")
    public ResponseEntity<Void> deleteLab(CurrentUser me, @PathVariable UUID id) {
        service.deleteLab(me, id);
        return ResponseEntity.noContent().build();
    }
}
