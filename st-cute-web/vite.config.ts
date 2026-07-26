import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  esbuild: {
    charset: 'utf8'
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 9662,
    proxy: {
      '/api': {
        target: 'http://localhost:9661',
        changeOrigin: true
      },
      '/ws': {
        target: 'ws://localhost:9661',
        ws: true
      }
    }
  }
})
