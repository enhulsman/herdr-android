## MODIFIED Requirements

### Requirement: A machine without spaces
The sheet SHALL offer folders instead of spaces on a machine with no
`herdr-app` document, an unreadable one, or one listing zero spaces: the
folders its live terminals are in, each once, the home folder first. The
terminal SHALL be labelled with a fresh space id, so the desk shows it as a new space named
after its folder.

#### Scenario: No layout document
- **WHEN** the machine has no `herdr-app` document and two terminals in `/home/me/src/api`
- **THEN** the sheet offers the home folder and `api` once each, and the started terminal is labelled with a space id no space has yet

#### Scenario: Zero spaces
- **WHEN** the machine's layout lists zero spaces and the person starts a terminal in `/home/me/src/api` from the phone
- **THEN** the desk shows a new space "api" holding it, and kudde-push alerts for it as for any placed terminal


### Requirement: Agent shortcuts
The sheet SHALL offer, below "Terminal", the agents from the desk's agent table
that are installed on the chosen machine, as found by the person's interactive
shell on that machine (login and interactive on macOS). The check SHALL run in
the background when the machine connects and SHALL time out after 5 seconds; a
successful answer SHALL be kept for that machine until the app restarts, and a
failed one SHALL be retried the next time the sheet opens. Until the check
answers, the sheet SHALL hold the shortcut row's place. An alias of an agent's
command SHALL count as installed.

Tapping an agent SHALL start a terminal as "Terminal" does (folder, label,
`keep`), running the agent's command from the table in the person's interactive
shell, written so that the person's alias for it applies; when the agent quits,
the shell SHALL remain in that terminal. The phone SHALL start the bare command
only: no flags, no first message, nothing taken from the person's input.
Machines whose shell is not `sh`-compatible (fish, nu, PowerShell) or that have
no `sh` SHALL get "Terminal" only.

#### Scenario: Claude Code
- **WHEN** WSL has `claude` and `codex` installed and the person taps "Claude Code" in the space "website"
- **THEN** a terminal starts in the space's folder, labelled with the space, running `claude` in the person's interactive shell

#### Scenario: The alias applies
- **WHEN** the person's shell defines `claude` only as an alias for another program, and they tap "Claude Code"
- **THEN** the sheet offers Claude Code, and the aliased program is what runs

#### Scenario: Only installed agents
- **WHEN** a machine has `codex` but not `claude`
- **THEN** the sheet offers Codex and not Claude Code

#### Scenario: The check fails
- **WHEN** the installed-agents check fails or takes longer than 5 seconds
- **THEN** the sheet offers "Terminal" only, and the check runs again the next time the sheet opens

#### Scenario: A fish shell
- **WHEN** the machine's shell is fish
- **THEN** the sheet offers "Terminal" only

#### Scenario: macOS
- **WHEN** the machine reports `Darwin`
- **THEN** the check and the agent run in a login interactive shell, and the shell that remains is a login interactive shell

#### Scenario: The agent quits
- **WHEN** an agent started from a shortcut exits normally
- **THEN** the terminal stays, with the person's shell at the prompt in the same folder

#### Scenario: Alerts while the desk app is closed
- **WHEN** an agent started from the phone in the space "website" blocks while the desk app is not running
- **THEN** kudde-push alerts for it after the grace delay, per the V1 alerts rule as amended by this change

