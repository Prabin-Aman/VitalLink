# 3. Class diagram

There are two views. The **domain model** shows the entities, their key attributes, behaviour and
relationships. The attributes match [the database](04-database.md); in Java each table is read and written by its module's service with plain SQL.
The **service model** shows how routers, services, repositories and the core classes fit together.

Notation: `+` public, `-` private, `$` static, `*` abstract. Multiplicities are on the associations.
Computed values (status, streaks, totals) are methods, not stored attributes.

## 3.1 Account, sharing and audit

```mermaid
classDiagram
  direction LR
  class User {
    +UUID id
    +UUID auth_subject
    +str email
    +datetime email_confirmed_at
    +str timezone
    +datetime created_at
    +is_email_confirmed() bool
    +local_day(datetime) date
  }
  class Profile {
    +str first_name
    +str last_name
    +date date_of_birth
    +Sex sex
    +Decimal height_cm
    +str phone
    +str zip
    +str city
    +str language
    +Units units
    +Theme theme
    +int daily_steps_goal
    +int move_kcal_goal
    +int exercise_min_goal
    +int stand_hours_goal
    +int sleep_goal_min
    +age(date today) int
    +display_name() str
  }
  class Consent {
    +UUID id
    +UUID grantor_id
    +str grantee_email
    +UUID grantee_user_id
    +str grantee_label
    +GranteeKind grantee_kind
    +list~Scope~ scopes
    +str purpose
    +datetime starts_at
    +datetime expires_at
    +datetime revoked_at
    +RevokeReason revoke_reason
    +datetime bound_at
    +covers(Scope) bool
    +is_live(datetime now) bool
    +bind_to(UUID viewer) void
    +revoke(RevokeReason) void
  }
  class AuditEntry {
    +int id
    +datetime at
    +UUID actor_user_id
    +UUID subject_user_id
    +AuditAction action
    +Scope scope
    +AccessOutcome outcome
    +RefusalReason reason
    +UUID consent_id
    +str request_id
  }
  class Scope {
    <<enumeration>>
    vitals
    activity
    sleep
    nutrition
    records
  }
  class AccessOutcome {
    <<enumeration>>
    authorised
    limited
    blocked
  }
  class GranteeKind {
    <<enumeration>>
    doctor
    family
    other
  }
  User "1" *-- "1" Profile : has
  User "1" --> "*" Consent : grants as grantor
  User "0..1" <-- "*" Consent : bound viewer
  Consent "1" ..> "*" Scope : covers
  AuditEntry ..> User : ids only, no FK
  AuditEntry ..> Consent : consent_id, no FK
  AuditEntry --> AccessOutcome
  Consent --> GranteeKind
```

## 3.2 Health, training, nutrition and goals

```mermaid
classDiagram
  direction LR
  class Metric {
    +int id
    +str code
    +Scope scope
    +str unit
    +Aggregation aggregation
    +float min_value
    +float max_value
    +bool manual_allowed
    +is_plausible(float) bool
  }
  class HealthReading {
    +UUID user_id
    +int metric_id
    +datetime recorded_at
    +ReadingSource source
    +float value
    +int samples
    +datetime updated_at
  }
  class IngestBatch {
    +int id
    +UUID idempotency_key
    +bytes payload_sha256
    +ReadingSource platform
    +int submitted
    +int inserted
    +int updated
    +int unchanged
    +matches(bytes hash) bool
  }
  class Device {
    +UUID id
    +ReadingSource platform
    +str external_id
    +str name
    +DeviceKind kind
    +list~int~ metric_ids
    +datetime last_sync_at
    +status(datetime now) DeviceStatus
  }
  class Workout {
    +UUID id
    +WorkoutType type
    +str title
    +datetime started_at
    +int duration_s
    +Decimal distance_m
    +int kcal
    +int avg_hr
    +int rpe
    +list~int~ zone_seconds
    +str focus
    +kind() SessionKind
    +dominant_zone() int
    +volume_kg() Decimal
    +pace_s_per_km() int
  }
  class ExerciseSet {
    +int set_no
    +int reps
    +Decimal weight_kg
    +int duration_s
    +est_one_rep_max() Decimal
  }
  class Exercise {
    +int id
    +str name
    +MuscleGroup primary_muscle
  }
  class TrainingPlan {
    +UUID id
    +str name
    +PlanStatus status
    +int weekly_target_sessions
    +next_session(datetime now) PlanSession
  }
  class PlanSession {
    +UUID id
    +str title
    +SessionKind kind
    +datetime scheduled_for
    +int exercises
    +int minutes
  }
  class MealEntry {
    +UUID id
    +MealSlot slot
    +str name
    +datetime eaten_at
    +totals() Macros
  }
  class MealItem {
    +str label
    +Decimal quantity
    +Decimal kcal
    +Decimal protein_g
    +Decimal carbs_g
    +Decimal fat_g
    +Decimal fibre_g
  }
  class Food {
    +UUID id
    +str name
    +FoodSource source
    +Decimal serving_g
    +macros_for(Decimal quantity) Macros
  }
  class HydrationLog {
    +datetime logged_at
    +int ml
  }
  class NutritionTargets {
    +int kcal
    +int protein_g
    +int carbs_g
    +int fat_g
    +int fibre_g
    +int water_ml
    +int glass_ml
    +Decimal weekly_target_kg
  }
  class Goal {
    +UUID id
    +str title
    +GoalArea area
    +GoalKind kind
    +Comparator comparator
    +Decimal target
    +Period period
    +date starts_on
    +date ends_on
    +GoalLifecycle lifecycle
    +status(list~GoalProgress~, date today) GoalStatus
    +streak(list~GoalProgress~) int
  }
  class GoalProgress {
    +date period_start
    +Decimal actual
    +Decimal target
    +bool met
  }
  class Insight {
    +UUID id
    +InsightKind kind
    +InsightArea area
    +str text
    +Tone tone
    +dict evidence
    +date generated_on
    +datetime dismissed_at
  }
  Metric "1" <-- "*" HealthReading : of
  IngestBatch "1" ..> "*" HealthReading : upserts
  Device "*" ..> "*" Metric : reports
  Workout "1" *-- "*" ExerciseSet
  ExerciseSet "*" --> "1" Exercise
  TrainingPlan "1" *-- "*" PlanSession
  PlanSession "0..1" --> "0..1" Workout : completed by
  MealEntry "1" *-- "*" MealItem
  MealItem "*" --> "0..1" Food : copied from
  Goal "1" *-- "*" GoalProgress
  Goal "*" --> "0..1" Metric : tracks
```

