export const REPO = 'https://github.com/enhulsman/kudde';
export const RELEASES = `${REPO}/releases/latest`;
export const guide = (path) => `${REPO}/blob/main/${path}`;
export const HERDR_CORE = 'https://github.com/herdrdev/herdr-core';
export const OBTAINIUM = 'https://github.com/ImranR98/Obtainium';

export const NAV = [
  { href: '/install', label: 'Install' },
  { href: '/setup', label: 'Setup' },
  { href: '/security', label: 'Security' },
  { href: '/herdr', label: 'kudde and herdr' },
];

// Inline markup for the page's own copy (trusted): `code`, and names with a
// hyphen kept on one line.
export const nb = (text) => text
  .replace(/`([^`]+)`/g, '<code>$1</code>')
  .replace(/(?<![\w>-])herdr-core\b/g, '<span class="nb">herdr-core</span>');
