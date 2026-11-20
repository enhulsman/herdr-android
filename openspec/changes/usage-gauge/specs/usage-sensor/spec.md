## ADDED Requirements

### Requirement: The sensor goes on with one command at the machine
`kudde-push usage on` SHALL make Claude Code's status line run the sensor,
by changing only the `statusLine` entry of Claude Code's user settings
file (`settings.json` in `$CLAUDE_CONFIG_DIR` as set where the command
runs, else in `~/.claude`). It SHALL name that settings file in what it
prints. Before it writes, it SHALL keep the original entry's exact text,
or the fact that there was none, in a file of its own for that settings
file: under the fixed folder `$HOME/.config/kudde-push/usage/` (never an
XDG folder), named from the settings file's canonical path, readable by
the owner only (mode 600), in a folder of mode 700. It SHALL create the
readings folder `$HOME/.local/state/kudde-push/usage/claude/` with mode
700. The new entry SHALL be `{"type":"command","command":"'<absolute
path of kudde-push>' usage sensor --original '<kept file>'"}`, both paths
quoted for `/bin/sh`, plus every other member of the original entry (such
as `padding` or `refreshInterval`) unchanged. Every byte of the file
outside that entry SHALL stay as it was. A settings file that is a
symbolic link SHALL be written through, the link kept. The write SHALL be
atomic and SHALL be made only if the file is unchanged since it was read;
otherwise it SHALL read again and retry once, then stop with nothing
changed.

An entry SHALL count as the sensor's when its `type` is `command` and its
command, split as `/bin/sh` would split it, is a program whose file name
is `kudde-push` followed by `usage sensor`, wherever that program lies.
When the entry is already the sensor's, `on` SHALL keep the kept
original and only point the command at this kudde-push and this kept
file. `on` SHALL NEVER replace a kept original by a different text: when
a kept original exists and the entry is neither the sensor's nor equal
to that kept text, it SHALL change nothing and say so. A file that is not
valid JSON, or whose top level is not an object, SHALL NOT be changed.
kudde-push SHALL NOT turn the sensor on by itself, and its alert service
SHALL work the same with the sensor on or off.

#### Scenario: No status line before
- **GIVEN** a settings file `{"model":"opus","env":{"A":"1"}}` with no `statusLine`
- **WHEN** the person runs `kudde-push usage on`
- **THEN** the file holds the same `model` and `env` text and a `statusLine` of type command running `'<path>/kudde-push' usage sensor --original '<kept file>'`, the kept file records that there was none, and it prints "Usage sensor on for <settings>. Claude Code's status line now runs through kudde-push and shows what it showed before. Undo: kudde-push usage off"

#### Scenario: An existing status line
- **GIVEN** a settings file with `"statusLine": {"type": "command", "command": "bash ~/bin/my-line.sh", "refreshInterval": 2}`
- **WHEN** the person runs `kudde-push usage on`
- **THEN** that entry's exact text is in the kept file, and the new entry runs the sensor and still has `"refreshInterval": 2`

#### Scenario: Folders and modes
- **GIVEN** a home with no `.config/kudde-push` and no `.local/state/kudde-push`
- **WHEN** the person runs `kudde-push usage on`
- **THEN** the kept file has mode 600, its folder and the readings folder have mode 700

#### Scenario: Two settings files
- **GIVEN** `kudde-push usage on` was run once with `CLAUDE_CONFIG_DIR` unset and once with it set to another folder, each settings file with its own status line
- **WHEN** both settings files are read
- **THEN** each entry names its own kept file, and each kept file holds that settings file's original

#### Scenario: Already on
- **GIVEN** the sensor is on
- **WHEN** the person runs `kudde-push usage on` again
- **THEN** the settings file and the kept original are unchanged, and it prints "Usage sensor is already on for <settings>."

