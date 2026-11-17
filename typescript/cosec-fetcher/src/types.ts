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

import type { DeviceIdStorage } from './deviceIdStorage.js';
import type { JwtTokenManager } from './jwtTokenManager.js';

/**
 * CoSec HTTP headers enumeration.
 */
export class CoSecHeaders {
  static readonly DEVICE_ID = 'CoSec-Device-Id';
  static readonly SPACE_ID = 'CoSec-Space-Id';
  static readonly AUTHORIZATION = 'Authorization';
  static readonly REQUEST_ID = 'CoSec-Request-Id';
}

export class ResponseCodes {
  static readonly UNAUTHORIZED = 401;
  static readonly FORBIDDEN = 403;
  static readonly TOO_MANY_REQUESTS = 429;
}

export interface AppIdCapable {
  /**
   * Does nothing: CoSec 8.0 does not read a request's app. The app of a
   * request is the `aud` of its token, set by whoever issued it.
   *
   * @deprecated Remove it. Kept so existing configuration still compiles;
   * it is removed in the next major version.
   */
  appId?: string;
}

export interface DeviceIdStorageCapable {
  deviceIdStorage: DeviceIdStorage;
}

export interface JwtTokenManagerCapable {
  tokenManager: JwtTokenManager;
}

/**
 * CoSec options interface.
 */
export interface CoSecOptions
  extends AppIdCapable, DeviceIdStorageCapable, JwtTokenManagerCapable {}
