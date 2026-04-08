export type HealthDataType =
  | 'steps'
  | 'distance'
  | 'calories'
  | 'heartRate'
  | 'weight'
  | 'sleep'
  | 'respiratoryRate'
  | 'oxygenSaturation'
  | 'restingHeartRate'
  | 'heartRateVariability';

export type HealthUnit = 'count' | 'meter' | 'kilocalorie' | 'bpm' | 'kilogram' | 'minute' | 'percent' | 'millisecond';

export interface AuthorizationOptions {
  /** Data types that should be readable after authorization. */
  read?: HealthDataType[];
  /** Data types that should be writable after authorization. */
  write?: HealthDataType[];
}

export interface AuthorizationStatus {
  readAuthorized: HealthDataType[];
  readDenied: HealthDataType[];
  writeAuthorized: HealthDataType[];
  writeDenied: HealthDataType[];
}

export interface AvailabilityResult {
  available: boolean;
  /** Platform specific details (for debugging/diagnostics). */
  platform?: 'ios' | 'android' | 'web';
  reason?: string;
}

export interface QueryOptions {
  /** The type of data to retrieve from the health store. */
  dataType: HealthDataType;
  /** Inclusive ISO 8601 start date (defaults to now - 1 day). */
  startDate?: string;
  /** Exclusive ISO 8601 end date (defaults to now). */
  endDate?: string;
  /** Maximum number of samples to return (defaults to 100). */
  limit?: number;
  /** Return results sorted ascending by start date (defaults to false). */
  ascending?: boolean;
}

export type SleepState = 'inBed' | 'asleep' | 'awake' | 'rem' | 'deep' | 'light';

export interface HealthSample {
  dataType: HealthDataType;
  value: number;
  unit: HealthUnit;
  startDate: string;
  endDate: string;
  sourceName?: string;
  sourceId?: string;
  /** For sleep data, indicates the sleep state (e.g., 'asleep', 'awake', 'rem', 'deep', 'light'). */
  sleepState?: SleepState;
  /** Unique record identifier from the native health store (populated by getChanges). */
  recordId?: string;
  /** When the record was last modified in the health store, ISO 8601 (populated by getChanges). */
  lastModifiedTime?: string;
}

export interface ReadSamplesResult {
  samples: HealthSample[];
}

export type WorkoutType =
  | 'running'
  | 'cycling'
  | 'walking'
  | 'swimming'
  | 'yoga'
  | 'strengthTraining'
  | 'hiking'
  | 'tennis'
  | 'basketball'
  | 'soccer'
  | 'americanFootball'
  | 'baseball'
  | 'crossTraining'
  | 'elliptical'
  | 'rowing'
  | 'stairClimbing'
  | 'traditionalStrengthTraining'
  | 'waterFitness'
  | 'waterPolo'
  | 'waterSports'
  | 'wrestling'
  | 'other';

export interface QueryWorkoutsOptions {
  /** Optional workout type filter. If omitted, all workout types are returned. */
  workoutType?: WorkoutType;
  /** Inclusive ISO 8601 start date (defaults to now - 1 day). */
  startDate?: string;
  /** Exclusive ISO 8601 end date (defaults to now). */
  endDate?: string;
  /** Maximum number of workouts to return (defaults to 100). */
  limit?: number;
  /** Return results sorted ascending by start date (defaults to false). */
  ascending?: boolean;
  /**
   * Anchor for pagination. Use the anchor returned from a previous query to continue from that point.
   * On iOS, this uses HKQueryAnchor. On Android, this uses Health Connect's pageToken.
   * Omit this parameter to start from the beginning.
   */
  anchor?: string;
}

export interface Workout {
  /** The type of workout. */
  workoutType: WorkoutType;
  /** Duration of the workout in seconds. */
  duration: number;
  /** Total energy burned in kilocalories (if available). */
  totalEnergyBurned?: number;
  /** Total distance in meters (if available). */
  totalDistance?: number;
  /** ISO 8601 start date of the workout. */
  startDate: string;
  /** ISO 8601 end date of the workout. */
  endDate: string;
  /** Source name that recorded the workout. */
  sourceName?: string;
  /** Source bundle identifier. */
  sourceId?: string;
  /** Additional metadata (if available). */
  metadata?: Record<string, string>;
}

export interface QueryWorkoutsResult {
  workouts: Workout[];
  /**
   * Anchor for the next page of results. Pass this value as the anchor parameter in the next query
   * to continue pagination. If undefined or null, there are no more results.
   */
  anchor?: string;
}