#### Scenario: Moved binary, on again
- **GIVEN** the sensor is on, and kudde-push was then moved to another folder
- **WHEN** the person runs the moved `kudde-push usage on`
- **THEN** the entry's command now names the moved kudde-push, the kept original is unchanged, and it prints "Usage sensor on for <settings>, now run from <path>."

#### Scenario: A kept original from before
- **GIVEN** a kept original from an earlier `on`, and the person has since set a different status line by hand
- **WHEN** the person runs `kudde-push usage on`
- **THEN** nothing changes, and it prints "Not changed: an earlier status line from a previous usage on is kept in <file>. Delete that file if you no longer need it, then run kudde-push usage on again."

#### Scenario: Not JSON
- **GIVEN** a settings file that ends half way through an object
- **WHEN** the person runs `kudde-push usage on`
- **THEN** the file is unchanged, nothing is kept, it prints "Not changed: <settings> is not valid JSON." and it exits with a failure status

#### Scenario: Changed while writing
- **GIVEN** Claude Code rewrites the settings file between the sensor's read and its write, twice
- **WHEN** `kudde-push usage on` runs
- **THEN** Claude Code's last text is in the file, the sensor is not on, it prints "Not changed: <settings> kept changing while kudde-push wrote it. Try again." and it exits with a failure status

#### Scenario: A linked settings file
- **GIVEN** `settings.json` is a symbolic link to a file in another folder
- **WHEN** `kudde-push usage on` runs
- **THEN** the link is still a link, and the file it points to holds the new entry

#### Scenario: Never on by itself
- **GIVEN** kudde-push installed and its alert service running for a day, and `usage on` never run
- **WHEN** the settings file is compared with a copy from before
- **THEN** it is byte for byte the same, and alerts were sent as usual

### Requirement: Your status line shows exactly as before
`kudde-push usage sensor --original <file>` SHALL read its input to the
end, run the original command kept in `<file>` with `/bin/sh -c`, with
the same input, working folder and environment plus
`KUDDE_USAGE_SENSOR=1`, and SHALL print exactly what that command prints
on its output and error streams, and exit with its exit status (128 plus
the signal when it was killed). The sensor SHALL write nothing of its own
to either stream. Input over 1 MB SHALL still be passed whole, never cut,
and SHALL NOT be saved. An original that does not read its input, or
stops reading early, SHALL NOT make the sensor fail. With no original, a
kept file that is missing or unreadable, or an original whose type is not
`command` or that is null, the sensor SHALL print nothing and exit with
success. It SHALL save its reading after starting the command; a failure
or a crash while saving SHALL NOT change what is printed or the exit
status. When started with `KUDDE_USAGE_SENSOR=1` already set (a sensor
inside a sensor), it SHALL run nothing, save nothing, print nothing and
exit with success.

#### Scenario: Passed through
- **GIVEN** an original command that prints the input's `model.display_name` and a coloured bar on two lines, and exits 0
- **WHEN** Claude Code runs the sensor with a payload
- **THEN** the sensor prints the same bytes, colour codes included, and exits 0

#### Scenario: The command fails
- **GIVEN** an original command that prints "oops" on its error stream and exits 3
- **WHEN** the sensor runs
- **THEN** it prints "oops" on its error stream, nothing else, and exits 3

#### Scenario: Pipes and quotes
- **GIVEN** an original command `x=$(cat); printf '%s\n' "${#x}" | sed 's/^/len: /'; echo "a \"quoted\" word"`
- **WHEN** the sensor runs with a 2,000-byte payload
- **THEN** it prints `len: 2000` and `a "quoted" word` on two lines

#### Scenario: A path with a space
- **GIVEN** kudde-push lies in a folder named `my tools`
- **WHEN** `usage on` runs and Claude Code then runs the entry's command
- **THEN** the sensor runs and the original's output shows

#### Scenario: An original that never reads its input
- **GIVEN** an original command `echo ready`, and a payload of 200 KB
- **WHEN** the sensor runs
- **THEN** it prints "ready", exits 0, and saves the reading

