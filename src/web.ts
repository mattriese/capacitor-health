import { WebPlugin } from '@capacitor/core';

import type {
  AuthorizationOptions,
  AuthorizationStatus,
  AvailabilityResult,
  GetChangesOptions,
  GetChangesResult,
  GetChangesTokenOptions,
  GetChangesTokenResult,
  HealthPlugin,
  PluginInfoResult,
  QueryAggregatedOptions,
  QueryAggregatedResult,
  QueryOptions,
  QueryWorkoutsOptions,
  QueryWorkoutsResult,
  ReadSamplesResult,
  WriteSampleOptions,
} from './definitions';

export class HealthWeb extends WebPlugin implements HealthPlugin {
  async isAvailable(): Promise<AvailabilityResult> {
    return {
      available: false,
      platform: 'web',
      reason: 'Native health APIs are not accessible in a browser environment.',
    };
  }

  async requestAuthorization(_options: AuthorizationOptions): Promise<AuthorizationStatus> {
    throw this.unimplemented('Health permissions are only available on native platforms.');
  }

  async checkAuthorization(_options: AuthorizationOptions): Promise<AuthorizationStatus> {
    throw this.unimplemented('Health permissions are only available on native platforms.');
  }

  async readSamples(_options: QueryOptions): Promise<ReadSamplesResult> {
    throw this.unimplemented('Reading health data is only available on native platforms.');
  }

  async saveSample(_options: WriteSampleOptions): Promise<void> {
    throw this.unimplemented('Writing health data is only available on native platforms.');
  }

  async getPluginVersion(): Promise<{ version: string }> {
    return { version: 'web' };
  }

  async getPluginInfo(): Promise<PluginInfoResult> {
    return { version: 'web', buildId: 'web' };
  }

  async openHealthConnectSettings(): Promise<void> {
    // No-op on web - Health Connect is Android only
  }

  async showPrivacyPolicy(): Promise<void> {
    // No-op on web - Health Connect privacy policy is Android only
  }

  async queryWorkouts(_options: QueryWorkoutsOptions): Promise<QueryWorkoutsResult> {
    throw this.unimplemented('Querying workouts is only available on native platforms.');
  }

  async queryAggregated(_options: QueryAggregatedOptions): Promise<QueryAggregatedResult> {
    throw this.unimplemented('Querying aggregated data is only available on native platforms.');
  }

  async getChangesToken(_options: GetChangesTokenOptions): Promise<GetChangesTokenResult> {
    throw this.unimplemented('Changes tracking is only available on native platforms.');
  }

  async getChanges(_options: GetChangesOptions): Promise<GetChangesResult> {
    throw this.unimplemented('Changes tracking is only available on native platforms.');
  }

  async checkBackgroundReadPermission() {
    return {
      available: false,
      granted: false,
    };
  }

  async requestBackgroundReadPermission() {
    return {
      available: false,
      granted: false,
    };
  }

  async configureHeartRateIntervalNotifications(): Promise<void> {
    throw this.unimplemented('Heart-rate notification ownership is only available on Android.');
  }

  async clearHeartRateIntervalNotifications(): Promise<void> {
    return;
  }

  async getHeartRateIntervalNotificationDebugState() {
    return {
      generatedAt: null,
      commitments: [],
      scheduledReminders: [],
      nextReconcileAt: null,
      lastReconciledAt: null,
      backgroundReadAvailable: false,
      backgroundReadGranted: false,
    };
  }
}
