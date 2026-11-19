// Static output only: plain HTML, CSS and a few lines of JavaScript.
import { defineConfig } from 'astro/config';

export default defineConfig({
  site: 'https://kudde.dev',
  output: 'static',
  // install.html, served at /install: Workers static assets (html_handling
  // "auto-trailing-slash") serve a folder index only at /install/, behind a redirect.
  trailingSlash: 'never',
  build: {
    format: 'file',
    // CSS and scripts go in files, never inline, so the Content-Security-Policy
    // in public/_headers needs no 'unsafe-inline' and no hashes.
    inlineStylesheets: 'never',
  },
  vite: {
    build: { assetsInlineLimit: 0 },
  },
  devToolbar: { enabled: false },
  // Nothing is fetched ahead of a click; the pages are small.
  prefetch: false,
});
