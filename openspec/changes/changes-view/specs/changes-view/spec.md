## ADDED Requirements

### Requirement: A Changes button opens the agent's changes
The terminal screen's title bar SHALL show a Changes button in both the
terminal and the chat view, for every terminal. A tap SHALL open the Changes
screen over the current view; back SHALL return to that view as it was. The
folder read SHALL be the folder of the terminal's foreground process (the
folder the title bar names), else the terminal's own folder.

#### Scenario: From the terminal
- **WHEN** Ezra taps Changes in an agent's terminal view and then back
- **THEN** the Changes screen shows the agent's folder's changes, and back shows the terminal as it was

#### Scenario: From the chat
- **WHEN** Ezra taps Changes in an agent's chat and then back
- **THEN** back shows the chat at the scroll position it had, with its draft

### Requirement: Outside a git repository the screen says so
The Changes screen SHALL read "Not a git repository" and list nothing when
the folder is not inside a git repository, when the desk cannot start git,
or when the folder no longer exists. It SHALL tell a repository apart from
none by git's exit code and untranslated message, whatever the desk's
language. Any other failure of git (a timeout, a refusal such as a
repository owned by another user) SHALL read "Could not read the changes"
with git's first error line, and offer Retry.

#### Scenario: A plain folder
- **WHEN** Ezra opens Changes for a terminal in a folder outside any repository
- **THEN** the screen reads "Not a git repository"

#### Scenario: No git on the desk
- **WHEN** the desk's herdr-core cannot start git
- **THEN** the screen reads "Not a git repository"

#### Scenario: A desk in another language
- **WHEN** the desk's git speaks German and the folder is outside any repository
- **THEN** the screen reads "Not a git repository"

#### Scenario: Git refuses the repository
- **WHEN** git exits with an error other than "not a git repository"
- **THEN** the screen reads "Could not read the changes" with git's first error line and a Retry control

### Requirement: The list shows what is not committed yet
The Changes screen SHALL list the repository's uncommitted changes against
its last commit: staged, unstaged and untracked (not ignored) files, from the
repository's top, whichever subfolder the agent runs in. Each entry SHALL
show its path and its status as a word, not by colour alone: changed,
added, deleted, new, conflict, submodule or repository (an untracked folder
that is a repository of its own). Text entries SHALL show their lines added
and removed; a new file's count is its line count, and a new file that is
empty or binary shows no count. Renames SHALL show as a deleted and a new or
added entry. In a repository with no commit yet, every file SHALL count as
added or new, with its counts and diff taken from the file on disk. An entry
of the kind repository SHALL not open. At most the first 1,000 entries SHALL
be listed, followed by "Showing the first 1,000 changed files". With nothing
uncommitted the screen SHALL read "No changes since the last commit" and
"Committed changes do not show here."

#### Scenario: Agent in a subfolder
- **WHEN** the agent runs in `app/` of a repository and changed `app/main.rs` and `docs/notes.md`
- **THEN** both files are listed with paths from the repository's top

#### Scenario: Staged, unstaged and untracked
- **WHEN** one file is staged, one changed but not staged and one new and untracked
- **THEN** all three are listed, as changed, changed and new, each with its line counts

#### Scenario: An agent that commits as it goes
- **WHEN** the agent committed all its work
- **THEN** the screen reads "No changes since the last commit" and "Committed changes do not show here."

#### Scenario: Many files
- **WHEN** 20,000 untracked files are not ignored
- **THEN** the first 1,000 are listed with "Showing the first 1,000 changed files"

#### Scenario: No commit yet
- **WHEN** the repository has no commit, a file was staged with 2 lines and has 4 on disk, and another was staged and then deleted
- **THEN** the first is listed as added with 4 lines and the second is not listed

#### Scenario: A clone inside the folder
- **WHEN** the agent cloned another repository into an untracked folder
- **THEN** that folder is listed as repository and does not open, and the rest of the list reads normally

#### Scenario: A rebase stopped on a conflict
- **WHEN** a file has a merge conflict
- **THEN** it is listed as conflict and its diff shows the conflict markers on disk

### Requirement: A file's diff is readable on the phone
A tap on an entry SHALL show its diff: lines added and removed coloured from
the theme's diff colours and kept apart by their `+` and `-` marks as well,
context lines plain, hunk headers as dividers. Long lines SHALL wrap with a
continuation mark; nothing scrolls sideways. A line ending in a carriage
return SHALL show it as a mark, so a change of line endings is visible. A
new file SHALL show as all added. The texts in the table of screen texts
SHALL show for a binary file, an empty file, a change of file mode only, a
missing newline at the end, and a submodule (with its old and new commit).

#### Scenario: A changed file
- **WHEN** Ezra taps a changed text file
- **THEN** its diff shows with added and removed lines marked by colour and by `+` and `-`

#### Scenario: A long line
- **WHEN** a changed line is 300 characters long
- **THEN** it wraps within the screen width with a continuation mark and the screen does not scroll sideways

#### Scenario: Line endings changed
- **WHEN** a file's lines changed from LF to CRLF only
- **THEN** each added line shows the carriage return mark and its removed line does not

#### Scenario: A new file
- **WHEN** Ezra taps an untracked file
- **THEN** its whole content shows as added lines