export interface WriteSampleOptions {
  dataType: HealthDataType;
  value: number;
  /**
   * Optional unit override. If omitted, the default unit for the data type is used
   * (count for `steps`, meter for `distance`, kilocalorie for `calories`, bpm for `heartRate`, kilogram for `weight`).
   */
  unit?: HealthUnit;
  /** ISO 8601 start date for the sample. Defaults to now. */
  startDate?: string;
  /** ISO 8601 end date for the sample. Defaults to startDate. */
  endDate?: string;
  /** Metadata key-value pairs forwarded to the native APIs where supported. */
  metadata?: Record<string, string>;
}

export type BucketType = 'hour' | 'day' | 'week' | 'month';

export type AggregationType = 'sum' | 'average' | 'min' | 'max';

export interface QueryAggregatedOptions {
  /** The type of data to aggregate from the health store. */
  dataType: HealthDataType;
  /** Inclusive ISO 8601 start date (defaults to now - 1 day). */
  startDate?: string;
  /** Exclusive ISO 8601 end date (defaults to now). */
  endDate?: string;
  /** Time bucket for aggregation (defaults to 'day'). */
  bucket?: BucketType;
  /** Aggregation operation to perform (defaults to 'sum'). */
  aggregation?: AggregationType;
}

export interface AggregatedSample {
  /** ISO 8601 start date of the bucket. */
  startDate: string;
  /** ISO 8601 end date of the bucket. */
  endDate: string;
  /** Aggregated value for the bucket. */
  value: number;
  /** Unit of the aggregated value. */
  unit: HealthUnit;
}

export interface QueryAggregatedResult {
  samples: AggregatedSample[];
}

export interface GetChangesTokenOptions {
  /** The data type to track changes for. One token per data type. */
  dataType: HealthDataType;
  /**
   * Optional ISO 8601 date to limit the initial fetch scope (primarily for iOS).
   * On iOS, limits the initial HKAnchoredObjectQuery to records after this date.
   * On Android, this parameter is ignored (tokens are scoped to "from now" by the API).
   */
  since?: string;
}

export interface GetChangesTokenResult {
  /** Opaque token string for use with getChanges. Platform-specific format. */
  token: string;
}

export interface GetChangesOptions {
  /** The data type to query changes for. Must match the token's data type. */
  dataType: HealthDataType;
  /** The token from a previous getChangesToken or getChanges call. */
  token: string;
}

export interface GetChangesResult {
  /** New or modified samples since the token was issued. */
  samples: HealthSample[];
  /** Token for the next getChanges call. Always present. */
  nextToken: string;
  /**
   * True if the token was invalid or expired. When true, samples will be empty
   * and nextToken will contain a fresh token. The caller should perform a full
   * re-read via readSamples to catch up on missed data.
   * On Android: tokens expire after 30 days of non-use.
   * On iOS: anchors don't expire, but invalid tokens will set this flag.
   */
  tokenExpired: boolean;
}

export interface BackgroundReadPermissionResult {
  /** Whether the Health Connect background-read feature exists on this device. */
  available: boolean;
  /** Whether background reads are currently granted. */
  granted: boolean;
}

export interface HeartRateIntervalNotificationCommitment {
  commitmentId: string;
  taskName: string;
  thresholdBpm: number;
  maxOrMin: 'max' | 'min';
  intervalInMinutes: number;
  completionMetric: number;
  completionMetricType: 'seconds' | 'quantity';
  timePeriodStartAt: string;
  timePeriodEndAt: string;
  reminderLeadMinutes: number;
  staleAfterMinutes: number;
}

export interface ConfigureHeartRateIntervalNotificationsOptions {
  generatedAt: string;
  commitments: HeartRateIntervalNotificationCommitment[];
}

export interface ScheduledHeartRateIntervalReminder {
  commitmentId: string;
  title: string;
  body: string;
  scheduleAt: string;
  dueAt: string;
}

export interface HeartRateIntervalNotificationDebugState {
  generatedAt: string | null;
  commitments: HeartRateIntervalNotificationCommitment[];
  scheduledReminders: ScheduledHeartRateIntervalReminder[];
  nextReconcileAt: string | null;
  lastReconciledAt: string | null;
  backgroundReadAvailable: boolean;
  backgroundReadGranted: boolean;
}

export interface PluginInfoResult {
  /** The native plugin version (semver, e.g. "7.2.14"). */
  version: string;
  /**
   * Git short hash stamped at yalc push time, or "dev" if built without the
   * push script (or "web" when running on the web stub). Use this to verify
   * the running plugin matches the expected source version during local
   * development against yalc.
   */
  buildId: string;
}

export interface HealthPlugin {
  /** Returns whether the current platform supports the native health SDK. */
  isAvailable(): Promise<AvailabilityResult>;
  /** Requests read/write access to the provided data types. */
  requestAuthorization(options: AuthorizationOptions): Promise<AuthorizationStatus>;
  /** Checks authorization status for the provided data types without prompting the user. */
  checkAuthorization(options: AuthorizationOptions): Promise<AuthorizationStatus>;
  /** Reads samples for the given data type within the specified time frame. */
  readSamples(options: QueryOptions): Promise<ReadSamplesResult>;
  /** Writes a single sample to the native health store. */
  saveSample(options: WriteSampleOptions): Promise<void>;

