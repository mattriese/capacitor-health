## Sync Overlap: Why It Exists, Why It's Flawed, and Better Alternatives

### What the Health Connect API actually returns

Every Health Connect record carries two distinct temporal signals:

1. **Activity timestamps**: `startTime`, `endTime`, `sample.time` — when the physical activity happened.
2. **`metadata.lastModifiedTime`** — when Health Connect received or last updated the record.

A `HeartRateRecord` from the API looks like this (Kotlin types):

```
HeartRateRecord {
    startTime: Instant           // e.g. 2025-01-15T14:00:00Z
    endTime: Instant             // e.g. 2025-01-15T14:01:00Z
    samples: List<Sample> [
        Sample { time: Instant, beatsPerMinute: Long }
    ]
    metadata: Metadata {
        id: String               // Health Connect's unique record ID
        lastModifiedTime: Instant // when HC received/updated this record
        dataOrigin: DataOrigin { packageName: String }
        device: Device?          // manufacturer, model
        clientRecordId: String?
        clientRecordVersion: Long
    }
}
```

Health Connect also provides a **Changes API** (`getChanges` with a `changesToken`) that returns only records created or modified since a token was issued. This is Google's recommended approach for incremental sync.

### What our plugin exposes (and what it drops)

The `@capgo/capacitor-health` plugin (`HealthManager.kt`) calls `readRecords` with `TimeRangeFilter.between(startTime, endTime)` and builds a JS payload per sample via `createSamplePayload`. That method returns:

- `startDate`, `endDate` (activity timestamps)
- `value` (BPM or step count)
- `sourceId`, `sourceName` (from `metadata.dataOrigin` and `metadata.device`)

**It does NOT return `metadata.lastModifiedTime`.** That field is available in the `Metadata` object passed to `createSamplePayload` but is never included in the output.

**It does NOT implement the Changes API.** Only `readRecords` (time-range queries) is wrapped.

### Why the `readRecords` API forces an overlap

`readRecords` filters on **activity timestamps**, not on when Health Connect received the data. There is no `TimeRangeFilter.byLastModified()`. This means: if a record was physically performed at 10:00 AM but only written to Health Connect at 12:00 PM (e.g., because the watch was out of Bluetooth range), a query for `readRecords(from=11:00, to=now)` will miss it, because the record's activity time (10:00) is outside the query range.

The overlap window compensates for this by re-reading a fixed amount of historical activity time on each sync, hoping to catch records that were written to Health Connect after our previous sync but whose activity timestamps are older.

### How `last_synced_through_at` creates the problem

`FitnessSyncService` advances `last_synced_through_at` to `now` after every successful sync, regardless of whether new data was actually received. This is the design flaw the overlap exists to compensate for.

Walkthrough of the watch-disconnect scenario:

1. **T+0min**: App syncs. Watch is connected. HR data flows normally. Sets `last_synced_through_at = T+0`.
2. **T+1min**: User goes for a walk with watch but leaves phone at home.
3. **T+2min through T+120min**: App syncs every 60 seconds. Health Connect has no new watch data (watch is out of Bluetooth range). But `last_synced_through_at` advances to `now` on every sync — T+2, T+3, ..., T+120.
4. **T+121min**: User returns. Watch reconnects. Samsung Health dumps 2 hours of HR data (activity-timestamped T+1 through T+120) into Health Connect.
5. **T+122min**: App syncs. `last_synced_through_at = T+121`. With 180-minute overlap, read window starts at T+121 - 180 = T-59. This reaches back far enough to cover the entire walk.

Without the overlap, step 5 would start reading from ~T+121 and miss all 2 hours of walk data permanently.

### Why 3 hours is both too long and too short

The 180-minute overlap was chosen as a conservative guess. It creates two problems:

**Too long for normal use**: During typical connected use (watch on wrist, phone nearby), HR data flows into Health Connect every ~1 minute. The overlap re-reads ~180 minutes of already-processed data every sync cycle. With sync running every 60 seconds during active commitment windows, this means every minute the system:

