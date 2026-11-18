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

import { render } from '@testing-library/react';
import { TokenStorage } from '@ahoo-wang/cosec-fetcher';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { RouteGuard, SecurityProvider } from '../../src';

describe('RouteGuard', () => {
  it('calls onUnauthorized after commit, not on every render', () => {
    const tokenStorage = new TokenStorage({
      key: 'purity-guard',
      storage: new InMemoryStorage(),
    });
    const onUnauthorized = vi.fn();
    function Parent() {
      return (
        <SecurityProvider tokenStorage={tokenStorage}>
          <RouteGuard onUnauthorized={onUnauthorized}>
            <div>Protected</div>
          </RouteGuard>
        </SecurityProvider>
      );
    }
    // Each rerender renders the guard again, in the same mounted tree.
    const { rerender } = render(<Parent />);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);

    rerender(<Parent />);
    rerender(<Parent />);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    tokenStorage.destroy();
  });
});
