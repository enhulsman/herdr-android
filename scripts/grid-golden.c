/*
 * Prints the cell grid libghostty-vt holds after a byte stream, read the way
 * herdr-app's VT pipeline reads it (src/vt.rs: `Vt::frame` and `read_cell`).
 * scripts/record-grid-goldens.sh compiles it against build/ghostty/host and
 * writes the goldens the app's grid reader is tested against
 * (crates/herdr-mobile/src/vt/goldens/).
 *
 * Ported from herdr-app `src/vt.rs`, Apache-2.0 (see
 * crates/herdr-proto/LICENSE-herdr-Apache-2.0). Changed: C over the library's
 * own headers instead of Rust over bindgen, so the struct layouts here come
 * from the compiler and are independent of the app's hand-written bindings;
 * no theme is applied (libghostty's default colours and palette), minimum
 * contrast is off (herdr-app's default), no selection, no kitty images.
 *
 * Usage: grid-golden <stream file> <cols> <rows>
 *
 * Output format (also written by `Grid::golden` in vt/grid.rs):
 *   grid <cols>x<rows>
 *   default fg=<rrggbb> bg=<rrggbb>
 *   cursor <x>,<y> <bar|block|underline|hollow>   or   cursor none
 *   row <y>: <text, trailing spaces removed>
 *     <x0>-<x1> <style>       one line per run of cells with the same
 *                             non-default style: fg=<rrggbb> (when not the
 *                             default foreground), bg=<rrggbb> (own
 *                             background), then bold italic faint underline
 *                             strike wide
 */
#include <ghostty/vt.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
  uint8_t r, g, b;
} Rgb;

typedef struct {
  char text[64]; /* UTF-8 of the cell's grapheme, empty for none */
  Rgb fg;
  bool has_bg;
  Rgb bg;
  bool bold, italic, faint, underline, strike, wide;
} Cell;

static void ok(GhosttyResult r, const char *what) {
  if (r != GHOSTTY_SUCCESS) {
    fprintf(stderr, "%s failed: %d\n", what, (int)r);
    exit(1);
  }
}

static Rgb rgb(GhosttyColorRgb c) {
  Rgb out = {c.r, c.g, c.b};
  return out;
}

static size_t utf8(uint32_t cp, char *out) {
  if (cp < 0x80) {
    out[0] = (char)cp;
    return 1;
  }
  if (cp < 0x800) {
    out[0] = (char)(0xC0 | (cp >> 6));
    out[1] = (char)(0x80 | (cp & 0x3F));
    return 2;
  }
  if (cp < 0x10000) {
    out[0] = (char)(0xE0 | (cp >> 12));
    out[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
    out[2] = (char)(0x80 | (cp & 0x3F));
    return 3;
  }
  out[0] = (char)(0xF0 | (cp >> 18));
  out[1] = (char)(0x80 | ((cp >> 12) & 0x3F));
  out[2] = (char)(0x80 | ((cp >> 6) & 0x3F));
  out[3] = (char)(0x80 | (cp & 0x3F));
  return 4;
}

/* herdr-app `read_cell`, with the theme's background = the terminal's default. */
static Cell read_cell(GhosttyRenderStateRowCells c, Rgb fg_default, Rgb bg_default) {
  Cell cell;
  memset(&cell, 0, sizeof cell);
  GhosttyCell raw = 0;
  ghostty_render_state_row_cells_get(c, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw);
  GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
  ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);

  GhosttyStyle style;
  memset(&style, 0, sizeof style);
  style.size = sizeof style;
  ghostty_render_state_row_cells_get(c, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);

  GhosttyColorRgb col;
  Rgb fg = fg_default;
  if (ghostty_render_state_row_cells_get(c, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &col) ==
      GHOSTTY_SUCCESS)
    fg = rgb(col);
  bool has_bg = false;
  Rgb bg = {0, 0, 0};
  if (ghostty_render_state_row_cells_get(c, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &col) ==
      GHOSTTY_SUCCESS) {
    has_bg = true;
    bg = rgb(col);
  }
  if (style.inverse) {
    Rgb new_fg = has_bg ? bg : bg_default;
    bg = fg;
    has_bg = true;
    fg = new_fg;
  }

  uint32_t cps[16];
  uint32_t len = 0;
  if (wide != GHOSTTY_CELL_WIDE_SPACER_TAIL && wide != GHOSTTY_CELL_WIDE_SPACER_HEAD &&
      !style.invisible) {
    ghostty_render_state_row_cells_get(c, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &len);
    if (len > 16) {
      fprintf(stderr, "grapheme of %u codepoints\n", len);
      exit(1);
    }
    if (len > 0)
      ghostty_render_state_row_cells_get(c, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, cps);
  }
  size_t at = 0;
  for (uint32_t i = 0; i < len; i++) {
    uint32_t cp = cps[i];
    if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF))
      continue; /* char::from_u32 drops it */
    at += utf8(cp, cell.text + at);
  }
  cell.text[at] = 0;

  /* A kitty placeholder draws no text and no decorations. */
  if (len > 0 && cps[0] == 0x10EEEE) {
    cell.text[0] = 0;
    style.underline = 0;
    style.strikethrough = false;
  }

  cell.fg = fg;
  cell.has_bg = has_bg;
  cell.bg = bg;
  cell.bold = style.bold;
  cell.italic = style.italic;
  cell.faint = style.faint;
  cell.underline = style.underline != 0;
  cell.strike = style.strikethrough;
  cell.wide = wide == GHOSTTY_CELL_WIDE_WIDE;
  return cell;
}

