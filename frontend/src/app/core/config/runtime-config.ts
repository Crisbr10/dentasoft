import { HttpClient } from '@angular/common/http';
import {
  EnvironmentProviders,
  Injectable,
  inject,
  provideAppInitializer,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

/**
 * Public URLs of this deployment. They are never written in the code: the build writes
 * them to config.json from DENTASOFT_URLS_API and DENTASOFT_URLS_AUTH (ADR 0006).
 */
export interface RuntimeConfig {
  readonly apiUrl: string;
  readonly authUrl: string;
}

export const RUNTIME_CONFIG_FILE = 'config.json';

@Injectable({ providedIn: 'root' })
export class RuntimeConfigStore {
  private readonly http = inject(HttpClient);
  private readonly current = signal<RuntimeConfig | null>(null);

  /** The loaded configuration. Available once the app has started. */
  get config(): RuntimeConfig {
    const config = this.current();
    if (!config) {
      throw new Error('Runtime configuration is not loaded');
    }
    return config;
  }

  async load(): Promise<void> {
    const raw = await firstValueFrom(this.http.get<unknown>(RUNTIME_CONFIG_FILE));
    this.current.set(parseRuntimeConfig(raw));
  }
}

export function parseRuntimeConfig(raw: unknown): RuntimeConfig {
  if (typeof raw !== 'object' || raw === null) {
    throw new Error(`${RUNTIME_CONFIG_FILE} is not a JSON object`);
  }
  const values = raw as Record<string, unknown>;
  return { apiUrl: requireUrl(values, 'apiUrl'), authUrl: requireUrl(values, 'authUrl') };
}

function requireUrl(values: Record<string, unknown>, key: keyof RuntimeConfig): string {
  const value = values[key];
  if (typeof value !== 'string' || !URL.canParse(value)) {
    throw new Error(`${RUNTIME_CONFIG_FILE}: ${key} must be an absolute URL`);
  }
  return value.replace(/\/+$/, '');
}

/** Loads config.json before the app starts; the app does not start without it. */
export function provideRuntimeConfig(): EnvironmentProviders {
  return provideAppInitializer(() => inject(RuntimeConfigStore).load());
}