Every class above also has `user_id` → `User`. It's left out to keep the diagram readable.

## 3.3 Records, care, notifications and library

```mermaid
classDiagram
  direction LR
  class HealthRecord {
    +UUID id
    +RecordType type
    +str title
    +str source
    +str note
    +date record_date
    +str file_key
    +str file_name
    +int file_size
    +str file_type
    +FileStatus file_status
    +request_upload(name, type, size) SignedUrl
    +confirm(StoredObject) void
    +download_url() SignedUrl
  }
  class LabPanel {
    +UUID id
    +str name
    +str short_name
    +str facility
    +str laboratory
    +str ordered_by
    +datetime collected_at
    +datetime reported_at
    +str note
  }
  class LabResult {
    +str analyte
    +Decimal value
    +str unit
    +Decimal ref_low
    +Decimal ref_high
    +int decimals
    +Interpretation reported_interpretation
    +bool on_report
    +interpretation() Interpretation
    +previous(list~LabResult~) Decimal
  }
  class Medication {
    +UUID id
    +str name
    +DoseForm form
    +str strength
    +str instructions
    +str prescriber
    +int refills_left
    +date refill_due_on
    +MedStatus status
    +doses_on(date day, str tz) list~ScheduledDose~
    +refill_due(date today) bool
  }
  class MedicationSchedule {
    +time local_time
    +int days_mask
  }
  class DoseLog {
    +datetime scheduled_for
    +DoseStatus status
    +datetime logged_at
  }
  class Appointment {
    +UUID id
    +str clinician_name
    +str specialty
    +datetime starts_at
    +int duration_min
    +VisitMode mode
    +str location
    +AppointmentState state
    +display_status(datetime now) str
  }
  class Condition {
    +UUID id
    +str label
    +ConditionKind kind
    +date noted_on
  }
  class EmergencyProfile {
    +OrganDonor organ_donor
    +str preferred_hospital
    +str notes
    +bool show_on_lock_screen
    +qr_payload(Profile, list~Condition~, list~Medication~) str
  }
  class EmergencyContact {
    +UUID id
    +str name
    +str relation
    +str phone
    +bool is_primary
  }
  class Notification {
    +UUID id
    +NotificationKind kind
    +str title
    +str body
    +str short_body
    +str action
    +str dedupe_key
    +datetime created_at
    +datetime read_at
    +mark_read() void
  }
  class Article {
    +UUID id
    +str slug
    +str title
    +int minutes
    +str reviewer
    +str summary
    +str body_md
    +int trending_rank
  }
  class ArticleCategory {
    +str code
    +str label
    +int position
  }
  HealthRecord "1" -- "0..1" LabPanel : document of
  LabPanel "1" *-- "*" LabResult
  Medication "1" *-- "*" MedicationSchedule
  Medication "1" *-- "*" DoseLog
  EmergencyProfile "1" ..> "*" Condition : lists
  EmergencyProfile "1" ..> "*" Medication : lists active
  ArticleCategory "1" <-- "*" Article
```