static bool same_rgb(Rgb a, Rgb b) { return a.r == b.r && a.g == b.g && a.b == b.b; }

/* The style part of a run line; empty when the cell looks like a default one. */
static void style_of(const Cell *c, Rgb fg_default, char *out) {
  out[0] = 0;
  char buf[32];
  if (!same_rgb(c->fg, fg_default)) {
    snprintf(buf, sizeof buf, " fg=%02x%02x%02x", c->fg.r, c->fg.g, c->fg.b);
    strcat(out, buf);
  }
  if (c->has_bg) {
    snprintf(buf, sizeof buf, " bg=%02x%02x%02x", c->bg.r, c->bg.g, c->bg.b);
    strcat(out, buf);
  }
  if (c->bold) strcat(out, " bold");
  if (c->italic) strcat(out, " italic");
  if (c->faint) strcat(out, " faint");
  if (c->underline) strcat(out, " underline");
  if (c->strike) strcat(out, " strike");
  if (c->wide) strcat(out, " wide");
}

int main(int argc, char **argv) {
  if (argc != 4) {
    fprintf(stderr, "usage: %s <stream file> <cols> <rows>\n", argv[0]);
    return 2;
  }
  uint16_t cols = (uint16_t)atoi(argv[2]), rows = (uint16_t)atoi(argv[3]);
  FILE *f = fopen(argv[1], "rb");
  if (!f) {
    perror(argv[1]);
    return 1;
  }
  size_t cap = 1 << 20, n = 0;
  uint8_t *bytes = malloc(cap);
  size_t got;
  while ((got = fread(bytes + n, 1, cap - n, f)) > 0) {
    n += got;
    if (n == cap) bytes = realloc(bytes, cap *= 2);
  }
  fclose(f);

  GhosttyTerminal term = NULL;
  ok(ghostty_terminal_new(NULL, &term, cols, rows), "terminal_new");
  GhosttyRenderState state = NULL;
  ok(ghostty_render_state_new(NULL, &state), "render_state_new");
  GhosttyRenderStateRowIterator rows_it = NULL;
  ok(ghostty_render_state_row_iterator_new(NULL, &rows_it), "row_iterator_new");
  GhosttyRenderStateRowCells cells = NULL;
  ok(ghostty_render_state_row_cells_new(NULL, &cells), "row_cells_new");

  ghostty_terminal_vt_write(term, bytes, n);
  ok(ghostty_render_state_update(state, term), "render_state_update");

  GhosttyRenderStateColors colors;
  memset(&colors, 0, sizeof colors);
  colors.size = sizeof colors;
  ok(ghostty_render_state_get(state, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors), "colors");
  Rgb fg_default = rgb(colors.foreground), bg_default = rgb(colors.background);

  printf("grid %ux%u\n", cols, rows);
  printf("default fg=%02x%02x%02x bg=%02x%02x%02x\n", fg_default.r, fg_default.g, fg_default.b,
         bg_default.r, bg_default.g, bg_default.b);

  GhosttyRenderStateCursor cur;
  memset(&cur, 0, sizeof cur);
  cur.size = sizeof cur;
  ok(ghostty_render_state_get(state, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cur), "cursor");
  if (cur.visible && cur.viewport_has_value) {
    const char *shape = "block";
    switch (cur.visual_style) {
      case GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BAR: shape = "bar"; break;
      case GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_UNDERLINE: shape = "underline"; break;
      case GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK_HOLLOW: shape = "hollow"; break;
      default: break;
    }
    unsigned x = cur.wide_tail && cur.viewport_x > 0 ? cur.viewport_x - 1u : cur.viewport_x;
    printf("cursor %u,%u %s\n", x, (unsigned)cur.viewport_y, shape);
  } else {
    printf("cursor none\n");
  }

  ok(ghostty_render_state_get(state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &rows_it), "rows");
  Cell *row = calloc(cols, sizeof(Cell));
  unsigned y = 0;
  while (ghostty_render_state_row_iterator_next(rows_it)) {
    ok(ghostty_render_state_row_get(rows_it, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &cells), "cells");
    unsigned x = 0;
    while (ghostty_render_state_row_cells_next(cells) && x < cols) row[x++] = read_cell(cells, fg_default, bg_default);
    unsigned width = x;

    /* Text: a blank cell is a space, a wide character's tail adds nothing. */
    char line[16384];
    size_t at = 0;
    for (x = 0; x < width; x++) {
      if (row[x].text[0]) {
        size_t l = strlen(row[x].text);
        memcpy(line + at, row[x].text, l);
        at += l;
      } else if (!(x > 0 && row[x - 1].wide)) {
        line[at++] = ' ';
      }
    }
    while (at > 0 && line[at - 1] == ' ') at--;
    line[at] = 0;
    printf("row %u: %s\n", y, line);

    char style[256], next[256];
    x = 0;
    while (x < width) {
      style_of(&row[x], fg_default, style);
      unsigned end = x;
      while (end + 1 < width) {
        style_of(&row[end + 1], fg_default, next);
        if (strcmp(next, style) != 0) break;
        end++;
      }
      if (style[0]) printf("  %u-%u%s\n", x, end, style);
      x = end + 1;
    }
    y++;
  }
  free(row);
  free(bytes);
  ghostty_render_state_row_cells_free(cells);
  ghostty_render_state_row_iterator_free(rows_it);
  ghostty_render_state_free(state);
  ghostty_terminal_free(term);
  return 0;
}
