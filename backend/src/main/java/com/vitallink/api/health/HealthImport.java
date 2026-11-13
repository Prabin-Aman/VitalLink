package com.vitallink.api.health;

import com.vitallink.api.core.ApiError;
import com.vitallink.api.health.Catalogue.Metric;
import com.vitallink.api.health.HealthService.Row;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Turns a health data file into 5-minute readings (docs/05 §import): an Apple Health export ({@code export.zip}
 * or its {@code export.xml}) or a VitalLink CSV ({@code start,metric,value}). Pure parsing — the service stores
 * the rows with the same upsert as phone sync, so importing the same file again changes nothing.
 *
 * <p>Apple's export lists every sample from every source, so the iPhone and the Watch both count the same steps.
 * Like the Health app, totals take one source per bucket (the one with the most), and averages pool them.
 * Samples that span several buckets are spread over them by overlap.
 */
public final class HealthImport {

    /** The compressed upload and the XML inside it are both capped (zip bombs, Render's 512 MB). */
    static final long MAX_UPLOAD_BYTES = 300L * 1024 * 1024;

    static final long MAX_XML_BYTES = 2L * 1024 * 1024 * 1024;
    static final int MAX_PROBLEMS = 10;

    /**
     * The longest one sample may cover. Real ones are minutes to a night long; an end date years away would be spread
     * over millions of 5-minute buckets and exhaust memory.
     */
    static final Duration MAX_SAMPLE = Duration.ofDays(2);

    public enum Kind {
        apple,
        csv
    }

    /** What a file held: the rows to store and what was left out. */
    public record Parsed(
            Kind kind, List<Row> rows, int records, int skipped, List<String> problems, Instant from, Instant to) {}

    private record Key(int metricId, long epoch, String source) {}

    private static final class Acc {
        double sum;
        int count;
    }

    private final Instant since;
    private final Instant until;
    private final ZoneId zone;
    private final Map<Key, Acc> acc = new HashMap<>();
    private final List<String> problems = new ArrayList<>();
    private int records;
    private int skipped;

    HealthImport(Instant since, Instant until, ZoneId zone) {
        this.since = since;
        this.until = until;
        this.zone = zone;
    }

    /** Reads the whole stream (zip, XML or CSV, detected from the first bytes) and returns the readings. */
    public static Parsed parse(InputStream raw, Instant since, Instant until, ZoneId zone) throws IOException {
        HealthImport im = new HealthImport(since, until, zone);
        BufferedInputStream in = new BufferedInputStream(new Capped(raw, MAX_UPLOAD_BYTES, "file"), 1 << 16);
        in.mark(8);
        byte[] head = in.readNBytes(4);
        in.reset();
        Kind kind;
        if (head.length >= 2 && head[0] == 'P' && head[1] == 'K') {
            kind = Kind.apple;
            im.appleZip(in);
        } else if (firstNonSpace(in) == '<') {
            kind = Kind.apple;
            im.appleXml(new Capped(in, MAX_XML_BYTES, "export.xml"));
        } else {
            kind = Kind.csv;
            im.csv(in);
        }
        return im.finish(kind);
    }

    private static int firstNonSpace(BufferedInputStream in) throws IOException {
        in.mark(4096);
        int c;
        int n = 0;
        do {
            c = in.read();
            n++;
        } while (c != -1 && n < 4096 && (Character.isWhitespace(c) || c == 0xEF || c == 0xBB || c == 0xBF));
        in.reset();
        return c;
    }

    // ---------------------------------------------------------------- Apple Health

