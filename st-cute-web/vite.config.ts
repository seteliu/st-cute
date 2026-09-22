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
        // 刻意保持 changeOrigin=false：让后端收到的 Host 头与浏览器 Origin 一致（均为 9662），
        // 从而在开发态也满足后端的同源校验；若改为 true，Host 会被改写成 9661 而 Origin 仍是 9662，
        // 不同源将导致设置保存等 POST 请求被 403 拒绝
        changeOrigin: false
      },
      '/ws': {
        target: 'ws://localhost:9661',
        ws: true
      }
    }
  }
})
