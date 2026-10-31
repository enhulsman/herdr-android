# changes-view Specification (delta)

## ADDED Requirements

### Requirement: See what an agent changed
From a terminal whose folder is a git repository, a Changes view SHALL list the
changed files (staged, unstaged, untracked) against HEAD and SHALL show each
file's diff, with additions and removals coloured from the current theme.

#### Scenario: Before approving
- **WHEN** an agent is blocked on a permission prompt and Ezra opens Changes
- **THEN** the changed files and their diffs are shown, and the agent stays blocked

### Requirement: Changes is view-only
The Changes view SHALL run only read-only git commands (`status`, `diff`,
`show`, `ls-files`) through `machine.exec`, and SHALL offer no stage, commit,
discard or edit action.

#### Scenario: No write paths
- **WHEN** the Changes view is open
- **THEN** no control in it can change the repository, and the app's exec calls are limited to the read-only list (checked by a test)

### Requirement: Large diffs stay usable
Diffs over 2,000 lines or files over 1 MiB SHALL be shown truncated with the
size and a "load more" control; binary files SHALL be listed without content.

#### Scenario: Lockfile churn
- **WHEN** a lockfile changed by 20,000 lines
- **THEN** the first 2,000 lines show with "load more"
