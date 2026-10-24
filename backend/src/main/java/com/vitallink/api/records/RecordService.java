package com.vitallink.api.records;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Clock;
import com.vitallink.api.core.Ids;
import com.vitallink.api.core.audit.Audit.Scope;
import com.vitallink.api.core.storage.FileStorage;
import com.vitallink.api.core.userdata.UserData;
import com.vitallink.api.core.web.Patch;
import com.vitallink.api.notifications.NotificationService;
import com.vitallink.api.notifications.NotificationService.Kind;
import com.vitallink.api.notifications.NotificationService.Message;
import com.vitallink.api.records.RecordDtos.DownloadOut;
import com.vitallink.api.records.RecordDtos.FileInfo;
import com.vitallink.api.records.RecordDtos.HealthRecord;
import com.vitallink.api.records.RecordDtos.Interpretation;
import com.vitallink.api.records.RecordDtos.LabFile;
import com.vitallink.api.records.RecordDtos.LabPanel;
import com.vitallink.api.records.RecordDtos.LabPanelIn;
import com.vitallink.api.records.RecordDtos.LabResult;
import com.vitallink.api.records.RecordDtos.LabResultIn;
import com.vitallink.api.records.RecordDtos.LatestLab;
import com.vitallink.api.records.RecordDtos.RecordIn;
import com.vitallink.api.records.RecordDtos.RecordShare;
import com.vitallink.api.records.RecordDtos.RecordType;
import com.vitallink.api.records.RecordDtos.RecordsOverview;
import com.vitallink.api.records.RecordDtos.UploadIn;
import com.vitallink.api.records.RecordDtos.UploadOut;
import com.vitallink.api.sharing.SharingService;
import com.vitallink.api.sharing.SharingService.ConsentRow;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records (metadata plus one optional stored file) and lab panels (docs/07 §7.4).
 *
 * <p>Upload flow: {@code POST /upload} reserves a storage key and returns a short-lived signed URL (status
 * pending); the client PUTs the file there; {@code POST /confirm} has the server look at what was stored,
 * and only a PDF or image of at most 25 MB becomes attached. Downloads are signed URLs for attached files.
 */
@Service
public class RecordService implements UserData {

    private static final Logger log = LoggerFactory.getLogger(RecordService.class);
    static final Set<String> ALLOWED_TYPES = Set.of("application/pdf", "image/jpeg", "image/png", "image/heic");
    static final long MAX_BYTES = 25L * 1024 * 1024;
    /** Blood-group tests are a blood feature: refused (hard constraint). */
    public static final Pattern BLOOD_GROUP =
            Pattern.compile("(?i)\\b(abo|rh(d)?|rhesus|blood\\s*(type|group)|type\\s*and\\s*screen)\\b");

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final SharingService sharing;
    private final NotificationService notifications;

