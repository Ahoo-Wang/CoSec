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

import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import { useRouter } from 'vitepress'
import { defineAsyncComponent } from 'vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  enhanceApp({ app }) {
    // vitepress-plugin-mermaid turns ```mermaid blocks into <Mermaid>; the component renders them,
    // follows the light/dark theme and adds zoom and expand.
    app.component('Mermaid', defineAsyncComponent(() => import('./Mermaid.vue')))
  },
  setup() {
    if (typeof window !== 'undefined') {
      // The gtag snippet only reports the first page load; report client-side navigations too.
      const router = useRouter()
      router.onAfterRouteChanged = (to: string) => {
        ;(window as any).gtag?.('event', 'page_view', { page_path: to })
      }
    }
  },
} satisfies Theme