- Reads ~180 samples from Health Connect (only ~1 is new)
- Checks ~180 dedupe keys against local SQLite
- Generates ~180 × 11 = ~1,980 rollup buckets
- Fetches ~1,980 existing bucket rows for dirty-checking
- Compares all of them, finding ~99% unchanged

**Too short for long disconnects**: A 4-hour hike with watch but no phone would lose the first hour of data. Any fixed overlap can be too small for longer disconnects.

### HR sample frequency: device and settings dependent

The raw data frequency in Health Connect depends entirely on the source device and its settings:

| Device / Context | Typical HR sample rate |
|---|---|
| Samsung Galaxy Watch (continuous HR mode) | ~1 per minute |
| Samsung Galaxy Watch (default battery saving) | ~1 per 10 minutes |
| Apple Watch during workout | ~1 per 5 seconds |
| Apple Watch background | ~1 per 5-15 minutes |

The `@capgo/capacitor-health` plugin is a passthrough — it reads every individual sample from Health Connect / HealthKit and does not compress or aggregate.

With Samsung's ~1-minute HR sampling rate, the current 30-second rollup bucket model degenerates: most buckets are empty, and occupied buckets have exactly 1 sample. The proportional `secondsAtOrAbove` estimate (`round((countAtOrAbove / sampleCount) * 30)`) becomes binary (0 or 30) instead of the smooth gradient it was designed for. The 30-second bucket size is still correct for high-frequency sources (Apple Watch during workouts, ~6 samples per bucket) but provides no benefit over 60-second buckets for 1-minute data.

### Three options to fix the sync overlap

#### Option 1: Reduce overlap to 15 minutes

Simplest change. Replace `FITNESS_INCREMENTAL_SYNC_OVERLAP_MINUTES = 180` with `15`.

**Code change**: one constant in `FitnessSyncService.ts`.

**Pros**:
- Eliminates ~90% of wasted re-reads during normal connected use.
- 15 minutes covers Samsung Health's battery-batched sync delay (Samsung's FAQ says Samsung Health writes to Health Connect "as soon as data is created or changed" on the phone, and watch-to-phone transfer takes at most a few minutes when connected).
- No plugin changes needed.

**Cons**:
- Still doesn't handle the watch-disconnect scenario. A 30-minute walk with phone left at home would lose the first 15 minutes of data.
- Still fundamentally the wrong mechanism (fixed window vs. variable delay).

**When it breaks**: User physically separates from their phone for longer than 15 minutes and expects backfilled watch data to be captured.

#### Option 2: Fork the plugin to expose `lastModifiedTime`

Add `metadata.lastModifiedTime` to the JS payload in `createSamplePayload`.

**Code changes in `HealthManager.kt`**:

```kotlin
// In createSamplePayload, add:
payload.put("lastModifiedTime", formatter.format(metadata.lastModifiedTime))
```

**Code changes in `FitnessSyncService.ts`**: Track the max `lastModifiedTime` seen per sync. Use it as a "data watermark" for the next sync start instead of `now`. Only advance `last_synced_through_at` to the latest `lastModifiedTime` actually observed (or `now` if no data was returned, to avoid stalling).

**Pros**:
- Handles watch-disconnect correctly: if no data arrives, the watermark doesn't advance past the gap. When backfilled data appears, it has `lastModifiedTime` values that naturally fall after the watermark.
- No overlap needed at all for normal connected use.

**Cons**:
- `readRecords` still filters on **activity time**, not `lastModifiedTime`. We can't query "give me records modified after X." We'd still need to query a wide activity-time range and post-filter by `lastModifiedTime`. This limits the efficiency gain.
- The wide activity-time range is needed because backfilled data could have activity timestamps hours before its `lastModifiedTime`. How wide? We'd need a heuristic or a very wide window — which brings us back to a variant of the overlap problem.
- Requires a plugin fork (though a small change).

**When it breaks**: If we set the activity-time query range too narrow, we miss backfilled records whose activity timestamps are outside the range, even though their `lastModifiedTime` would have flagged them as new.

#### Option 3: Fork the plugin to implement the Changes API

Add a new method that wraps Health Connect's `getChanges` / `getChangesToken` APIs.

**Code changes in `HealthManager.kt`**:

