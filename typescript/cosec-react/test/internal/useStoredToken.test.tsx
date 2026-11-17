/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { act, renderHook } from '@testing-library/react';
import { renderToString } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { TokenStorage } from '@ahoo-wang/cosec-fetcher';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { useStoredToken } from '../../src/internal/useStoredToken';

const base64Url = (value: object) =>
  btoa(JSON.stringify(value))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
const jwt = (sub: string) =>
  `e30.${base64Url({ sub, exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;
const pair = (sub: string) => ({
  accessToken: jwt(sub),
  refreshToken: jwt(sub),
});
const storage = () => new TokenStorage({ storage: new InMemoryStorage() });

describe('useStoredToken', () => {
  it('is null until a pair is stored, then follows the storage', async () => {
    const tokenStorage = storage();
    const { result } = renderHook(() => useStoredToken(tokenStorage));
    expect(result.current).toBeNull();

    await act(async () => {
      tokenStorage.signIn(pair('alice'));
    });
    expect(result.current?.access.payload?.sub).toBe('alice');

    await act(async () => {
      tokenStorage.signIn(pair('bob'));
    });
    expect(result.current?.access.payload?.sub).toBe('bob');

    await act(async () => {
      tokenStorage.remove();
    });
    expect(result.current).toBeNull();
  });

  it('starts from the pair already stored', () => {
    const tokenStorage = storage();
    tokenStorage.signIn(pair('alice'));
    const { result } = renderHook(() => useStoredToken(tokenStorage));
    expect(result.current?.access.payload?.sub).toBe('alice');
  });

  it('does not render again when nothing changed', () => {
    const tokenStorage = storage();
    tokenStorage.signIn(pair('alice'));
    let renders = 0;
    const { result, rerender } = renderHook(() => {
      renders++;
      return useStoredToken(tokenStorage);
    });
    const first = result.current;
    const rendered = renders;

    rerender();
    rerender();

    // Only the renders asked for: the snapshot is the same object, so React
    // does not render a second time to settle it.
    expect(renders).toBe(rendered + 2);
    expect(result.current).toBe(first);
  });

  it('unsubscribes on unmount, and subscribes once per storage', () => {
    const tokenStorage = storage();
    const remove = vi.fn();
    const addListener = vi
      .spyOn(tokenStorage, 'addListener')
      .mockImplementation(() => remove);
    const { rerender, unmount } = renderHook(() =>
      useStoredToken(tokenStorage),
    );
    rerender();
    expect(addListener).toHaveBeenCalledTimes(1);
    expect(remove).not.toHaveBeenCalled();

    unmount();
    expect(remove).toHaveBeenCalledTimes(1);
  });

  it('resubscribes when the storage changes', async () => {
    const first = storage();
    const second = storage();
    second.signIn(pair('bob'));
    const { result, rerender } = renderHook(
      ({ tokenStorage }) => useStoredToken(tokenStorage),
      { initialProps: { tokenStorage: first } },
    );
    expect(result.current).toBeNull();

    rerender({ tokenStorage: second });
    expect(result.current?.access.payload?.sub).toBe('bob');

    await act(async () => {
      first.signIn(pair('alice'));
    });
    expect(result.current?.access.payload?.sub).toBe('bob');
    await act(async () => {
      second.signIn(pair('carol'));
    });
    expect(result.current?.access.payload?.sub).toBe('carol');
  });

  it('renders null on the server, whatever the storage holds', () => {
    const tokenStorage = storage();
    tokenStorage.signIn(pair('alice'));
    function Probe() {
      const token = useStoredToken(tokenStorage);
      return <span>{token ? 'signed in' : 'signed out'}</span>;
    }
    expect(renderToString(<Probe />)).toContain('signed out');
  });
});
