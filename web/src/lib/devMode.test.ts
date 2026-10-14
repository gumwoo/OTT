import { describe, expect, it } from 'vitest';
import { isDevMode, syncDevModeFromUrl } from './devMode';

describe('developer mode', () => {
  it('is off unless turned on', () => {
    expect(isDevMode()).toBe(false);
  });

  it('turns on with ?devtools=1, stays on, and turns off with ?devtools=0', () => {
    syncDevModeFromUrl('?devtools=1');
    expect(isDevMode()).toBe(true);

    syncDevModeFromUrl('?q=other');
    expect(isDevMode()).toBe(true);

    syncDevModeFromUrl('?devtools=0');
    expect(isDevMode()).toBe(false);
  });

  it('ignores other values', () => {
    syncDevModeFromUrl('?devtools=true');
    expect(isDevMode()).toBe(false);
  });
});
