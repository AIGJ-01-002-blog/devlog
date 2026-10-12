/// <reference types="vitest/config" />
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 서버(5173)는 API·로그인·서버가 그리는 화면 주소를 백엔드(8080)로 넘긴다.
const backend = 'http://localhost:8080'

/**
 * main.tsx는 언어 사전을 받은 뒤 화면(App)을 불러온다(동적 import). 그러면 브라우저가 main을 실행한 뒤에야 화면 묶음을
 * 받기 시작하니, index.html에 화면 묶음과 그 묶음이 바로 쓰는 파일을 modulepreload로 적어 처음부터 함께 받게 한다.
 */
function preloadApp(): Plugin {
  return {
    name: 'devlog-preload-app',
    apply: 'build',
    transformIndexHtml: {
      order: 'post',
      handler(html, ctx) {
        const bundle = ctx.bundle
        if (!bundle) return html
        const chunks = Object.values(bundle).filter((c) => c.type === 'chunk')
        const app = chunks.find((c) => c.facadeModuleId?.endsWith('/src/App.tsx'))
        if (!app) throw new Error('App 묶음을 찾지 못했습니다 (main.tsx가 App을 불러오는 방식이 바뀌었나요?)')
        const files = new Set<string>()
        const visit = (file: string) => {
          if (files.has(file)) return
          files.add(file)
          const chunk = bundle[file]
          if (chunk?.type === 'chunk') chunk.imports.forEach(visit)
        }
        visit(app.fileName)
        const links = [...files].filter((f) => !html.includes(`/${f}"`))
          .map((f) => `    <link rel="modulepreload" crossorigin href="/${f}">`).join('\n')
        return html.replace(/( *)<\/head>/, (_, indent: string) => `${links}\n${indent}</head>`)
      },
    },
  }
}

export default defineConfig({
  plugins: [react(), preloadApp()],
  server: {
    proxy: {
      '/api': backend,
      '/oauth2': backend,
      '/login/oauth2': backend,
      '/actuator': backend,
    },
  },
  build: {
    outDir: 'dist',
    // 인라인 스크립트를 만들지 않는다 (CSP script-src 'self')
    modulePreload: { polyfill: false },
    sourcemap: false,
    // 글꼴은 CSS 안에 data: 주소로 넣지 않는다. 작은 한글 조각 글꼴 44개(woff 포함)가 CSS에 들어가 CSS가 524KB가 됐고,
    // CSS는 화면을 그리기 전에 다 받아야 해서 첫 화면이 그만큼 늦었다. 파일로 두면 쓰는 글자의 조각만 받는다.
    assetsInlineLimit: (file) => (/\.woff2?$/.test(file) ? false : undefined),
  },
  test: {
    environment: 'jsdom',
    // theme.test.ts가 styles.css 원문(?raw)을 읽는다
    css: { include: [/styles\.css/] },
  },
})
