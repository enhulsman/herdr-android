## 1. Records and render

- [x] 1.1 Move V1's `changes-view` delta and tasks M5.1-M5.3 into this change; V1's proposal points here
- [x] 1.2 Render in `design/` (D7): list and diff in paper and graphite, a wrapped long line, "Show the whole diff", "No changes since the last commit", "Not a git repository", "Could not read the changes", with the spec's table of texts; signed off by Ezra before 4.2. Signed off 2026-11-16 (renders 13-23 in `design/renders/screens.html`)
- [x] 1.3 Draft the two 1.x issues verbatim (the branch's commits not on main; the chat's tool sheet showing an edit as a diff); create them after Ezra's OK. Created 2026-11-16 as #52 and #53 (1.x, on the board)

## 2. Tests first (Rust, `herdr-changes`)

- [x] 2.1 Argument lists (D2): every shape starts with the fixed prefix and env, contains `--`, puts paths only after it and only from status output; no shape names a writing subcommand; entries ending in `/` never reach the grep or new-file shapes; the grep path list is cut at 24 KB of arguments
- [x] 2.2 Real git in temporary repositories: agent in a subfolder; staged, unstaged and untracked; rename as deleted and new; no first commit with `AM` (counts and diff from disk) and `AD` (not listed), in a SHA-256 repository as well; submodule at a new commit (word and both commits, also with `diff.submodule=log` set); an untracked nested repository; a conflict; a mode-only change; CRLF lines; binary file; new-file line counts (D4) including a path with spaces, a newline and a leading `-`, a binary and an empty new file, and `color.grep=always` set; the outcome table, "not a git repository" also under a German locale; a repository owned by another user (skipped where it cannot be set up). The German locale test skips where git does not speak German (the desk had no de_DE locale); the other user is git's `GIT_TEST_ASSUME_DIFFERENT_OWNER`. The list keeps git's order: tracked entries, then new ones
- [x] 2.3 Hostile repository: external diff driver, text conversion and fsmonitor hook that each create a marker file; files named `--output=x`, `:(glob)*` and `HEAD`; an index lock held during every call; no marker file, nothing under `.git` created or changed (checksum of the git dir, and of the common dir under a linked worktree), and `git commit` succeeds after the lock is released. A clean filter: the list and diff show, the index and `index.lock` untouched
- [x] 2.4 Caps: 1,000 entries and the note; a 20,000-line diff with its line count after the headers; a truncated exec result is "too large"; a line over 4,000 characters cut with its mark
- [x] 2.5 Hub `Changes` with a fake core (D5): reads on open, on status and turn events, on Retry, on reconnect and on screen on; none while the screen is off or after leaving; an event during a read queues one read; an open diff re-fetched, and "no longer changed"; `spawn_failed` gives "Not a git repository"; nothing is sent to the terminal. Reading on reconnect is the hub's (the subscription comes back on a new connection); reading on screen on and none while it is off are the screen's loop (4.3), as for the chat

## 3. Build (Rust)

- [x] 3.1 `herdr-changes` crate: builder, parsers, outcomes, caps (2.1-2.4)
- [x] 3.2 `Changes` in `herdr-mobile` (2.5): folder from `terminal.get`, calls through `machine.exec` with D2's timeouts, results over uniffi

## 4. Android (tests first in each)

- [x] 4.1 Pure functions: every diff line kind (added, removed, context, hunk header, file header, no newline mark, binary, submodule, mode change, empty file), wrapping with the continuation mark, the carriage return mark, the 4,000-character cut, status words, count texts, the 2,000-line cut and its control text
- [ ] 4.2 Changes screen per the signed-off render: list, diff, states; goldens in paper and graphite for every state in 1.2 plus loading, a binary file, a submodule, a new file, an empty file, a mode change, a nested repository, a conflict, "Too large to show on the phone", "No longer changed", 1,000 files; each text checked against the spec's table
- [ ] 4.3 Changes button in the title bar of both views; back returns to the view as it was; the terminal under it keeps watching at the desk's size; content descriptions for rows and the button

## 5. Review and phone check

- [ ] 5.1 `openspec validate changes-view --strict`
- [ ] 5.2 Codex adversarial review of the branch (the git calls, read-only guarantees, caps)
- [ ] 5.3 Test phone, debug build: every spec scenario that needs a device (before approving a blocked agent, the agent going on, a reverted file, lockfile churn, a 30 MB new file, a plain folder, back from a lost connection, from the chat and back), on the test repository and in a throwaway agent; the data an open huge diff costs per turn (D5, Risks)
- [ ] 5.4 Records: `decisions.md` and the README feature line