#### Scenario: Over 1 MB
- **GIVEN** an original command `wc -c` and a 1.5 MB input
- **WHEN** the sensor runs
- **THEN** it prints the full byte count of the input, and no reading is saved

#### Scenario: No original
- **GIVEN** the sensor was turned on where there was no status line
- **WHEN** Claude Code runs the sensor
- **THEN** it prints nothing, exits 0, and still saves the reading

#### Scenario: An original that is not a command
- **GIVEN** a kept original `null`, or `{"type":"static","text":"hi"}`
- **WHEN** the sensor runs
- **THEN** it prints nothing and exits 0

#### Scenario: Saving fails
- **GIVEN** the readings folder cannot be written, or saving is made to crash
- **WHEN** the sensor runs
- **THEN** its output and exit status are those of the original command

#### Scenario: A sensor inside a sensor
- **GIVEN** a kept original whose command is itself `kudde-push usage sensor --original <that file>`
- **WHEN** Claude Code runs the sensor
- **THEN** the inner sensor runs nothing and exits 0, the outer one prints nothing and exits 0, and no process loop starts

### Requirement: The sensor keeps only what the phone needs
The sensor SHALL save, from each payload with a `session_id` of letters,
digits and dashes, under `$HOME/.local/state/kudde-push/usage/claude/`, a
file `<session_id>.json` holding the time of the reading (the desk's
clock, in Unix seconds), `context_window.context_window_size` when it is
a number, and from `rate_limits` each member that is an object with a
number `used_percentage`, with that share and its `resets_at` when a
number. It SHALL save nothing else from the payload: no prompt, path,
model, cost or workspace. Files SHALL be written atomically, readable by
the owner only (mode 600). On each save the sensor SHALL remove files in
that folder not written for 7 days. The sensor SHALL send nothing
anywhere: it SHALL open no network connection and no connection to a
core, and SHALL send no alert.

#### Scenario: A full payload
- **GIVEN** a payload with `session_id` "1f0c…", `context_window.context_window_size` 1000000, `rate_limits.five_hour` at 15% resetting at 1795208400 and `rate_limits.seven_day` at 70% resetting at 1795500000, and a `cwd`, `transcript_path` and `cost`
- **WHEN** the sensor runs
- **THEN** `usage/claude/1f0c….json` holds the time, the window 1000000 and both windows with their shares and resets, and no text of the `cwd`, `transcript_path` or `cost`

#### Scenario: A hostile session id
- **GIVEN** a payload whose `session_id` is "../../.bashrc"
- **WHEN** the sensor runs
- **THEN** no file is written, and the output is the original command's

#### Scenario: Old files go
- **GIVEN** a reading file last written 8 days ago and one written yesterday
- **WHEN** the sensor saves a reading
- **THEN** the 8-day-old file is gone and yesterday's is kept

#### Scenario: Owner only
- **WHEN** the sensor writes a reading
- **THEN** the file's mode is 600

#### Scenario: No network, no core
- **GIVEN** a desk with no network and no herdr-core running
- **WHEN** the sensor runs with a full payload
- **THEN** its output and saved reading are as usual, and it opened no socket

### Requirement: A payload without the fields degrades to tokens only
The sensor SHALL save what a payload has and leave out what it lacks.
When a payload has no `context_window.context_window_size` or no
`rate_limits` (an older Claude Code, or a login that has no plan limits),
the file SHALL hold the time and whichever of the two is there; the
phone then shows tokens only, no limits, or both. A payload that is not
JSON SHALL be passed to the original command unchanged, and nothing SHALL
be saved.

#### Scenario: No rate limits
- **GIVEN** a payload with a window size and no `rate_limits`
- **WHEN** the sensor runs
- **THEN** the file holds the time and the window and no windows

#### Scenario: Neither field
- **GIVEN** a payload with a `session_id` only
- **WHEN** the sensor runs
- **THEN** the file holds the time only, and the phone shows tokens only

