// Static output only: plain HTML, CSS and a few lines of JavaScript.
import { defineConfig } from 'astro/config';

export default defineConfig({
  site: 'https://kudde.dev',
  output: 'static',
  build: { format: 'directory' },
  devToolbar: { enabled: false },
  // Nothing is fetched ahead of a click; the pages are small.
  prefetch: false,
});