```kotlin
suspend fun getChangesToken(
    client: HealthConnectClient,
    dataTypes: Collection<HealthDataType>
): String {
    val recordTypes = dataTypes.map { it.recordClass }.toSet()
    return client.getChangesToken(
        ChangesTokenRequest(recordTypes = recordTypes)
    )
}

suspend fun getChanges(
    client: HealthConnectClient,
    token: String
): Pair<JSArray, String> {
    val results = JSArray()
    var nextToken = token
    do {
        val response = client.getChanges(nextToken)
        for (change in response.changes) {
            when (change) {
                is UpsertionChange -> {
                    // Build payload from change.record
                    // Include metadata.lastModifiedTime
                    results.put(buildChangePayload(change.record))
                }
                is DeletionChange -> {
                    // Optionally handle deletions
                }
            }
        }
        nextToken = response.nextChangesToken
    } while (response.hasMore)
    return Pair(results, nextToken)
}
```

**Code changes in `FitnessSyncService.ts`**: Replace the time-range query loop with token-based sync. Store the changes token in `fitness_sync_state`. On each sync, call `getChanges(token)` to get only new/modified records. Process only the returned records. Store the new token.

**Pros**:
- No overlap needed at all. Zero wasted re-reads.
- Handles watch-disconnect perfectly: when the watch reconnects and Samsung Health writes backfilled records, they appear as `UpsertionChange` events regardless of their activity timestamps.
- This is Google's recommended sync pattern for Health Connect.
- Eliminates all the machinery around overlap windows, dedupe key pre-checks, and dirty-checking.

**Cons**:
- Largest code change. Requires forking the Capacitor plugin and adding new Kotlin methods + JS bindings.
- Token expires after 30 days of non-use. Need a fallback to re-read historical data if the token becomes invalid (this is a one-time cost, not per-sync).
- Changes API only works in the foreground (same constraint as `readRecords`).
- Testing requires mocking the Changes API at the Kotlin level, which is more complex than mocking `readRecords`.