    private void appleZip(InputStream in) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName().replace('\\', '/');
                if (name.endsWith("/export.xml") || name.equals("export.xml")) {
                    appleXml(new Capped(zip, MAX_XML_BYTES, "export.xml"));
                    return;
                }
            }
        }
        throw new ApiError(
                422, "unreadable_file", "That zip has no export.xml. Upload the export.zip from the Health app.");
    }

    static final DateTimeFormatter APPLE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);

    /** Apple type → catalogue code (quantity types; sleep is handled separately). */
    static final Map<String, String> APPLE = Map.ofEntries(
            Map.entry("HKQuantityTypeIdentifierStepCount", "steps"),
            Map.entry("HKQuantityTypeIdentifierDistanceWalkingRunning", "distance"),
            Map.entry("HKQuantityTypeIdentifierActiveEnergyBurned", "active_energy"),
            Map.entry("HKQuantityTypeIdentifierBasalEnergyBurned", "basal_energy"),
            Map.entry("HKQuantityTypeIdentifierFlightsClimbed", "flights_climbed"),
            Map.entry("HKQuantityTypeIdentifierAppleExerciseTime", "exercise_minutes"),
            Map.entry("HKQuantityTypeIdentifierHeartRate", "heart_rate"),
            Map.entry("HKQuantityTypeIdentifierRestingHeartRate", "resting_heart_rate"),
            Map.entry("HKQuantityTypeIdentifierHeartRateVariabilitySDNN", "hrv"),
            Map.entry("HKQuantityTypeIdentifierOxygenSaturation", "oxygen_saturation"),
            Map.entry("HKQuantityTypeIdentifierRespiratoryRate", "respiratory_rate"),
            Map.entry("HKQuantityTypeIdentifierBloodPressureSystolic", "bp_systolic"),
            Map.entry("HKQuantityTypeIdentifierBloodPressureDiastolic", "bp_diastolic"),
            Map.entry("HKQuantityTypeIdentifierBloodGlucose", "glucose"),
            Map.entry("HKQuantityTypeIdentifierBodyTemperature", "body_temperature"),
            Map.entry("HKQuantityTypeIdentifierVO2Max", "vo2_max"),
            Map.entry("HKQuantityTypeIdentifierBodyMass", "weight"),
            Map.entry("HKQuantityTypeIdentifierBodyFatPercentage", "body_fat"),
            Map.entry("HKQuantityTypeIdentifierLeanBodyMass", "lean_mass"));

    static final String SLEEP = "HKCategoryTypeIdentifierSleepAnalysis";
    static final String MINDFUL = "HKCategoryTypeIdentifierMindfulSession";

    private void appleXml(InputStream in) throws IOException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        // The export carries an internal DTD; never resolve anything outside the file (XXE).
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        // Every record's device attribute is full of &lt; and &gt;, which the JDK counts towards its entity caps, so
        // real
        // exports stop early. Those caps guard against entities defined in a DTD, and the DTD is off above.
        f.setProperty("jdk.xml.entityExpansionLimit", "0");
        f.setProperty("jdk.xml.totalEntitySizeLimit", "0");
        f.setProperty("jdk.xml.maxGeneralEntitySizeLimit", "0");
        try {
            XMLStreamReader x = f.createXMLStreamReader(in, "UTF-8");
            boolean health = false;
            while (x.hasNext()) {
                if (x.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                String el = x.getLocalName();
                if (el.equals("HealthData")) {
                    health = true;
                } else if (el.equals("Record")) {
                    appleRecord(
                            x.getAttributeValue(null, "type"),
                            x.getAttributeValue(null, "sourceName"),
                            x.getAttributeValue(null, "unit"),
                            x.getAttributeValue(null, "value"),
                            x.getAttributeValue(null, "startDate"),
                            x.getAttributeValue(null, "endDate"));
                }
            }
            if (!health) {
                throw new ApiError(422, "unreadable_file", "That XML isn't an Apple Health export.");
            }
        } catch (XMLStreamException e) {
            // A size cap hit while the parser was reading surfaces wrapped; keep its 413.
            for (Throwable t = e; t != null; t = t.getCause() != null ? t.getCause() : nested(t)) {
                if (t instanceof ApiError a) {
                    throw a;
                }
            }
            throw new ApiError(422, "unreadable_file", "The export couldn't be read: it may be cut short.");
        }
    }

    private static Throwable nested(Throwable t) {
        return t instanceof XMLStreamException x && x.getNestedException() != t ? x.getNestedException() : null;
    }

    void appleRecord(String type, String source, String unit, String value, String start, String end) {
        if (type == null || start == null) {
            return;
        }
        boolean sleep = type.equals(SLEEP);
        boolean mindful = type.equals(MINDFUL);
        String code = APPLE.get(type);
        if (code == null && !sleep && !mindful) {
            return; // a type VitalLink doesn't track (e.g. dietary water, audio exposure)
        }
        Instant s;
        Instant e;
        try {
            s = OffsetDateTime.parse(start, APPLE_TIME).toInstant();
            e = end == null ? s : OffsetDateTime.parse(end, APPLE_TIME).toInstant();
        } catch (DateTimeParseException ex) {
            skip("a record with an unreadable date (" + start + ")");
            return;
        }
        if (e.isBefore(s) || s.isBefore(since) || !s.isBefore(until)) {
            return; // outside the chosen period
        }
        if (Duration.between(s, e).compareTo(MAX_SAMPLE) > 0) {
            skip("a record that covers more than 2 days (" + start + ")");
            return;
        }
        records++;
        String src = source == null ? "" : source;
        if (sleep) {
            String stage = value == null ? "" : value.replace("HKCategoryValueSleepAnalysis", "");
            switch (stage) {
                case "AsleepDeep" -> minutes(src, s, e, "sleep_deep", "sleep_asleep");
                case "AsleepCore" -> minutes(src, s, e, "sleep_core", "sleep_asleep");
                case "AsleepREM" -> minutes(src, s, e, "sleep_rem", "sleep_asleep");
                case "Asleep", "AsleepUnspecified" -> minutes(src, s, e, "sleep_asleep");
                case "Awake" -> minutes(src, s, e, "sleep_awake");
                default -> {} // InBed: time in bed isn't sleep
            }
            return;
        }
        if (mindful) {
            minutes(src, s, e, "mindful_minutes");
            return;
        }
        Double v = number(value);
        Double converted = v == null ? null : convert(code, unit, v);
        if (converted == null) {
            skip(code + " in an unknown unit (" + unit + ")");
            return;
        }
        add(Catalogue.get(code), src, s, e, converted);
    }

    /** Value in the catalogue's unit, or null when the unit isn't one Apple uses for that type. */
    static Double convert(String code, String unit, double v) {
        String u = unit == null ? "" : unit;
        return switch (code) {
            case "steps", "flights_climbed" -> u.equals("count") ? v : null;
            case "distance" ->
                switch (u) {
                    case "km" -> v * 1000;
                    case "m" -> v;
                    case "mi" -> v * 1609.344;
                    case "ft" -> v * 0.3048;
                    default -> null;
                };
            case "active_energy", "basal_energy" ->
                switch (u) {
                    case "kcal", "Cal" -> v;
                    case "kJ" -> v / 4.184;
                    default -> null;
                };
            case "exercise_minutes" -> u.equals("min") ? v : null;
            case "heart_rate", "resting_heart_rate", "respiratory_rate" -> u.equals("count/min") ? v : null;
            case "hrv" -> u.equals("ms") ? v : null;
            case "oxygen_saturation", "body_fat" -> u.equals("%") ? (v <= 1 ? v * 100 : v) : null;
            case "bp_systolic", "bp_diastolic" -> u.equals("mmHg") ? v : null;
            case "glucose" -> u.equals("mg/dL") ? v : u.startsWith("mmol") ? v * 18.016 : null;
            case "body_temperature" -> u.equals("degC") ? v : u.equals("degF") ? (v - 32) / 1.8 : null;
            case "vo2_max" -> u.startsWith("mL/") ? v : null;
            case "weight", "lean_mass" ->
                switch (u) {
                    case "kg" -> v;
                    case "lb" -> v * 0.45359237;
                    case "g" -> v / 1000;
                    default -> null;
                };
            default -> null;
        };
    }

    // ---------------------------------------------------------------- CSV

    static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd[ ]['T']HH:mm[:ss]", Locale.ROOT);

    /**
     * {@code start,metric,value} (start in ISO 8601 with an offset, or local "YYYY-MM-DD HH:MM" in the account's
     * zone), and optionally {@code end}, {@code unit} and {@code source} (an Apple Shortcut sends
     * all of them, separated by {@code |} so commas in numbers or device names can't split a line). With a unit the
     * value is converted like an Apple export's; with an end a total is spread over the buckets it covers; with a
     * source, totals take one source per bucket. Metric {@code sleep} takes a stage name as its value (Deep, Core,
     * REM, Asleep, Awake; In Bed is skipped) and needs an end.
     */
    private void csv(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String header = r.readLine();
        if (header == null) {
            throw new ApiError(422, "unreadable_file", "The file is empty.");
        }
        header = header.replace("﻿", "");
        String sep = header.contains("|") ? "\\|" : ",";
        List<String> cols =
                List.of(header.toLowerCase(Locale.ROOT).replace(" ", "").split(sep));
        int ts = cols.indexOf("start");
        int mc = cols.indexOf("metric");
        int vc = cols.indexOf("value");
        int ec = cols.indexOf("end");
        int uc = cols.indexOf("unit");
        int sc = cols.indexOf("source");
        if (ts < 0 || mc < 0 || vc < 0) {
            throw new ApiError(
                    422,
                    "unreadable_file",
                    "This isn't a file VitalLink can read. Use an Apple Health export, or a CSV with the columns start, metric, value.");
        }
        int line = 1;
        for (String l; (l = r.readLine()) != null; ) {
            line++;
            if (l.isBlank()) {
                continue;
            }
            String[] f = l.split(sep, -1);
            if (f.length <= Math.max(ts, Math.max(mc, vc))) {
                skip("line " + line + ": missing a column");
                continue;
            }
            String code = f[mc].strip();
            Instant at = csvTime(f[ts].strip());
            if (at == null) {
                skip("line " + line + ": unreadable start \"" + f[ts].strip() + "\"");
                continue;
            }
            Instant until_ = at;
            if (ec >= 0 && ec < f.length && !f[ec].isBlank()) {
                until_ = csvTime(f[ec].strip());
                if (until_ == null || until_.isBefore(at)) {
                    skip("line " + line + ": unreadable end \"" + f[ec].strip() + "\"");
                    continue;
                }
                if (Duration.between(at, until_).compareTo(MAX_SAMPLE) > 0) {
                    skip("line " + line + ": covers more than 2 days");
                    continue;
                }
            }
            String src = sc >= 0 && sc < f.length ? f[sc].strip() : "";
            if (at.isBefore(since) || !at.isBefore(until)) {
                continue;
            }
            if (code.equals("sleep")) {
                String stage = f[vc].strip().toLowerCase(Locale.ROOT);
                records++;
                if (stage.contains("deep")) {
                    minutes(src, at, until_, "sleep_deep", "sleep_asleep");
                } else if (stage.contains("core") || stage.contains("light")) {
                    minutes(src, at, until_, "sleep_core", "sleep_asleep");
                } else if (stage.contains("rem")) {
                    minutes(src, at, until_, "sleep_rem", "sleep_asleep");
                } else if (stage.contains("awake")) {
                    minutes(src, at, until_, "sleep_awake");
                } else if (stage.contains("asleep")) {
                    minutes(src, at, until_, "sleep_asleep");
                } // in bed: time in bed isn't sleep
                continue;
            }
            Metric m = Catalogue.get(code);
            if (m == null) {
                skip("line " + line + ": unknown metric \"" + code + "\"");
                continue;
            }
            Double v = number(f[vc]);
            if (v == null) {
                skip("line " + line + ": value isn't a number");
                continue;
            }
            if (uc >= 0 && uc < f.length && !f[uc].isBlank()) {
                Double converted = convert(code, appleUnit(f[uc]), v);
                if (converted == null) {
                    skip(code + " in an unknown unit (" + f[uc].strip() + ")");
                    continue;
                }
                v = converted;
            }
            records++;
            add(m, src, at, until_, v);
        }
    }

    /** The unit names an iPhone shows (Shortcuts) mapped to the ones in an Apple export. */
    static String appleUnit(String unit) {
        String u = unit.strip();
        return switch (u.toLowerCase(Locale.ROOT)) {
            case "bpm", "beats/min", "count/min", "/min", "breaths/min" -> "count/min";
            case "count", "steps", "step", "floors", "flights" -> "count";
            case "lbs", "lb", "pound", "pounds" -> "lb";
            case "kg", "kilograms" -> "kg";
            case "mi", "miles" -> "mi";
            case "km", "kilometers", "kilometres" -> "km";
            case "m", "meters", "metres" -> "m";
            case "ft", "feet" -> "ft";
            case "kcal", "cal", "calories", "kilocalories" -> "kcal";
            case "kj" -> "kJ";
            case "%", "percent" -> "%";
            case "mmhg" -> "mmHg";
            case "mg/dl" -> "mg/dL";
            case "mmol/l", "mmol<180.1558800000541>/l" -> "mmol/L";
            case "°f", "degf", "f" -> "degF";
            case "°c", "degc", "c" -> "degC";
            case "ms" -> "ms";
            case "min", "minutes" -> "min";
            default -> u;
        };
    }

    private Instant csvTime(String s) {
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through to local time
        }
        try {
            return LocalDateTime.parse(s, LOCAL).atZone(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through to a bare date
        }
        try {
            return LocalDate.parse(s).atTime(12, 0).atZone(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------- buckets

    private static Double number(String s) {
        if (s == null) {
            return null;
        }
        // An iPhone writes numbers in its region's style: "1,234.5", "1 234,5" or "1,5".
        String t = s.strip().replace(" ", "").replace(" ", "").replace(" ", "");
        if (t.matches("-?\\d{1,3}(,\\d{3})+(\\.\\d+)?")) {
            t = t.replace(",", "");
        } else if (t.matches("-?\\d{1,3}(\\.\\d{3})+,\\d+")) {
            t = t.replace(".", "").replace(",", ".");
        } else if (t.matches("-?\\d+,\\d+")) {
            t = t.replace(",", ".");
        }
        try {
            double d = Double.parseDouble(t);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void skip(String why) {
        skipped++;
        if (problems.size() < MAX_PROBLEMS) {
            problems.add(why);
        }
    }

    private static long bucket(Instant t) {
        return Math.floorDiv(t.getEpochSecond(), 300) * 300;
    }

    /** Totals are spread over the buckets the sample covers; averages and point readings go to the start bucket. */
    private void add(Metric m, String source, Instant s, Instant e, double v) {
        if (m.aggregation() != Catalogue.Agg.sum || !e.isAfter(s)) {
            put(m.id(), bucket(s), source, v);
            return;
        }
        double total = e.getEpochSecond() - s.getEpochSecond();
        for (long b = bucket(s); b < e.getEpochSecond(); b += 300) {
            double overlap = Math.min(b + 300, e.getEpochSecond()) - Math.max(b, s.getEpochSecond());
            if (overlap > 0) {
                put(m.id(), b, source, v * overlap / total);
            }
        }
    }

    /** Minutes of a category sample (sleep stage, mindful session) in each bucket, for each code. */
    private void minutes(String source, Instant s, Instant e, String... codes) {
        for (long b = bucket(s); b < e.getEpochSecond(); b += 300) {
            double overlap = Math.min(b + 300, e.getEpochSecond()) - Math.max(b, s.getEpochSecond());
            if (overlap > 0) {
                for (String c : codes) {
                    put(Catalogue.get(c).id(), b, source, overlap / 60.0);
                }
            }
        }
    }

    private void put(int metricId, long epoch, String source, double v) {
        Acc a = acc.computeIfAbsent(new Key(metricId, epoch, source), k -> new Acc());
        a.sum += v;
        a.count++;
    }

    private Parsed finish(Kind kind) {
        // Per metric and bucket: one source for totals (the largest, as the Health app shows), all for averages.
        Map<Long, double[]> merged = new HashMap<>(); // (metricId, epoch) → {value, samples}
        Map<Long, Integer> metricOf = new HashMap<>();
        for (var en : acc.entrySet()) {
            Key k = en.getKey();
            Acc a = en.getValue();
            Metric m = Catalogue.BY_ID.get(k.metricId());
            long id = k.epoch() * 128 + k.metricId(); // metric ids < 128
            metricOf.put(id, k.metricId());
            double[] cur = merged.get(id);
            if (m.aggregation() == Catalogue.Agg.sum) {
                if (cur == null || a.sum > cur[0]) {
                    merged.put(id, new double[] {a.sum, a.count});
                }
            } else if (cur == null) {
                merged.put(id, new double[] {a.sum, a.count});
            } else {
                cur[0] += a.sum;
                cur[1] += a.count;
            }
        }
        List<Row> rows = new ArrayList<>(merged.size());
        TreeSet<Long> epochs = new TreeSet<>();
        for (var en : merged.entrySet()) {
            Metric m = Catalogue.BY_ID.get(metricOf.get(en.getKey()));
            double[] v = en.getValue();
            double value = m.aggregation() == Catalogue.Agg.sum ? v[0] : v[0] / v[1];
            value = Math.round(value * 1000) / 1000.0;
            if (value < m.minValue() || value > m.maxValue()) {
                skip(m.code() + " " + value + " " + m.unit() + " is outside " + (long) m.minValue() + "–"
                        + (long) m.maxValue());
                continue;
            }
            long epoch = Math.floorDiv(en.getKey(), 128);
            epochs.add(epoch);
            rows.add(new Row(m.id(), Instant.ofEpochSecond(epoch), value, (int) Math.min(32767, Math.max(1, v[1]))));
        }
        rows.sort(Comparator.comparing(Row::start).thenComparingInt(Row::metricId));
        return new Parsed(
                kind,
                rows,
                records,
                skipped,
                List.copyOf(problems),
                epochs.isEmpty() ? null : Instant.ofEpochSecond(epochs.first()),
                epochs.isEmpty() ? null : Instant.ofEpochSecond(epochs.last()));
    }

    /** Stops reading past a size cap so one upload can't exhaust memory or disk. */
    static final class Capped extends FilterInputStream {
        private final long max;
        private final String what;
        private long read;

        Capped(InputStream in, long max, String what) {
            super(in);
            this.max = max;
            this.what = what;
        }

        private void count(long n) {
            if (n > 0 && (read += n) > max) {
                throw new ApiError(413, "file_too_large", "The " + what + " is over " + max / (1024 * 1024) + " MB.");
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            count(n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            count(s);
            return s;
        }
    }
}
