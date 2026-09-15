import { defineConfig } from 'vite'

export default defineConfig(({}) => {

  const base = "/mad-the-game/"

  return {
    publicDir: './public',
    plugins: [],
    server: {
      port: 3000,
      open: base
    },
    base: base,
    build: {
      chunkSizeWarningLimit: 2000,
      rolldownOptions: {
        output: {
          codeSplitting: {
            groups: [
              {
                name: 'ui5',
                test: /node_modules[\\/]@ui5/
              }
            ]
          }
        }
      }
    }
  }
})