**When it breaks**: Only if the token expires (user doesn't open the app for 30 days), in which case the fallback re-reads the last 24-48 hours of data.

### Summary

| | Option 1: Reduce overlap | Option 2: Expose lastModifiedTime | Option 3: Changes API |
|---|---|---|---|
| Plugin change | None | Small (1 line in Kotlin) | Medium (new Kotlin methods + JS bridge) |
| Service change | 1 constant | Moderate (watermark logic) | Large (replace query loop with token sync) |
| Watch-disconnect handling | Broken for >15min | Partial (wide query + post-filter) | Correct |
| Wasted re-reads (connected use) | ~10% of current | ~same as current (wide query) | Zero |
| Complexity | Trivial | Medium | High |
| Google's recommendation | No | No | Yes |

## Plugin Fork: Integration Context for Implementation

This section provides the concrete codebase context needed to implement the change-tracking APIs (Option 3) in a fork of `@capgo/capacitor-health`.

### Plugin identity and structure

- **Package**: `@capgo/capacitor-health` v7.2.15
- **GitHub**: https://github.com/nicepkg/capacitor-health (originally capgo/capacitor-health)
- **Capacitor version**: Capacitor 6+

File structure (only the files relevant to the fork):

```
android/src/main/java/app/capgo/plugin/health/
  HealthPlugin.kt      # Capacitor bridge — @PluginMethod annotations, coroutine dispatch
  HealthManager.kt     # Business logic — readSamples, saveSample, pagination
  HealthDataType.kt    # Enum mapping data type strings to HC record classes + permissions

ios/Sources/HealthPlugin/
  HealthPlugin.swift    # Capacitor bridge — CAPPluginMethod registration, call.resolve/reject
  Health.swift          # Business logic — HKSampleQuery, authorization, payload building

dist/esm/
  definitions.d.ts     # TypeScript interface consumed by the app
```

### How the Capacitor bridge works

**Android** (`HealthPlugin.kt`):
- Methods annotated with `@PluginMethod` are exposed to JS.
- Each method receives a `PluginCall`, extracts params, launches a coroutine via `pluginScope.launch`, calls into `HealthManager`, and resolves/rejects the call.
- Pattern: `call.getString("param")` → business logic → `call.resolve(JSObject)`.

**iOS** (`HealthPlugin.swift`):
- Methods registered in the `pluginMethods` array as `CAPPluginMethod`.
- Each `@objc func` receives a `CAPPluginCall`, extracts params, calls into `Health` (the business logic class), and resolves/rejects on `DispatchQueue.main`.
- Pattern: `call.getString("param")` → `implementation.method(...)` → completion callback → `call.resolve(dict)`.

### Current TypeScript interface

```typescript
// definitions.d.ts — the full current API surface
interface HealthPlugin {
    isAvailable(): Promise<AvailabilityResult>;
    requestAuthorization(options: AuthorizationOptions): Promise<AuthorizationStatus>;
    checkAuthorization(options: AuthorizationOptions): Promise<AuthorizationStatus>;
    readSamples(options: QueryOptions): Promise<ReadSamplesResult>;
    saveSample(options: WriteSampleOptions): Promise<void>;
    getPluginVersion(): Promise<{ version: string }>;
}

interface HealthSample {
    dataType: HealthDataType;  // 'steps' | 'heartRate' | ...
    value: number;
    unit: HealthUnit;
    startDate: string;         // ISO 8601
    endDate: string;           // ISO 8601
    sourceName?: string;
    sourceId?: string;
}
```

### New methods to add

The fork needs two new methods on the `HealthPlugin` interface:

```typescript
interface HealthPlugin {
    // ... existing methods ...

    /**
     * Get a changes token for the given data types.
     * On Android: wraps HealthConnectClient.getChangesToken()
     * On iOS: creates an initial HKAnchoredObjectQuery with nil anchor,
     *         returns the serialized anchor as the "token"
     */
    getChangesToken(options: {
        dataTypes: HealthDataType[];
    }): Promise<{ token: string }>;

    /**
     * Get all changes since the given token.
     * On Android: wraps HealthConnectClient.getChanges() in a loop
     * On iOS: runs HKAnchoredObjectQuery with the deserialized anchor
     *
     * Returns new/modified samples and a new token for next call.
     */
    getChanges(options: {
        token: string;
    }): Promise<{
        samples: HealthSample[];
        nextToken: string;
        /** True if the token was invalid/expired and a full re-read is needed */
        tokenExpired: boolean;
    }>;
}
```

The `token` field is opaque to JS — on Android it's the string token from Health Connect, on iOS it's Base64-encoded `NSKeyedArchiver` data of the `HKQueryAnchor`.

### How the app consumes the plugin today

`NativeFitnessProviderClient` (`src/services/fitness/FitnessProviderClient.ts`) wraps the plugin:

```typescript
// The interface that FitnessSyncService depends on
interface FitnessProviderClient {
    getAvailability(provider): Promise<FitnessAvailability>;
    isProviderSupported(provider): boolean;
    getPermissionState(provider): Promise<FitnessPermissionState>;
    requestPermissions(provider): Promise<FitnessPermissionState>;
    readStepSamples(params: { provider, from: DateTime, to: DateTime }): Promise<StepSample[]>;
    readHeartRateSamples(params: { provider, from: DateTime, to: DateTime }): Promise<HeartRateSample[]>;
}
```

`FitnessSyncService` calls `readStepSamples` and `readHeartRateSamples` with a time range computed from the overlap window. To switch to change-tracking, the interface would gain new methods:

```typescript
interface FitnessProviderClient {
    // ... existing methods ...

    /** Get or initialize a changes token for this provider */
    getChangesToken(provider: FitnessProvider): Promise<string>;

    /** Get all new/modified samples since the token */
    getChanges(provider: FitnessProvider, token: string): Promise<{
        stepSamples: StepSample[];
        heartRateSamples: HeartRateSample[];
        nextToken: string;
        tokenExpired: boolean;
    }>;
}
```

### Where tokens/anchors should be persisted

The `fitness_sync_state` table already has a `sync_cursor` column (currently unused, always NULL):

```sql
-- In supabase/schemas/tables_etc.sql
CREATE TABLE fitness_sync_state (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    provider TEXT NOT NULL,
    sync_cursor TEXT,              -- <-- store the token/anchor here
    last_synced_through_at TIMESTAMPTZ,
    last_synced_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);
```

`FitnessSyncService.upsertSyncState` already writes to this table. The change is to also read/write `sync_cursor` as the token. On Android this is the changes token string. On iOS this is the Base64-encoded anchor data.

### Android-specific implementation notes

`HealthDataType.kt` maps string identifiers to record classes. It will need a `recordClass` property (or method) to build `ChangesTokenRequest(recordTypes = ...)`. Currently it maps to permissions strings only:

```kotlin
enum class HealthDataType(val identifier: String, ...) {
    STEPS("steps", ...),
    HEART_RATE("heartRate", ...),
    // Need to add: val recordClass: KClass<out Record>
    // STEPS → StepsRecord::class
    // HEART_RATE → HeartRateRecord::class
}
```

The `getChanges` loop in `HealthManager` must handle `ChangesResponse.changesTokenExpired` — when true, return `tokenExpired: true` so the service can fall back to a full time-range read.

### iOS-specific implementation notes

`HKAnchoredObjectQuery` needs a specific `HKQuantityType` per query. Since we read both steps and heart rate, we need two separate anchored queries (one per data type), each with its own persisted anchor.

The anchor serialization pattern:

```swift
// Serialize to Base64 for transport through JS bridge
let data = try NSKeyedArchiver.archivedData(withRootObject: anchor, requiringSecureCoding: true)
let base64Token = data.base64EncodedString()

// Deserialize from Base64
let data = Data(base64Encoded: base64Token)!
let anchor = try NSKeyedUnarchiver.unarchivedObject(ofClass: HKQueryAnchor.self, from: data)
```

iOS anchors never expire (unlike Android's 30-day token expiry). But the first call with a `nil` anchor returns ALL historical data, which could be very large. Consider adding a `predicate` to limit the initial fetch to the last 24-48 hours.

### Service-side changes in FitnessSyncService.ts

The sync flow would change from:

```
1. Compute overlap window (from, to)
2. readStepSamples(from, to) + readHeartRateSamples(from, to)
3. Dedupe raw samples against existing keys
4. Insert new raw samples
5. Generate rollups from ALL samples in window
6. Dirty-check and persist rollups
7. Advance last_synced_through_at to now
```

To:

```
1. Read sync_cursor from fitness_sync_state (or null if first run)
2. If null: call getChangesToken(), do a one-time full read via readSamples (existing method), save token
3. If token exists: call getChanges(token)
4. If tokenExpired: fall back to full read, get new token
5. Process ONLY the returned samples (no dedupe needed — they're guaranteed new)
6. Generate rollups from only the new samples
7. Merge new rollups with existing (UPDATE changed, INSERT new — same two-phase but much smaller set)
8. Save nextToken to sync_cursor
```

The overlap window, `FITNESS_INCREMENTAL_SYNC_OVERLAP_MINUTES`, and the dedupe key pre-check logic in `persistRawSamples` all become unnecessary with change-tracking.

### Testing the fork

The existing test file `src/services/fitness/__tests__/FitnessSyncService.test.ts` mocks `FitnessProviderClient`. New tests should:

1. Mock `getChangesToken` and `getChanges` on the provider client interface.
2. Test the happy path: token exists → getChanges returns 5 new samples → only those 5 are processed.
3. Test token expiry: `tokenExpired: true` → falls back to full time-range read → saves new token.
4. Test first run: no sync_cursor → initializes token → does full read.
5. Test empty changes: getChanges returns 0 samples → no writes, token updated.

## Recommendation

1. **Short term**: fix the Health Connect query to start at midnight local time for Samsung step reads. This unblocks whole-day quantity commitments immediately.
2. **Short term**: reduce the sync overlap from 180 minutes to 15 minutes. This eliminates most wasted re-reads at negligible risk.
3. **Medium term**: build a custom Capacitor step sensor plugin using `TYPE_STEP_COUNTER` with a foreground service for active commitment windows. This enables time-windowed and interval commitments on Android.
4. **Medium term**: fork `@capgo/capacitor-health` to implement the Health Connect Changes API (Option 3 above). This eliminates the overlap problem entirely and handles watch-disconnect backfills correctly.
5. **UX**: surface clear guidance to Samsung users about battery optimization exemption. Consider detecting Samsung devices and showing a setup prompt.
6. **Graceful degradation**: for commitment types that require granularity, warn users during commitment creation if the app cannot guarantee background step tracking on their device.

---

## PR Description

```
## What
- Add `getChangesToken()` and `getChanges()` methods to the plugin, wrapping Health Connect's Changes API (Android) and HealthKit's `HKAnchoredObjectQuery` (iOS) for efficient incremental sync.

## Why
- The existing `readSamples` API filters on activity timestamps, not write time. Late-arriving data (e.g., watch reconnecting after a disconnect) is missed unless the caller re-reads a large overlap window. A 180-minute overlap re-reads ~99% duplicate data every sync cycle and still can't handle disconnects longer than 3 hours.
- The Changes API returns only new/modified records since a token was issued, eliminating the overlap entirely and handling backfills correctly. This is Google's recommended sync pattern for Health Connect.

## How
- **TypeScript** (`src/definitions.ts`): Extended `HealthSample` with optional `recordId` and `lastModifiedTime` fields. Added 4 new interfaces (`GetChangesTokenOptions`, `GetChangesTokenResult`, `GetChangesOptions`, `GetChangesResult`) and 2 new methods on `HealthPlugin`. Full JSDoc on all new types and methods.
- **Android** (`HealthManager.kt`, `HealthPlugin.kt`): `getChangesToken` wraps `HealthConnectClient.getChangesToken()` with a per-data-type `ChangesTokenRequest`. `getChanges` wraps `HealthConnectClient.getChanges()` in a pagination loop (`response.hasMore`), filters for `UpsertionChange` events, and routes records through a 10-branch `when` block covering all supported data types. Adds `recordId` and `lastModifiedTime` from record metadata. Auto-recovers on token expiry by fetching a fresh token and returning `tokenExpired: true`. Only treats exceptions with "token" in the message as expiry — other exceptions propagate normally.
- **iOS** (`Health.swift`, `HealthPlugin.swift`): `getChangesToken` runs an `HKAnchoredObjectQuery` with nil anchor (optionally filtered by a `since` date predicate) and serializes the resulting anchor to Base64 via `NSKeyedArchiver`. `getChanges` deserializes the anchor, runs a new anchored query, converts results using extracted `convertQuantitySample`/`convertCategorySample` helpers (shared with `readSamples`), and adds `recordId` (UUID). On invalid token, auto-recovers via `getChangesToken` and returns `tokenExpired: true`. Anchors never expire on iOS, but deserialization failure triggers the same recovery path.
- **Web** (`src/web.ts`): Stub implementations that throw `this.unimplemented()`.
- **README.md**: Auto-regenerated via `bun run build` / `docgen`.
- Refactored iOS `readSamples` to use extracted `convertQuantitySample`/`convertCategorySample` helpers, eliminating duplication between `readSamples` and `getChanges`.

## Testing
- `bun run verify:web` — TypeScript compilation and Rollup bundle pass.
- `bun run verify:android` — Gradle `compileDebugKotlin` passes (full Android build succeeds).
- `bun run verify:ios` — `xcodebuild build` passes for iOS.
- `bun run fmt` — ESLint, Prettier, and SwiftLint all pass clean.
- `bun run build` — Full build including docgen completes successfully.

## Not Tested
- End-to-end on a physical Android device with Health Connect installed (requires a real device with health data and a paired wearable for the watch-disconnect scenario).
- End-to-end on a physical iOS device with HealthKit data.
- Token expiry recovery on Android (requires waiting 30 days or manually invalidating a token at the Health Connect level).
- Behavior with very large anchor payloads on iOS (initial nil-anchor query against a health store with years of data — mitigated by the optional `since` parameter).
- Concurrent calls to `getChanges` with the same token (undefined behavior at the Health Connect level — callers should serialize access).
```
