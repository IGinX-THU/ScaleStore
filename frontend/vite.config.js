import { defineConfig } from 'vite'

export default defineConfig({
  server: {
    port: 5173,
    proxy: {
      '/storage': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/access': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/config': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/metadata': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
  },
})
