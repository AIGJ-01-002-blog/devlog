import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { loadDictionary } from './lib/i18n'
// 글꼴 (072): 본문 IBM Plex Sans KR, 제목 함렛. 한글은 글자 범위별 작은 파일로 나뉘어 쓰는 글자만 받는다
import '@fontsource/ibm-plex-sans-kr/400.css'
import '@fontsource/ibm-plex-sans-kr/600.css'
import '@fontsource/ibm-plex-sans-kr/700.css'
import '@fontsource/hahmlet/700.css'
import '@fontsource/hahmlet/800.css'
import './styles.css'

// 화면 모듈 몇 곳은 불러올 때(모듈 실행 시점) t()로 문장을 번역해 두므로, 사전을 다 받은 뒤에 화면 모듈을 불러온다.
// 화면 묶음은 index.html의 modulepreload로 이미 받는 중이라(vite.config.ts) 한국어 화면은 기다림이 늘지 않는다.
loadDictionary().then(() => Promise.all([import('./App'), import('./lib/auth')])).then(([{ App }, { AuthProvider }]) => {
  const root = document.getElementById('root')!
  // 서버가 넣은 첫 화면 HTML은 검색 엔진·JS 없는 환경용이다. React가 그 자리를 새로 그린다.
  root.innerHTML = ''
  createRoot(root).render(
    <StrictMode>
      <AuthProvider>
        <App />
      </AuthProvider>
    </StrictMode>,
  )
})