#### Scenario: Not JSON
- **GIVEN** an input that is not JSON
- **WHEN** the sensor runs
- **THEN** the original command gets that input byte for byte, and no file is written

### Requirement: Turning it off puts back the original exactly
`kudde-push usage off` SHALL, when the `statusLine` entry is still the
sensor's (by value, as `on` judges it, not by its bytes), put back the
kept original text in its place, or remove the entry when there was
none, then delete the kept original. It SHALL delete the readings folder
only when no kept original for any settings file remains. When nothing
else in the file changed since `usage on`, the file SHALL be byte for
byte what it was before. When the entry is no longer the sensor's and
differs from the kept original, it SHALL change nothing, keep the
original, and say so. When the entry equals the kept original again (put
back by hand), it SHALL delete the kept original and say the sensor is
not on. When the kept original is missing or unreadable while the entry
is the sensor's, it SHALL change nothing and say so. The same atomic,
compare-before-write rules as `usage on` SHALL hold. `kudde-push usage
status` SHALL say whether the sensor is on for the settings file, name
it, and say when the entry's kudde-push or its kept original is missing.

#### Scenario: On and off again
- **GIVEN** any settings file, with or without a status line
- **WHEN** the person runs `kudde-push usage on` and then `kudde-push usage off`
- **THEN** the file is byte for byte as before, the kept original and the readings folder are gone, and it prints "Usage sensor off for <settings>. Your status line is back as it was."

#### Scenario: Reformatted by Claude Code in between
- **GIVEN** the sensor is on, and Claude Code then rewrote the file with the entry's members in another order and other spacing
- **WHEN** the person runs `kudde-push usage off`
- **THEN** the entry is recognised as the sensor's and the original status line is back

#### Scenario: Other settings changed in between
- **GIVEN** the sensor is on, and Claude Code then added `"theme":"dark"` to the file
- **WHEN** the person runs `kudde-push usage off`
- **THEN** the original status line is back, and `"theme":"dark"` is still there

#### Scenario: Status line replaced in between
- **GIVEN** the sensor is on, and the person then set another status line by hand
- **WHEN** the person runs `kudde-push usage off`
- **THEN** the settings file is unchanged, the kept original is still there, and it prints "Not changed: the status line in <settings> is no longer the sensor. Your earlier status line is kept in <file>."

#### Scenario: Put back by hand
- **GIVEN** the sensor is on, and the person then put the original status line back by hand
- **WHEN** the person runs `kudde-push usage off`
- **THEN** the settings file is unchanged, the kept original is deleted, and it prints "Usage sensor is not on for <settings>."

#### Scenario: Kept original lost
- **GIVEN** the sensor is on, and its kept original was deleted or holds half a JSON value
- **WHEN** the person runs `kudde-push usage off`
- **THEN** nothing changes, and it prints "Not changed: the earlier status line kept in <file> is missing or unreadable, so it cannot be put back. Change statusLine in <settings> by hand."

#### Scenario: Off when not on
- **GIVEN** the sensor was never turned on
- **WHEN** the person runs `kudde-push usage off`
- **THEN** nothing changes, and it prints "Usage sensor is not on for <settings>."

#### Scenario: Status
- **WHEN** the person runs `kudde-push usage status` before and after `usage on`
- **THEN** it prints "off: <settings>", then "on: <settings>"

#### Scenario: Status with a missing program
- **GIVEN** the sensor is on, and the kudde-push its entry names was moved away
- **WHEN** the person runs `kudde-push usage status` from the new place
- **THEN** it prints "on: <settings>, but it runs <path>, which is missing. Run kudde-push usage on to repair it."

#### Scenario: Status with a lost original
- **GIVEN** the sensor is on, and its kept original is missing
- **WHEN** the person runs `kudde-push usage status`
- **THEN** it prints "on: <settings>, but the kept status line <file> is missing. kudde-push usage off cannot put it back."
