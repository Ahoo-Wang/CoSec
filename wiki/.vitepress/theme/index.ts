import { h, nextTick, watch, onMounted } from 'vue'
import type { Theme } from 'vitepress'
import DefaultTheme from 'vitepress/theme'
import { useData, useRoute } from 'vitepress'
import { createMermaidRenderer } from 'vitepress-mermaid-renderer'
import mediumZoom from 'medium-zoom'
import './styles/index.css'

const CoSecTheme: Theme = {
  extends: DefaultTheme,
  Layout: () => {
    const { isDark } = useData()

    const initMermaid = () => {
      const mermaidRenderer = createMermaidRenderer({
        theme: isDark.value ? 'dark' : 'default',
        startOnLoad: true,
      })
      mermaidRenderer.setToolbar({
        desktop: {
          zoomIn: 'enabled',
          zoomOut: 'enabled',
          zoomLevel: 'enabled',
          resetView: 'enabled',
          fullscreen: 'enabled',
          copyCode: 'enabled',
          download: 'enabled',
          positions: { vertical: 'top', horizontal: 'right' },
        },
        mobile: {
          zoomIn: 'enabled',
          zoomOut: 'enabled',
          zoomLevel: 'disabled',
          resetView: 'enabled',
          fullscreen: 'enabled',
          copyCode: 'enabled',
          download: 'enabled',
          positions: { vertical: 'bottom', horizontal: 'right' },
        },
      })
    }

    nextTick(() => initMermaid())

    watch(
      () => isDark.value,
      () => nextTick(() => initMermaid()),
    )

    return h(DefaultTheme.Layout)
  },
  setup() {
    const route = useRoute()

    const initZoom = () => {
      mediumZoom('.main img', { background: 'var(--vp-c-bg)' })
    }

    onMounted(() => initZoom())

    watch(
      () => route.path,
      () => nextTick(() => initZoom()),
    )
  },
}

export default CoSecTheme
