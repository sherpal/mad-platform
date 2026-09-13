import { resolve } from 'path'
import { createHtmlPlugin } from 'vite-plugin-html'
import { scalaMetadata } from "./scala-metadata"

import { defineConfig } from 'vite'

const scalaVersion = scalaMetadata.scalaVersion

export default defineConfig(({ command, mode, ssrBuild }) => {

  const htmlPlugin = createHtmlPlugin()

  const mainJS = `/target/scala-${scalaVersion}/frontend-${mode === 'production' ? 'opt' : 'fastopt'}/main.js`
  console.log('mainJS', mainJS)
  const script = `<script type="module" src="${mainJS}"></script>`

  const base = "/mad-the-game/"

  return {
    publicDir: './public',
    plugins: createHtmlPlugin({
      minify: process.env.NODE_ENV === 'production',
      inject: {
        data: {
          script
        }
      }
    }),
    server: {
      port: 3000,
      open: base
    },
    base: base
  }
})
