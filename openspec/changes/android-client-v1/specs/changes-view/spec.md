# changes-view Specification (delta)

All requirements here belong to version 1.1 (M5).

## ADDED Requirements

### Requirement: See what an agent changed
From a terminal whose folder is a git repository, a Changes view SHALL list changed
files (staged, unstaged, untracked) against HEAD and SHALL show each file's diff,
coloured from the current theme.

#### Scenario: Before approving
- **WHEN** an agent is blocked on a permission prompt and Ezra opens Changes
- **THEN** the changed files and diffs are shown and the agent stays blocked

### Requirement: Git runs from a fixed, read-only template
The Changes view SHALL run git through `machine.exec` only as
`GIT_OPTIONAL_LOCKS=0 git --no-pager -c core.fsmonitor=false` followed by one of:
`status --porcelain=v2 -z`, `diff --no-ext-diff --no-textconv [--cached] -- <paths>`,
`ls-files -z --others --exclude-standard`; paths always after `--`. No other
subcommand or flag SHALL be possible, and no control SHALL change the repository.

#### Scenario: Hostile repository
- **WHEN** the repo configures an external diff driver, contains a file named `--output=x`, and holds an index lock
- **THEN** the view shows the diff without running the driver, creates no file, and the agent's own git commands are not blocked

### Requirement: Large diffs stay usable
Diffs over 2,000 lines or files over 1 MiB SHALL show truncated with their size and
a "load more" control; binary files SHALL be listed without content.

#### Scenario: Lockfile churn
- **WHEN** a lockfile changed by 20,000 lines
- **THEN** the first 2,000 lines show with "load more"
