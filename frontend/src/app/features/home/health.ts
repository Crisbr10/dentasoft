import { HttpErrorResponse } from '@angular/common/http';

/** Server state shown on the home page. */
export type HealthStatus = 'checking' | 'up' | 'down' | 'unreachable';

/** Body of Spring Boot's /actuator/health. */
export interface HealthResponse {
  readonly status: string;
}

export const HEALTH_PATH = '/actuator/health';

export function toHealthStatus(
  loading: boolean,
  value: HealthResponse | undefined,
  error: unknown,
): HealthStatus {
  if (loading) {
    return 'checking';
  }
  if (error) {
    // Spring Boot answers 503 with a body when the app is running but a component is down.
    const reachable = error instanceof HttpErrorResponse && error.status > 0;
    return reachable ? 'down' : 'unreachable';
  }
  return value?.status === 'UP' ? 'up' : 'down';
}
