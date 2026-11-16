# Design: the Changes view

## Context

The proposal settles the decisions (Ezra, 2026-11-16). The read path is
modelled on the Mac app's Changes panel (`research/src/herdr-app/defaults/
mods/changes/main.js`, read-only parts): find the top, status, line counts,
a diff per file. herdr-core needs nothing new: `machine.exec` takes `argv`
(no shell), `cwd`, `env` and `timeout`, returns stdout in base64 up to 8 MB
with `truncated`, and runs at most 16 calls at once per core, shared with
the chat's polling. `terminal.get` gives the foreground process's folder.
The theme has `diffAdd` and `diffDel` (`design-tokens.json`); there is no
render yet. A review of the first draft (2026-11-16, local:
`changes-view-scrutiny.md`) ran each shape against git 2.56; its fixes are
in D2 to D5, and Ezra settled its two owner questions the same day: filters
may run (the spec promises no lock and no index write), and the 8 MB limit
stands as an exception to decision 5.

## Goals / Non-Goals

**Goals:** the spec's requirements; one argument-list builder that a test
can hold to its fixed shapes; parsers tested against real git in scratch
repositories, a hostile one included; a render signed off before the screen
is built.

**Non-Goals:** the branch's commits not on main (proposal decision 2, a 1.x
issue); the pending edit of a permission prompt (decision 3: the chat's tool
sheet showing an edit as a diff is a 1.x issue); staging, committing or
discarding; syntax colouring; sideways scrolling; recursing into
submodules; switching repository filters off.

## Decisions

### D1. A `herdr-changes` crate for the git calls and their parsing
Pure functions: the argument lists (D2), parsing of `rev-parse`, status,
`--raw --numstat`, `grep -c` and diffs, the caps, the outcome of each call.
Tested in `tests/` against real git in temporary repositories, as
`herdr-chat` is tested against the desk. *Over* a module in `herdr-proto`:
that crate holds the wire protocol, and the chat set the pattern of one
crate per view.

### D2. The fixed shapes
Every call is `git --no-pager --literal-pathspecs -c core.fsmonitor=false
-c diff.autoRefreshIndex=false -c core.quotePath=false -c color.ui=never`
followed by one of these, each ending its options with `--` (a file named
`HEAD` otherwise makes `diff HEAD` ambiguous):

| Shape | Arguments | Timeout |
|---|---|---|
| top | `rev-parse --show-toplevel --` (from the agent's folder) | 20 s |
| head | `rev-parse --show-object-format --verify --quiet HEAD --` (no first commit: no id; the format picks the empty tree) | 20 s |
| status | `status --porcelain=v1 -z --no-renames --untracked-files=all --ignore-submodules=dirty --` | 30 s |
| counts | `diff <base> --raw --numstat -z --no-renames --ignore-submodules=dirty --no-ext-diff --no-textconv --` | 30 s |
| new-file counts | `grep --untracked -I -c -z --no-color -e '' -- <new paths>` (D4) | 30 s |
| diff | `diff <base> --no-ext-diff --no-textconv --no-color --submodule=short --ignore-submodules=dirty -- <path>` | 30 s |
| new file | `diff --no-index --no-ext-diff --no-textconv --no-color -- /dev/null <path>` | 30 s |

`<base>` is `HEAD`, or with no first commit the empty tree (SHA-1
`4b825dc642cb6eb9a060e54bf8d69288fbee4904`, SHA-256
`6ef19b41225c5369f1c104d45d8d85efa9b057b53b14b4b9b939dd74decc5321`), so an
added file's counts and diff both come from the disk. `--raw` with
`--numstat` gives the modes, which tell submodules (160000), symlinks,
type changes and mode-only changes apart from text edits.

`env` sets `GIT_OPTIONAL_LOCKS=0`, `GIT_LITERAL_PATHSPECS=1`,
`GIT_NO_LAZY_FETCH=1` (a partial clone fetches no missing object; older git
ignores it), `LC_ALL=C` and `LANGUAGE=C` (git's messages untranslated, for
the outcome table). `cwd` is the top for every call but the first. The
builder takes only an enum of shapes and paths that came from the status
output; a test asserts every list starts with the fixed prefix, contains
`--`, and puts paths only after it. Entries ending in `/` (an untracked
repository inside the folder) never reach the grep or new-file shapes.

**Outcome per call:**

| Shape | Success | "Not a git repository" | Everything else |
|---|---|---|---|
| top | exit 0 | exit 128 and stderr starts `fatal: not a git repository`; `spawn_failed` | "Could not read the changes" |
| head | exit 0, or exit 1 with no id (no first commit) | | as above |
| status, counts, diff | exit 0 | | as above |
| new-file counts | exit 0 or 1 (1: no text line found) | | as above |
| new file | exit 0 or 1 and stdout starts `diff --git` | | as above |

`spawn_failed` covers both a desk without git and a folder that no longer
exists; both read "Not a git repository" (spec). A read is top, head,
status, counts, new-file counts (more than one call when chunked, D4) and
the open diff: six or seven calls, one after another, never in parallel.

*Over* the October template (`status --porcelain=v2`, `ls-files`): it could
not show a new file, ran from the agent's folder (porcelain paths are from
the top, `diff -- <path>` from the folder), left pathspec magic on and had
no line counts.

**Filters.** Clean filters the repository configures (Git LFS's among them)
still run under these flags; with optional locks off the index is never
refreshed, so a file whose stat changed runs its filter on each read. They
take no lock and do not write the index, so the agent is never blocked
(Ezra, 2026-11-16: the spec promises this, not that no configured program
runs). *Over* finding and switching off each filter: one more call, and a
touched LFS file would then list as changed.

### D3. One fetch per diff, cut on the phone
A diff is fetched whole (up to the 8 MB exec limit) when its file is opened
and held while the diff screen shows; the screen draws the first 2,000
lines after the file headers and "Show the whole diff" draws the rest from
what is held. A `truncated` result is "Too large to show on the phone"
(Ezra, 2026-11-16: the exception to decision 5). *Over* fetching 2,000
lines and then the rest: git has no line range, and a shell `head` would
break D2's no-shell rule. The cost is up to 8 MB over mobile data for a
huge diff opened once (the Mac does the same, uncapped).

### D4. Line counts for new files in one call
`git grep --untracked -I -c -z --no-color -e ''` counts the lines of the
given text files in one call; its output is `path NUL count LF` per file
(the record ends in a line feed, not a NUL). A new file missing from it is
binary or empty and shows "new" without a count until opened. `--no-color`
is needed because `color.ui=never` does not override `color.grep=always`.
The path list is cut into calls of at most 24 KB of arguments, under
Windows' command line limit. Verified on git 2.56: a last line without a
newline counts, CRLF lines count once, a path with a newline survives,
symlinks are not followed. *Over* one `--no-index --numstat` call per new
file: up to 1,000 calls against a limit of 16 at once.

### D5. Reading follows the terminal's events
The hub's `Changes` object reads on opening, on each `status` or `turn`
event of its terminal while the screen shows, on Retry, when the
connection is restored and when the phone's screen comes back on; nothing
while the screen is off, and it is dropped on leaving. An event that
arrives during a read queues one more read, never more. An open diff is
fetched again after each read whose list still holds its file. *Over* the
Mac's 10 s timer: the phone pays for every call in battery and data, and an
agent's edits end with a status change. Edits made mid-turn show at the
turn's end (see Risks).

### D6. The desk keeps its size
The Changes screen opens over the terminal route; the terminal under it
stays attached and watching, as under the chat (the chat's "desk keeps its
size" rule), so the desk's layout and the agent's prompt are untouched and
back is instant.

### D7. Render first
One render in `design/` (list and diff, paper and graphite, a long wrapped
line, the 2,000-line control, the empty and "Not a git repository" states)
is signed off before the screen is built; the screen's goldens are then
held to it. The render uses the spec's table of texts.

### D8. Tests where they hold
Argument lists, parsers, outcomes and caps: unit tests. Git behaviour
(subfolder, no first commit, untracked counts, renames, submodule, nested
repository, conflict, mode change, CRLF, binary, hostile repository, a
clean filter, no writes under `.git`, a German locale): tests running real
git in temporary repositories, skipped with a message if git is missing.
Screen: Roborazzi goldens in both themes. Reading on events: tests of the
hub object with a fake core. Behaviour on the phone: a phone check.

## Risks / Trade-offs

- [Edits mid-turn appear only at the turn's end] → reading follows status
  and turn events (D5); a long turn's edits show when it stops or blocks,
  which is when they are wanted. A timer is a small change if the phone
  check finds it too late.
- [An open diff re-fetched on each turn] → up to 8 MB per turn for a huge
  diff left open; the phone check measures it, and fetching only when the
  file's counts or modes changed is the fallback.
- [Filters run on each read] → an LFS repository with stat-dirty files
  pays the filter's cost per read, and LFS may store objects under
  `.git/lfs`; accepted (D2, Filters).
- [Git versions] → needs git 2.25 or newer (`--show-object-format`; the
  other options are older); a desk with older git reads "Could not read the
  changes" with git's own message. Tested on the desk's git and in CI's.
- [`/dev/null` on Windows desks] → Git for Windows maps it (moderate
  confidence); desks other than Linux and macOS are untested.
- [A huge untracked tree that is not ignored] → status output can pass
  8 MB; the list shows the complete entries that arrived with the 1,000 cap
  note.
- [`--ignore-submodules=dirty`] → a submodule with only uncommitted work
  inside it is not listed; one at a new commit is.
- [Calls shared with the chat's 16-call limit] → a read is six or seven
  calls in a row, never in parallel.

## Migration Plan

None: a new screen. Rollback is a release without the button.

## Open Questions

None for the build; the render (D7) is shown to Ezra before the screen.