`Article` and `ArticleCategory` are shared content with no `user_id`. Every other class belongs to
a user, and so does each `EmergencyContact`.

## 3.4 Service model

```mermaid
classDiagram
  direction TB
  class Router {
    <<Spring @RestController>>
    +prefix str
  }
  class CurrentUser {
    <<dependency>>
    +id UUID
    +email str
    +email_verified bool
    +aal str
  }
  class RequireScope {
    <<dependency>>
    +scope Scope
    +__call__(owner_id, CurrentUser) ShareContext
  }
  class TokenVerifier {
    -jwks_cache JWKS
    -cached_at datetime
    +verify(str token) Claims
  }
  class ConsentEngine {
    <<pure>>
    +decide(Viewer, UUID owner, Scope, list~Grant~, datetime now)$ Decision
  }
  class ConsentGate {
    -repo ConsentRepository
    -audit AuditWriter
    +check(Viewer, UUID owner, Scope) ShareContext
  }
  class AuditWriter {
    -engine AsyncEngine
    +record(AuditEvent) void
  }
  class StorageClient {
    +sign_upload(key, type, size) SignedUrl
    +sign_download(key) SignedUrl
    +stat(key) StoredObject
    +delete(key) void
  }
  class Service {
    <<abstract>>
    #session AsyncSession
    +export(UUID user)* dict
    +delete_user_data(UUID user)* void
  }
  class Repository {
    <<abstract>>
    #session AsyncSession
  }
  class HealthService {
    +ingest(UUID user, IngestBatchIn) IngestResult
    +series(UUID user, metric, days, interval, agg) Series
    +dashboard(UUID user) Dashboard
    +tab(UUID user, HealthTab) TabOut
    +latest(UUID user, list~str~ metrics) list~Reading~
  }
  class SharingService {
    +create_share(UUID owner, ShareIn) Share
    +revoke(UUID owner, UUID share) void
    +shared_with_me(CurrentUser) list~Grant~
    +family(CurrentUser) list~FamilyMember~
    +access_log(UUID owner, Page) list~AccessLogEntry~
  }
  class AccountService {
    +get_or_create(Claims) User
    +export(UUID user) ExportStream
    +delete(UUID user) void
  }
  class NightlyJob {
    +run(datetime now) JobSummary
    -steps list~JobStep~
  }
  class JobStep {
    <<interface>>
    +name str
    +run_for_user(AsyncSession, User, datetime now) StepResult
    +run_global(AsyncSession, datetime now) StepResult
  }
  Router --> CurrentUser
  Router --> RequireScope : shared routes
  Router --> Service
  CurrentUser --> TokenVerifier
  RequireScope --> ConsentGate
  ConsentGate --> ConsentEngine
  ConsentGate --> AuditWriter
  Service <|-- HealthService
  Service <|-- SharingService
  Service <|-- AccountService
  Service --> Repository
  SharingService ..> HealthService : summary reads
  AccountService ..> Service : export and delete hooks
  NightlyJob o-- JobStep
  class RecordsService {
    +create(UUID user, RecordIn) HealthRecord
    +request_upload(UUID user, UUID record, FileIn) SignedUrl
    +confirm(UUID user, UUID record) HealthRecord
    +download(UUID user, UUID record) SignedUrl
  }
  Service <|-- RecordsService
  RecordsService --> StorageClient
```

How to read it:

- A **router** depends on `CurrentUser` and on `RequireScope(scope)` for every `/users/{id}/…` route.
  `RequireScope` returns a `ShareContext` (owner id, consent id, scope). The service then reads only
  what that scope allows.
- **`ConsentEngine.decide`** is static and pure, with no I/O, so every rule has a unit test.
  **`ConsentGate`** does the I/O around it: it loads grants, binds the grant on first use, and calls
  `AuditWriter` in its own transaction before raising a refusal.
- Every module service implements **export and delete hooks**. `AccountService` calls them all for
  `GET /me/export` and `DELETE /me`.
- **`NightlyJob`** runs an ordered list of `JobStep`s. Per-user steps run in their own transaction:
  goal progress, insights, missed-dose notifications, overdue-device notifications. Global steps then
  close expired shares, delete stale uploads and notifications older than 90 days, and clear old
  ingest batches.

## 3.5 As built (Java, 23 Oct 2026)

The sections above are the design. This is what exists in `backend/src/main/java/com/vitallink/api`,
with the real class and method names. Modules call each other only through their `…Service`; the
dotted arrows are every cross-module call.