    public RecordService(
            JdbcClient jdbc, FileStorage storage, SharingService sharing, NotificationService notifications) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.storage = storage;
        this.sharing = sharing;
    }

    // ---------------------------------------------------------------- rows

    record RecordRow(
            UUID id,
            RecordType type,
            String title,
            String source,
            String note,
            LocalDate date,
            String fileStatus,
            String fileKey,
            String fileName,
            Integer fileSize,
            String fileType,
            UUID panelId) {}

    private static RecordRow recordRow(ResultSet rs, int i) throws SQLException {
        int size = rs.getInt("file_size");
        Integer fileSize = rs.wasNull() ? null : size;
        return new RecordRow(
                rs.getObject("id", UUID.class),
                RecordType.valueOf(rs.getString("type")),
                rs.getString("title"),
                rs.getString("source"),
                rs.getString("note"),
                rs.getObject("record_date", LocalDate.class),
                rs.getString("file_status"),
                rs.getString("file_key"),
                rs.getString("file_name"),
                fileSize,
                rs.getString("file_type"),
                rs.getObject("panel_id", UUID.class));
    }

    private static final String RECORD_SELECT = """
            SELECT r.*, p.id AS panel_id FROM vitallink.health_records r
            LEFT JOIN vitallink.lab_panels p ON p.record_id = r.id
            """;

    private List<RecordRow> records(UUID userId, RecordType type) {
        var q = jdbc.sql(RECORD_SELECT + " WHERE r.user_id = :u"
                        + (type != null ? " AND r.type = CAST(:t AS vitallink.record_type)" : "")
                        + " ORDER BY r.record_date DESC, r.created_at DESC, r.id DESC")
                .param("u", userId);
        if (type != null) {
            q = q.param("t", type.name());
        }
        return q.query(RecordService::recordRow).list();
    }

    private RecordRow record(UUID userId, UUID id) {
        return jdbc.sql(RECORD_SELECT + " WHERE r.id = :id AND r.user_id = :u")
                .param("id", id)
                .param("u", userId)
                .query(RecordService::recordRow)
                .optional()
                .orElseThrow(() -> ApiError.notFound("No such record."));
    }

    /** The newest live share that covers records, if any. */
    private Optional<ConsentRow> recordsShare(UUID userId) {
        OffsetDateTime now = Clock.now();
        return sharing.liveConsents(userId).stream()
                .filter(c -> c.isLive(now) && c.scopes().contains(Scope.records))
                .findFirst();
    }

    static HealthRecord out(RecordRow r, ZoneId tz, String sharedWith) {
        FileInfo file = r.fileStatus().equals("none")
                ? null
                : new FileInfo(
                        r.fileName(),
                        r.fileSize() == null ? null : (int) Math.ceil(r.fileSize() / 1024.0),
                        r.fileType(),
                        r.fileStatus());
        return new HealthRecord(
                r.id(),
                r.title(),
                r.type(),
                r.source(),
                r.note(),
                r.date().atStartOfDay(tz).toOffsetDateTime(),
                sharedWith,
                r.panelId(),
                file);
    }

    // ---------------------------------------------------------------- records

    public List<HealthRecord> list(UUID userId, String tz, RecordType type) {
        String shared = recordsShare(userId).map(ConsentRow::granteeLabel).orElse(null);
        ZoneId zone = ZoneId.of(tz);
        return records(userId, type).stream().map(r -> out(r, zone, shared)).toList();
    }

    public HealthRecord get(UUID userId, String tz, UUID id) {
        String shared = recordsShare(userId).map(ConsentRow::granteeLabel).orElse(null);
        return out(record(userId, id), ZoneId.of(tz), shared);
    }

    public HealthRecord create(CurrentUser me, RecordIn body) {
        UUID id = insertRecord(me.id(), body.type(), body.title(), body.source(), body.note(), body.date());
        return get(me.id(), me.user().timezone(), id);
    }

    private UUID insertRecord(UUID userId, RecordType type, String title, String source, String note, LocalDate date) {
        UUID id = Ids.uuid7();
        jdbc.sql("""
                        INSERT INTO vitallink.health_records (id, user_id, type, title, source, note, record_date)
                        VALUES (:id, :u, CAST(:type AS vitallink.record_type), :title, :source, :note, :date)
                        """)
                .param("id", id)
                .param("u", userId)
                .param("type", type.name())
                .param("title", title)
                .param("source", source)
                .param("note", note)
                .param("date", date)
                .update();
        return id;
    }

    private static final Set<String> RECORD_FIELDS = Set.of("title", "source", "note", "date", "type");

    public HealthRecord update(CurrentUser me, UUID id, Map<String, Object> body) {
        record(me.id(), id);
        Patch p = Patch.of(body, RECORD_FIELDS);
        Map<String, Object> changes = new LinkedHashMap<>();
        List<String> sets = new ArrayList<>();
        if (p.has("title")) {
            String title = p.string("title", 1, 120, null);
            if (title == null) throw Patch.invalid("title", "can't be empty");
            changes.put("title", title);
            sets.add("title = :title");
        }
        if (p.has("source")) {
            String source = p.string("source", 0, 120, null);
            changes.put("source", source == null ? "" : source);
            sets.add("source = :source");
        }
        if (p.has("note")) {
            changes.put("note", p.string("note", 0, 200, null));
            sets.add("note = :note");
        }
        if (p.has("date")) {
            LocalDate date = p.date("date");
            if (date == null) throw Patch.invalid("date", "can't be empty");
            changes.put("record_date", date);
            sets.add("record_date = :record_date");
        }
        if (p.has("type")) {
            RecordType type = p.enumValue("type", RecordType.class);
            if (type == null) throw Patch.invalid("type", "can't be empty");
            changes.put("type", type.name());
            sets.add("type = CAST(:type AS vitallink.record_type)");
        }
        if (!sets.isEmpty()) {
            jdbc.sql("UPDATE vitallink.health_records SET " + String.join(", ", sets)
                            + ", updated_at = now() WHERE id = :id AND user_id = :u")
                    .params(changes)
                    .param("id", id)
                    .param("u", me.id())
                    .update();
        }
        return get(me.id(), me.user().timezone(), id);
    }

    /** Deletes the record (its lab panel stays, unlinked); the stored file goes once the delete commits. */
    public void delete(CurrentUser me, UUID id) {
        RecordRow r = record(me.id(), id);
        jdbc.sql("DELETE FROM vitallink.health_records WHERE id = :id AND user_id = :u")
                .param("id", id)
                .param("u", me.id())
                .update();
        if (r.fileKey() != null) {
            deleteAfterCommit(List.of(r.fileKey()));
        }
    }

    private void deleteAfterCommit(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        Runnable work = () -> keys.forEach(k -> {
            try {
                storage.delete(k);
            } catch (RuntimeException e) {
                log.error("couldn't delete stored file {}; the nightly job retries orphans", k, e);
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
        } else {
            work.run();
        }
    }

    // ---------------------------------------------------------------- files

    public UploadOut startUpload(CurrentUser me, UUID id, UploadIn body) {
        RecordRow r = record(me.id(), id);
        if (!ALLOWED_TYPES.contains(body.contentType())) {
            throw new ApiError(415, "unsupported_file_type", "Upload a PDF, JPEG, PNG or HEIC file.", ALLOWED_TYPES);
        }
        if (body.size() > MAX_BYTES) {
            throw new ApiError(413, "file_too_large", "Files can be up to 25 MB.");
        }
        String key = me.id() + "/" + id + "/" + Ids.uuid7();
        jdbc.sql("""
                        UPDATE vitallink.health_records SET file_status = 'pending', file_key = :key, file_name = :name,
                          file_size = NULL, file_type = NULL, upload_requested_at = now(), updated_at = now()
                        WHERE id = :id
                        """)
                .param("key", key)
                .param("name", body.fileName())
                .param("id", id)
                .update();
        if (r.fileKey() != null) {
            deleteAfterCommit(List.of(r.fileKey())); // a replaced file goes
        }
        FileStorage.SignedUpload signed = storage.signUpload(key, body.contentType());
        return new UploadOut(signed.url(), signed.method(), signed.headers(), signed.expiresAt());
    }

    /** Checks what was stored; only an allowed type of at most 25 MB becomes attached. */
    public HealthRecord confirmUpload(CurrentUser me, UUID id) {
        RecordRow r = record(me.id(), id);
        if (!r.fileStatus().equals("pending")) {
            throw new ApiError(409, "no_pending_upload", "There's no upload waiting for this record.");
        }
        var stored = storage.stat(r.fileKey())
                .orElseThrow(() ->
                        new ApiError(409, "file_missing", "The file hasn't arrived yet. Upload it, then confirm."));
        if (stored.size() < 1 || stored.size() > MAX_BYTES || !ALLOWED_TYPES.contains(stored.contentType())) {
            // The bad object goes now; the record stays pending until a new upload (or the nightly cleanup).
            storage.delete(r.fileKey());
            throw new ApiError(422, "file_rejected", "The stored file isn't a PDF or image of at most 25 MB.");
        }
        jdbc.sql("""
                        UPDATE vitallink.health_records SET file_status = 'attached', file_size = :size, file_type = :type,
                          updated_at = now() WHERE id = :id
                        """)
                .param("size", (int) stored.size())
                .param("type", stored.contentType())
                .param("id", id)
                .update();
        return get(me.id(), me.user().timezone(), id);
    }

    public DownloadOut download(UUID userId, UUID id) {
        RecordRow r = record(userId, id);
        if (!r.fileStatus().equals("attached")) {
            throw ApiError.notFound("This record has no file.");
        }
        return new DownloadOut(
                storage.signDownload(r.fileKey(), r.fileName()), Clock.now().plus(FileStorage.TTL));
    }

    // ---------------------------------------------------------------- overview

    static String scopeLabel(List<Scope> scopes) {
        List<String> labels = scopes.stream().map(Scope::name).toList();
        String joined = labels.size() == 1
                ? labels.getFirst()
                : String.join(", ", labels.subList(0, labels.size() - 1)) + " and " + labels.getLast();
        return Character.toUpperCase(joined.charAt(0)) + joined.substring(1);
    }

    public RecordsOverview overview(UUID userId, String tz) {
        ZoneId zone = ZoneId.of(tz);
        List<RecordRow> all = records(userId, null);
        Map<RecordType, Integer> counts = new EnumMap<>(RecordType.class);
        for (RecordType t : RecordType.values()) {
            counts.put(t, 0);
        }
        all.forEach(r -> counts.merge(r.type(), 1, Integer::sum));
        OffsetDateTime now = Clock.now();
        List<ConsentRow> live =
                sharing.liveConsents(userId).stream().filter(c -> c.isLive(now)).toList();
        String shared = live.stream()
                .filter(c -> c.scopes().contains(Scope.records))
                .findFirst()
                .map(ConsentRow::granteeLabel)
                .orElse(null);
        List<RecordShare> shares = live.stream()
                .map(c -> c.scopes().contains(Scope.records)
                        ? new RecordShare(c.granteeLabel(), all.size(), null, c.expiresAt())
                        : new RecordShare(c.granteeLabel(), null, scopeLabel(c.scopes()), c.expiresAt()))
                .toList();
        return new RecordsOverview(
                counts, all.stream().limit(6).map(r -> out(r, zone, shared)).toList(), shares);
    }

    // ---------------------------------------------------------------- labs

    record ResultRow(
            UUID panelId,
            String analyte,
            double value,
            String unit,
            double low,
            double high,
            int decimals,
            Interpretation reported,
            boolean onReport) {}

    record PanelRow(
            UUID id,
            UUID recordId,
            String name,
            String shortName,
            String facility,
            String laboratory,
            String orderedBy,
            OffsetDateTime collectedAt,
            OffsetDateTime reportedAt,
            OffsetDateTime addedAt,
            String note,
            String fileStatus,
            String fileName,
            Integer fileSize) {}

    /** The lab's reading if given; otherwise from the range: inside = normal, within 10% of its width = borderline. */
    static Interpretation interpret(double value, double low, double high, Interpretation reported) {
        if (reported != null) {
            return reported;
        }
        if (value >= low && value <= high) {
            return Interpretation.normal;
        }
        double width = high - low;
        double off = value < low ? low - value : value - high;
        return off <= width * 0.1 ? Interpretation.borderline : Interpretation.abnormal;
    }

    private List<PanelRow> panelRows(UUID userId) {
        return jdbc.sql("""
                        SELECT p.*, r.file_status, r.file_name, r.file_size FROM vitallink.lab_panels p
                        LEFT JOIN vitallink.health_records r ON r.id = p.record_id
                        WHERE p.user_id = :u ORDER BY p.collected_at, p.id
                        """)
                .param("u", userId)
                .query((rs, i) -> {
                    int size = rs.getInt("file_size");
                    Integer fileSize = rs.wasNull() ? null : size;
                    return new PanelRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("record_id", UUID.class),
                            rs.getString("name"),
                            rs.getString("short_name"),
                            rs.getString("facility"),
                            rs.getString("laboratory"),
                            rs.getString("ordered_by"),
                            rs.getObject("collected_at", OffsetDateTime.class),
                            rs.getObject("reported_at", OffsetDateTime.class),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getString("note"),
                            rs.getString("file_status"),
                            rs.getString("file_name"),
                            fileSize);
                })
                .list();
    }

    private Map<UUID, List<ResultRow>> resultRows(UUID userId) {
        Map<UUID, List<ResultRow>> out = new HashMap<>();
        jdbc.sql("""
                        SELECT r.* FROM vitallink.lab_results r JOIN vitallink.lab_panels p ON p.id = r.panel_id
                        WHERE p.user_id = :u ORDER BY r.position
                        """)
                .param("u", userId)
                .query((rs, i) -> {
                    String reported = rs.getString("reported_interpretation");
                    UUID panel = rs.getObject("panel_id", UUID.class);
                    return out.computeIfAbsent(panel, k -> new ArrayList<>())
                            .add(new ResultRow(
                                    panel,
                                    rs.getString("analyte"),
                                    rs.getBigDecimal("value").doubleValue(),
                                    rs.getString("unit"),
                                    rs.getBigDecimal("ref_low").doubleValue(),
                                    rs.getBigDecimal("ref_high").doubleValue(),
                                    rs.getInt("decimals"),
                                    reported == null ? null : Interpretation.valueOf(reported),
                                    rs.getBoolean("on_report")));
                })
                .list();
        return out;
    }

    /** Every panel, newest first; each result carries the same test's value from the latest earlier panel. */
    public List<LabPanel> labs(UUID userId, String tz) {
        ZoneId zone = ZoneId.of(tz);
        List<PanelRow> panels = panelRows(userId);
        Map<UUID, List<ResultRow>> results = resultRows(userId);
        Map<String, Double> latestByAnalyte = new HashMap<>();
        List<LabPanel> out = new ArrayList<>();
        for (PanelRow p : panels) { // oldest first, so "previous" is what came before
            List<ResultRow> rows = results.getOrDefault(p.id(), List.of());
            List<LabResult> labResults = rows.stream()
                    .map(r -> new LabResult(
                            r.analyte(),
                            r.value(),
                            r.unit(),
                            r.low(),
                            r.high(),
                            latestByAnalyte.get(r.analyte().toLowerCase()),
                            r.decimals(),
                            interpret(r.value(), r.low(), r.high(), r.reported())))
                    .toList();
            rows.forEach(r -> latestByAnalyte.put(r.analyte().toLowerCase(), r.value()));
            LabFile file = "attached".equals(p.fileStatus())
                    ? new LabFile(p.fileName(), (int) Math.ceil(p.fileSize() / 1024.0))
                    : null;
            out.add(new LabPanel(
                    p.id(),
                    p.recordId(),
                    p.name(),
                    p.shortName(),
                    p.facility(),
                    p.laboratory(),
                    p.orderedBy(),
                    p.collectedAt().atZoneSameInstant(zone).toOffsetDateTime(),
                    p.reportedAt().atZoneSameInstant(zone).toOffsetDateTime(),
                    p.addedAt().atZoneSameInstant(zone).toOffsetDateTime(),
                    file,
                    labResults,
                    rows.stream()
                            .filter(ResultRow::onReport)
                            .map(ResultRow::analyte)
                            .toList(),
                    p.note()));
        }
        return out.reversed();
    }

    public LabPanel lab(UUID userId, String tz, UUID id) {
        return labs(userId, tz).stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow(() -> ApiError.notFound("No such lab panel."));
    }

    public Optional<LatestLab> latestResult(UUID userId, String tz) {
        return labs(userId, tz).stream()
                .filter(p -> !p.results().isEmpty())
                .findFirst()
                .map(p -> new LatestLab(
                        p.id(), p.name(), p.collectedAt(), p.results().getFirst()));
    }

    private void checkResults(List<LabResultIn> results) {
        Set<String> seen = new java.util.HashSet<>();
        for (LabResultIn r : results) {
            if (BLOOD_GROUP.matcher(r.name()).find()) {
                throw new ApiError(
                        422,
                        "blood_group_not_supported",
                        "VitalLink doesn't store blood type or blood group results.",
                        r.name());
            }
            if (r.low() >= r.high()) {
                throw ApiError.invalid("Each result needs a reference range with low below high.");
            }
            if (!seen.add(r.name().toLowerCase())) {
                throw ApiError.invalid("Each test appears once per panel: " + r.name() + ".");
            }
        }
    }

    private void insertResults(UUID panelId, List<LabResultIn> results) {
        for (int i = 0; i < results.size(); i++) {
            LabResultIn r = results.get(i);
            jdbc.sql("""
                            INSERT INTO vitallink.lab_results (id, panel_id, analyte, value, unit, ref_low, ref_high,
                              decimals, reported_interpretation, on_report, position)
                            VALUES (:id, :p, :name, :value, :unit, :low, :high, :decimals,
                                    CAST(:interp AS vitallink.interpretation), :on_report, :position)
                            """)
                    .param("id", Ids.uuid7())
                    .param("p", panelId)
                    .param("name", r.name())
                    .param("value", BigDecimal.valueOf(r.value()))
                    .param("unit", r.unit())
                    .param("low", BigDecimal.valueOf(r.low()))
                    .param("high", BigDecimal.valueOf(r.high()))
                    .param("decimals", r.decimals())
                    .param(
                            "interp",
                            r.interpretation() == null
                                    ? null
                                    : r.interpretation().name())
                    .param("on_report", r.onReport())
                    .param("position", i)
                    .update();
        }
    }

    public LabPanel createLab(CurrentUser me, LabPanelIn body) {
        checkResults(body.results());
        if (body.reportedAt().isBefore(body.collectedAt())) {
            throw ApiError.invalid("The report can't come before the sample was collected.");
        }
        UUID recordId = body.recordId();
        if (recordId != null) {
            record(me.id(), recordId); // must be mine
            boolean taken = !jdbc.sql("SELECT 1 FROM vitallink.lab_panels WHERE record_id = :r")
                    .param("r", recordId)
                    .query()
                    .listOfRows()
                    .isEmpty();
            if (taken) {
                throw new ApiError(409, "record_has_panel", "That record already has a lab panel.");
            }
        } else {
            recordId = insertRecord(
                    me.id(),
                    RecordType.lab,
                    body.name(),
                    body.laboratory(),
                    null,
                    body.collectedAt().atZoneSameInstant(me.zone()).toLocalDate());
        }
        UUID id = Ids.uuid7();
        jdbc.sql("""
                        INSERT INTO vitallink.lab_panels (id, user_id, record_id, name, short_name, facility, laboratory,
                                                          ordered_by, collected_at, reported_at, note)
                        VALUES (:id, :u, :r, :name, :short, :facility, :lab, :ordered, :collected, :reported, :note)
                        """)
                .param("id", id)
                .param("u", me.id())
                .param("r", recordId)
                .param("name", body.name())
                .param("short", body.shortName())
                .param("facility", body.facility())
                .param("lab", body.laboratory())
                .param("ordered", body.orderedBy())
                .param("collected", body.collectedAt())
                .param("reported", body.reportedAt())
                .param("note", body.note())
                .update();
        insertResults(id, body.results());
        LabPanel panel = lab(me.id(), me.user().timezone(), id);
        long outside = panel.results().stream()
                .filter(r -> r.interpretation() != Interpretation.normal)
                .count();
        String from = body.facility().isEmpty() ? "" : " from " + body.facility();
        String flagged = outside == 0
                ? "All values within range."
                : outside + " value" + (outside == 1 ? "" : "s") + " outside range.";
        notifications.notify(
                me.id(),
                "lab:" + id,
                new Message(
                        Kind.health,
                        "New lab result available",
                        body.name() + from + ". " + flagged,
                        body.name() + ". " + flagged,
                        "View result",
                        "/records/labs"));
        return panel;
    }

    /** Replaces the panel's details and results (the form edits the whole report). */
    public LabPanel replaceLab(CurrentUser me, UUID id, LabPanelIn body) {
        lab(me.id(), me.user().timezone(), id);
        checkResults(body.results());
        jdbc.sql("""
                        UPDATE vitallink.lab_panels SET name = :name, short_name = :short, facility = :facility,
                          laboratory = :lab, ordered_by = :ordered, collected_at = :collected, reported_at = :reported,
                          note = :note, updated_at = now()
                        WHERE id = :id AND user_id = :u
                        """)
                .param("name", body.name())
                .param("short", body.shortName())
                .param("facility", body.facility())
                .param("lab", body.laboratory())
                .param("ordered", body.orderedBy())
                .param("collected", body.collectedAt())
                .param("reported", body.reportedAt())
                .param("note", body.note())
                .param("id", id)
                .param("u", me.id())
                .update();
        jdbc.sql("DELETE FROM vitallink.lab_results WHERE panel_id = :p")
                .param("p", id)
                .update();
        insertResults(id, body.results());
        return lab(me.id(), me.user().timezone(), id);
    }

    public void deleteLab(CurrentUser me, UUID id) {
        int n = jdbc.sql("DELETE FROM vitallink.lab_panels WHERE id = :id AND user_id = :u")
                .param("id", id)
                .param("u", me.id())
                .update();
        if (n == 0) {
            throw ApiError.notFound("No such lab panel.");
        }
    }

    // ---------------------------------------------------------------- data rights and the nightly job

    @Override
    public Map<String, String> exportQueries() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put(
                "records",
                "SELECT id, type, title, source, note, record_date, file_status, file_name, file_size, "
                        + "file_type, created_at FROM vitallink.health_records WHERE user_id = :u ORDER BY record_date, id");
        q.put("lab_panels", "SELECT * FROM vitallink.lab_panels WHERE user_id = :u ORDER BY collected_at, id");
        q.put("lab_results", """
                SELECT r.* FROM vitallink.lab_results r JOIN vitallink.lab_panels p ON p.id = r.panel_id
                WHERE p.user_id = :u ORDER BY p.collected_at, r.position
                """);
        return q;
    }

    @Override
    public void beforeDelete(UUID userId) {
        List<String> keys = jdbc.sql(
                        "SELECT file_key FROM vitallink.health_records WHERE user_id = :u AND file_key IS NOT NULL")
                .param("u", userId)
                .query(String.class)
                .list();
        deleteAfterCommit(keys);
    }

    /** Uploads started over a day ago and never confirmed: delete the object (if any) and reset. */
    public int cleanAbandonedUploads() {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT id, file_key FROM vitallink.health_records
                        WHERE file_status = 'pending' AND upload_requested_at < now() - interval '1 day'
                        """).query().listOfRows();
        for (Map<String, Object> r : rows) {
            jdbc.sql("""
                            UPDATE vitallink.health_records SET file_status = 'none', file_key = NULL, file_name = NULL,
                              file_size = NULL, file_type = NULL, upload_requested_at = NULL WHERE id = :id
                            """).param("id", r.get("id")).update();
        }
        deleteAfterCommit(rows.stream().map(r -> (String) r.get("file_key")).collect(Collectors.toList()));
        return rows.size();
    }
}
