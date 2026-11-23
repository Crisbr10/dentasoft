import { HttpErrorResponse } from '@angular/common/http';

import { toHealthStatus } from './health';

describe('toHealthStatus', () => {
  it('is checking while the request runs', () => {
    expect(toHealthStatus(true, undefined, undefined)).toBe('checking');
  });

  it('is up when the server reports UP', () => {
    expect(toHealthStatus(false, { status: 'UP' }, undefined)).toBe('up');
  });

  it('is down when the server reports another status', () => {
    expect(toHealthStatus(false, { status: 'OUT_OF_SERVICE' }, undefined)).toBe('down');
  });

  it('is down when the server answers with an error status', () => {
    const error = new HttpErrorResponse({ status: 503, error: { status: 'DOWN' } });
    expect(toHealthStatus(false, undefined, error)).toBe('down');
  });

  it('is unreachable when there is no answer', () => {
    expect(toHealthStatus(false, undefined, new HttpErrorResponse({ status: 0 }))).toBe(
      'unreachable',
    );
    expect(toHealthStatus(false, undefined, new Error('offline'))).toBe('unreachable');
  });
});
