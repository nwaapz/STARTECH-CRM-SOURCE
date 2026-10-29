import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import { writeFileSync } from 'fs'

const HTACCESS = `RewriteEngine On
RewriteBase /crm/

# Pass Authorization to PHP (cPanel/Apache often strips it)
RewriteCond %{HTTP:Authorization} .
RewriteRule .* - [E=HTTP_AUTHORIZATION:%{HTTP:Authorization}]

# Existing sync PHP files
RewriteRule ^api/sync/ - [L]

# Front-controller for /api/*
RewriteRule ^api/index\\.php$ - [L]
RewriteCond %{REQUEST_URI} ^/crm/api/
RewriteCond %{REQUEST_FILENAME} !-f
RewriteRule ^api/(.*)$ api/index.php [QSA,L]

# Vue SPA
RewriteRule ^index\\.html$ - [L]
RewriteCond %{REQUEST_FILENAME} !-f
RewriteCond %{REQUEST_FILENAME} !-d
RewriteRule . index.html [L]
`

export default defineConfig(({ mode }) => {
  const remote = mode === 'remote'
  const hostOut = resolve(__dirname, '../crm-php/host')
  return {
    plugins: [
      vue(),
      {
        name: 'strip-crossorigin',
        transformIndexHtml(html) {
          let out = html.replace(/ crossorigin/g, '')
          if (remote) {
            out = out.replace(
              '</head>',
              `  <meta name="crm-build" content="perf-debug" />\n</head>`,
            )
          }
          return out
        },
      },
      ...(remote
        ? [
            {
              name: 'write-htaccess',
              closeBundle() {
                writeFileSync(resolve(hostOut, '.htaccess'), HTACCESS)
              },
            },
          ]
        : []),
    ],
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src'),
      },
      mainFields: ['browser', 'module', 'main', 'jsnext:main', 'jsnext'],
    },
    base: remote ? '/crm/' : '/',
    build: {
      outDir: remote
        ? hostOut
        : resolve(__dirname, '../and_studio_proj/app/src/main/assets/crm'),
      emptyOutDir: true,
    },
  }
})