  /**
   * Get the native Capacitor plugin version
   *
   * @returns {Promise<{ version: string }>} a Promise with version for this device
   * @throws An error if something went wrong
   */
  getPluginVersion(): Promise<{ version: string }>;

  /**
   * Returns build metadata for the plugin: both the semver version and a
   * `buildId` (a git short hash stamped at yalc push time, or "dev" if built
   * without the push script). Use this to verify the running plugin matches
   * the expected source version when iterating locally via yalc.
   */
  getPluginInfo(): Promise<PluginInfoResult>;

  /**
   * Opens the Health Connect settings screen (Android only).
   * On iOS, this method does nothing.
   *
   * Use this to direct users to manage their Health Connect permissions
   * or to install Health Connect if not available.
   *
   * @throws An error if Health Connect settings cannot be opened
   */
  openHealthConnectSettings(): Promise<void>;

  /**
   * Shows the app's privacy policy for Health Connect (Android only).
   * On iOS, this method does nothing.
   *
   * This displays the same privacy policy screen that Health Connect shows
   * when the user taps "Privacy policy" in the permissions dialog.
   *
   * The privacy policy URL can be configured by adding a string resource
   * named "health_connect_privacy_policy_url" in your app's strings.xml,
   * or by placing an HTML file at www/privacypolicy.html in your assets.
   *
   * @throws An error if the privacy policy cannot be displayed
   */
  showPrivacyPolicy(): Promise<void>;

  /**
   * Queries workout sessions from the native health store.
   * Supported on iOS (HealthKit) and Android (Health Connect).
   *
   * @param options Query options including optional workout type filter, date range, limit, and sort order
   * @returns A promise that resolves with the workout sessions
   * @throws An error if something went wrong
   */
  queryWorkouts(options: QueryWorkoutsOptions): Promise<QueryWorkoutsResult>;

  /**
   * Queries aggregated health data from the native health store.
   * Aggregates data into time buckets (hour, day, week, month) with operations like sum, average, min, or max.
   * This is more efficient than fetching individual samples for large date ranges.
   *
   * Supported on iOS (HealthKit) and Android (Health Connect).
   *
   * @param options Query options including data type, date range, bucket size, and aggregation type
   * @returns A promise that resolves with the aggregated samples
   * @throws An error if something went wrong
   */
  queryAggregated(options: QueryAggregatedOptions): Promise<QueryAggregatedResult>;

  /**
   * Gets a changes token for tracking new or modified samples of a given data type.
   * Tokens are per-data-type — call once per type you want to track.
   *
   * On Android: wraps HealthConnectClient.getChangesToken().
   * On iOS: initializes an HKAnchoredObjectQuery anchor.
   *
   * @param options Data type and optional initial date filter
   * @returns A promise that resolves with an opaque token string
   * @throws An error if the data type is unsupported or Health SDK is unavailable
   */
  getChangesToken(options: GetChangesTokenOptions): Promise<GetChangesTokenResult>;

  /**
   * Gets all new or modified samples since the given token was issued.
   * Returns only upserts (new/modified records), not deletions.
   *
   * If the token has expired (Android: 30 days of non-use), returns tokenExpired: true
   * with a fresh token in nextToken. The caller should perform a full re-read via
   * readSamples to catch up on missed data, then resume using the new token.
   *
   * On Android: wraps HealthConnectClient.getChanges() with automatic pagination.
   * On iOS: runs HKAnchoredObjectQuery from the saved anchor.
   *
   * @param options Data type and token from a previous getChangesToken or getChanges call
   * @returns A promise with new samples, next token, and optional expiry flag
   * @throws An error if the data type is unsupported or Health SDK is unavailable
   */
  getChanges(options: GetChangesOptions): Promise<GetChangesResult>;

  /** Checks whether Android Health Connect background reads are available and granted. */
  checkBackgroundReadPermission(): Promise<BackgroundReadPermissionResult>;
  /** Requests Android Health Connect background-read permission when supported. */
  requestBackgroundReadPermission(): Promise<BackgroundReadPermissionResult>;
  /** Configures Android-native heart-rate interval reminder ownership. */
  configureHeartRateIntervalNotifications(
    options: ConfigureHeartRateIntervalNotificationsOptions
  ): Promise<void>;
  /** Clears Android-native heart-rate interval reminder ownership. */
  clearHeartRateIntervalNotifications(): Promise<void>;
  /** Returns Android-native HR reminder debug state. */
  getHeartRateIntervalNotificationDebugState(): Promise<HeartRateIntervalNotificationDebugState>;
}