```mermaid
classDiagram
  direction TB
  class WebConfig {
    <<core.web: argument resolvers>>
    +CurrentUser caller(request)
    +ConsentGate for path user_id
  }
  class TokenVerifier {
    <<core.security>>
    +verify(authorization) Claims
  }
  class RequestTransactionFilter {
    <<core.db>>
    +one transaction per /v1 request
  }
  class ConsentGate {
    <<core.consent>>
    +allows(Scope) boolean
    +require(Scope)
  }
  class Consent {
    <<pure, core.consent>>
    +decide(Viewer, owner, Scope, grants, now) Decision
  }
  class AuditLog {
    <<core.audit, REQUIRES_NEW>>
    +write(Event)
  }
  class AccessListener {
    <<interface, core.consent>>
    +allowed(owner, viewer, scope, consent)
  }
  class UserData {
    <<interface, core.userdata>>
    +exportQueries() Map
    +beforeDelete(user)
  }
  class FileStorage {
    <<interface, core.storage>>
    +signUpload(key, type) SignedUpload
    +stat(key) StoredObject
    +signDownload(key, name) String
    +delete(key)
  }
  class AccountService {
    +getOrCreate(Claims) User
    +me() / account() / updateAccount(patch)
    +targets(user) / updateTargets(patch)
    +profile(user) Profile
    +people(ids) Map
  }
  class DataRightsService {
    +export(me) Map
    +delete(me)
  }
  class HealthService {
    +ingest(IngestIn) IngestOut
    +addManual(ManualIn) ReadingOut
    +series() / bloodPressureSeries() / latest()
    +dashboard(user, tz, goal, withScore) Dashboard
    +devices() / forgetDevice(id)
    +daily() / nightlySleep() / totalBetween() / latestValue()
  }
  class HealthTabsService {
    +overview() HealthOverview
    +vitals() / sleep() / heart() / respiratory()
    +body() / mental() / digital()
  }
  class TrainingService {
    +saveWorkout(WorkoutIn) WorkoutDetail
    +listWorkouts() / training() / plans()
    +workoutTimes(user, from, to)
  }
  class NutritionService {
    +searchFoods() / createFood() / logMeal() / logWater()
    +day(user, tz, date) NutritionDay
    +dailyMacro() / dailyWater()
  }
  class SharingService {
    +createShare() / listShares() / revokeShare()
    +sharedWithMe() / receivedLive() / accessLog()
  }
  class FamilyService {
    +family(me) List~FamilyMember~
    +summaryFor(me, gate) Summary
  }
  class CareService {
    +overview() / today() / logDose() / undoDose()
    +appointments() / conditions() / emergency()
    +missedOn(day) / refillsDue() / nextAppointment()
  }
  class GoalService {
    +create() / update() / overview() / today() / streaks()
    +markAchieved(user) / atRisk(user)
  }
  class RecordService {
    +create() / list() / overview()
    +startUpload() / confirmUpload() / download()
    +createLab() / labs() / latestResult()
  }
  class NotificationService {
    +notify(user, dedupeKey, Message) boolean
    +list() / markRead()
  }
  class LibraryService {
    +load() at startup
    +library() / article(slug) / category(code)
  }
  class NightlyJob {
    +run() summary
    +perUser(user) int
  }
  WebConfig --> TokenVerifier
  WebConfig --> AccountService : getOrCreate
  WebConfig --> ConsentGate
  ConsentGate --> Consent
  ConsentGate --> AuditLog
  ConsentGate --> AccessListener
  NotificationService ..|> UserData
  RecordService ..|> UserData
  DataRightsService --> UserData : every module
  RecordService --> FileStorage
  HealthTabsService ..> HealthService
  HealthTabsService ..> AccountService : units, height, targets
  TrainingService ..> HealthService : active energy, wearable
  TrainingService ..> AccountService : birth date
  NutritionService ..> HealthService : weight, active energy
  NutritionService ..> AccountService : body profile
  GoalService ..> HealthService
  GoalService ..> NutritionService
  GoalService ..> TrainingService
  CareService ..> AccountService : profile for the medical ID
  SharingService ..> AccountService : names, emails
  SharingService ..> AuditLog : share events
  FamilyService ..> SharingService
  FamilyService ..> CareService
  FamilyService ..> HealthService
  RecordService ..> SharingService : who can see records
  RecordService ..> NotificationService : new lab result
  NightlyJob ..> GoalService
  NightlyJob ..> CareService
  NightlyJob ..> HealthService
  NightlyJob ..> RecordService : abandoned uploads
  NightlyJob ..> NotificationService
```

Shared views (`SharedViewsController`, `/v1/users/{user_id}/…`) call the same services with the owner's
id after `gate.require(scope)`. The access notifier (`notifications.AccessNotifier`) implements
`AccessListener`, so owners hear when someone opens their shared records without the consent core
depending on notifications.
