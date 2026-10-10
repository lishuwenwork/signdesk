import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 9500,
    proxy: { '/api': { target: 'http://127.0.0.1:10500', changeOrigin: false } },
  },
  build: { target: 'es2022' },
})
