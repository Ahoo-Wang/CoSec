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

import { defineConfig } from 'vite';
import dts from 'unplugin-dts/vite';
import react, { reactCompilerPreset } from '@vitejs/plugin-react';
import babel from '@rolldown/plugin-babel';

export default defineConfig({
  build: {
    sourcemap: true,
    lib: {
      entry: 'src/index.ts',
      name: 'CoSecReact',
      fileName: format =>
        format === 'es' ? 'index.es.js' : `index.${format}.cjs`,
    },
    rolldownOptions: {
      external: [
        'react',
        'react-dom',
        'react/jsx-runtime',
        'react/jsx-dev-runtime',
        'react/compiler-runtime',
        'react-compiler-runtime',
        '@ahoo-wang/cosec-fetcher',
        '@ahoo-wang/fetcher-react',
      ],
      output: {
        keepNames: true,
        globals: {
          react: 'React',
          'react-dom': 'ReactDOM',
          'react/jsx-runtime': 'ReactJSXRuntime',
          'react/jsx-dev-runtime': 'ReactJSXDevRuntime',
          'react/compiler-runtime': 'ReactCompilerRuntime',
          'react-compiler-runtime': 'ReactCompilerRuntime',
          '@ahoo-wang/cosec-fetcher': 'FetcherCoSec',
          '@ahoo-wang/fetcher-react': 'FetcherReact',
        },
      },
    },
  },
  plugins: [
    dts({
      // `.d.cts` for the `require` condition and `.d.ts` for `import`, so
      // node16/nodenext consumers get CommonJS and ES module types for the
      // matching build. A primary out dir with a module format makes the
      // plugin write every relative specifier with its runtime extension
      // (`./fetcher.js` in `.d.ts`, `./fetcher.cjs` in `.d.cts`), whatever
      // the source wrote.
      outDirs: [{ dir: 'dist', moduleFormat: 'cjs' }, 'dist'],
      tsconfigPath: './tsconfig.json',
    }),
    react(),
    babel({
      presets: [reactCompilerPreset()],
    }),
  ],
});