#### Scenario: A binary file
- **WHEN** Ezra taps a changed image
- **THEN** the screen reads "Binary file, not shown"

#### Scenario: A submodule at a new commit
- **WHEN** Ezra taps a submodule whose commit moved, on a desk whose git config asks for submodule logs in diffs
- **THEN** the screen shows the old and the new commit

### Requirement: Long diffs show their start and load in full on request
A diff of more than 2,000 lines (counted after its file headers) SHALL show
its first 2,000 lines and a control "Show the whole diff (N lines)", N being
that count, that shows the rest. A diff larger than herdr-core returns in
one call (8 MB), or longer than 50,000 lines after its file headers, SHALL
show its start (at most 50,000 lines) and read "Too large to show on the
phone" instead of that control. A single line over 4,000 characters SHALL
be cut with a mark, also in the whole diff.

#### Scenario: Lockfile churn
- **WHEN** a lockfile's diff is 20,000 lines long
- **THEN** the first 2,000 lines show with "Show the whole diff (20,000 lines)", and a tap shows all of them

#### Scenario: A diff of very many short lines
- **WHEN** an untracked file holds three million empty lines (under 8 MB)
- **THEN** its first 50,000 lines show and the screen reads "Too large to show on the phone"

#### Scenario: A new file larger than one call
- **WHEN** an untracked 30 MB log file is opened
- **THEN** its first lines show and the screen reads "Too large to show on the phone"

### Requirement: Git runs from fixed argument lists and never blocks the agent
The Changes screen SHALL run git only through `machine.exec` with `argv`
(never `line`, never a shell), from the repository's top, as argument lists
built by one function from a fixed set of shapes. Every call SHALL disable
optional locks, the file system monitor, external diff drivers, text
conversion, colour, the pager and fetching missing objects, treat paths as
literal, and end its options with `--`. Git SHALL take no lock and SHALL
NOT write the index or anything else under `.git`. Filters the repository
configures (such as Git LFS) MAY run, as they do for any `git status`. No
control on the screen SHALL change the repository: no commit, stage,
discard or checkout.

#### Scenario: Hostile repository
- **WHEN** the repository configures an external diff driver, a text conversion and a file system monitor hook that each create a file, holds files named `--output=x`, `:(glob)*` and `HEAD`, and the agent holds the index lock
- **THEN** the list and every diff show, the driver, conversion and hook do not run, and the agent's own `git commit` succeeds once it releases its lock

#### Scenario: A clean filter
- **WHEN** the repository configures a clean filter for a changed file
- **THEN** the list and the diff show, and the index and `index.lock` are untouched whether or not the filter ran

#### Scenario: Nothing written under .git
- **WHEN** Ezra opens Changes and three diffs in a repository without filters
- **THEN** no file under `.git` is created or changed

### Requirement: The screen follows the agent while it shows
The Changes screen SHALL read on opening, on each status or turn event of
its terminal while it shows, on Retry, when the connection to the desk is
restored and when the phone's screen comes back on; it SHALL NOT read while
the phone's screen is off or after leaving. An open diff SHALL update in
place, keeping the scroll position; a file no longer changed SHALL read "No
longer changed". While the Changes screen shows, the desk SHALL keep its
terminal size and nothing SHALL be sent to the terminal.

#### Scenario: Before approving
- **WHEN** an agent is blocked on a permission prompt and Ezra opens Changes
- **THEN** the files changed so far and their diffs show, the desk's terminal keeps its size, and the agent stays blocked

#### Scenario: The agent goes on
- **WHEN** the agent finishes a turn that changed another file while the list shows
- **THEN** the new file appears in the list without a tap

#### Scenario: A file reverted
- **WHEN** the agent reverts the file whose diff is open
- **THEN** the diff screen reads "No longer changed"

#### Scenario: Back from a lost connection
- **WHEN** the connection drops while the list shows, the agent changes a file, and the connection comes back
- **THEN** the list shows the change without a tap

### Requirement: The screen's texts are fixed
The Changes screen SHALL use exactly these texts:

| Where | Text |
|---|---|
| Title bar button and its description | Changes / "Show the files this agent changed" |
| Status words | changed, added, deleted, new, conflict, submodule, repository |
| Loading | Reading the changes… |
| Not a repository | Not a git repository |
| Nothing uncommitted | No changes since the last commit / Committed changes do not show here. |
| Failure | Could not read the changes / git's first error line / Retry |
| File cap | Showing the first 1,000 changed files |
| Binary | Binary file, not shown |
| Empty new file | Empty file |
| Mode only | Only the file mode changed (old to new, e.g. 100644 to 100755) |
| No newline at the end | No newline at end of file |
| Submodule | Submodule: old commit to new commit (short ids) |
| Long diff | Show the whole diff (N lines) |
| Too large | Too large to show on the phone |
| Reverted while open | No longer changed |
| Line continues | a hooked arrow (↪) at the start of each wrapped part |
| Carriage return | CR, in a small box at the line's end |
| Line cut at 4,000 characters | … (cut) at the cut |
| A row's description | path, status word, lines added and removed |

#### Scenario: Texts match the table
- **WHEN** each state of the screen is drawn in the screen tests
- **THEN** every text shown is one of the table's
