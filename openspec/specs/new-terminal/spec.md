# new-terminal Specification

## Purpose
TBD - created by archiving change start-agents. Update Purpose after archive.
## Requirements
### Requirement: A new terminal in a space
The Agents screen SHALL offer "New", which opens a sheet to start a terminal on a
connected machine. On a machine whose layout lists spaces, the person SHALL pick
a space and the terminal SHALL start in that space's folder, labelled with the
space so the desk shows it as a new tab, and kept as the desk keeps its own
(`keep: true`). The terminal SHALL run the machine's own shell, as the desk's
new tab does. The sheet SHALL show the machine, the space and the folder before
anything starts. A space folder that is empty SHALL mean the home folder; one
starting `~/` SHALL be expanded with the home folder; any other folder not
starting with `/` SHALL map to the home folder with a note in the sheet. An
unnamed space SHALL be shown by the desk's rule: its folder's name, `~` for
home.

#### Scenario: A terminal in a space
- **WHEN** the person picks WSL, the space "website" (folder `/home/me/src/website`) and "Terminal"
- **THEN** a terminal starts on WSL running its shell in `/home/me/src/website`, labelled with that space, with `keep` set

#### Scenario: Empty space folder
- **WHEN** the person picks a space whose folder is empty
- **THEN** the sheet shows the home folder, and the terminal starts there

#### Scenario: Unnamed space
- **WHEN** a space has no name and the folder `/home/me/src/api`
- **THEN** the sheet shows it as "api"

#### Scenario: One machine
- **WHEN** only one machine is connected
- **THEN** the sheet skips the machine choice

#### Scenario: Machine not connected
- **WHEN** no machine is connected
- **THEN** "New" is disabled and says why

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
- **THEN** the desk shows a new space "api" holding it, and herdr-push alerts for it as for any placed terminal

### Requirement: Start errors are shown
When the core refuses a start, the sheet SHALL stay open and show the reason;
nothing SHALL start elsewhere, and nothing SHALL be retried in another folder.
While a start is in flight, its button SHALL be disabled.

#### Scenario: The folder is gone
- **WHEN** the space's folder no longer exists on the machine and the core answers `spawn_failed`
- **THEN** the sheet says "Could not start in" that folder with the core's detail, and no terminal is created

#### Scenario: Machine unreachable
- **WHEN** the core answers `unavailable` or `machine_unknown`
- **THEN** the sheet says the machine is not reachable now

#### Scenario: Double tap
- **WHEN** the person taps "Terminal" twice quickly
- **THEN** exactly one terminal starts

### Requirement: The new terminal opens on the phone
After a successful start, the phone SHALL open the new terminal's screen at once,
at the phone's size, as a tap on a row does.

#### Scenario: Started and open
- **WHEN** a terminal starts from the sheet
- **THEN** its terminal screen is open on the phone and the sheet is closed

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
- **THEN** herdr-push alerts for it after the grace delay, per the V1 alerts rule as amended by this change

