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
    // Any answer with an HTTP status means the server is there but not healthy: Spring Boot
    // answers 503 when a component is down; other statuses (404, 401...) are treated alike.
    const reachable = error instanceof HttpErrorResponse && error.status > 0;
    return reachable ? 'down' : 'unreachable';
  }
  return value?.status === 'UP' ? 'up' : 'down';
}
