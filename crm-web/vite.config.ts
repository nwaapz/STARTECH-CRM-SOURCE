import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [
    vue(),
    {
      name: 'strip-crossorigin',
      transformIndexHtml(html) {
        return html.replace(/ crossorigin/g, '')
      },
    },
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
    mainFields: ['browser', 'module', 'main', 'jsnext:main', 'jsnext'],
  },
  base: '/',
  build: {
    outDir: resolve(__dirname, '../and_studio_proj/app/src/main/assets/crm'),
    emptyOutDir: true,
  },
})
