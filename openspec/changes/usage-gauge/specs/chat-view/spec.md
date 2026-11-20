## MODIFIED Requirements

### Requirement: A context gauge shows in the top bar
The chat's title bar SHALL show how full the session's context is, as the
ring of "The context gauge is a small ring". For Codex the share SHALL be
the input tokens of the last request against the model's context window,
both from the log's last token count. For Claude Code the used tokens
SHALL be the input plus cache tokens of the last assistant record; the
share SHALL be known only when the usage sensor has reported the
session's window size, and SHALL be those tokens against that window.
Without that window Claude Code's gauge SHALL show tokens only, with no
share. The share SHALL be the plain ratio, rounded to a whole percent. A
tap SHALL open the usage sheet. When the log holds no token count yet,
the chat SHALL show no gauge. The terminal's title bar SHALL show no
gauge.

#### Scenario: A Codex session
- **GIVEN** a Codex log whose last token count reports 223k input tokens and a 475k window
- **WHEN** the chat's title bar shows
- **THEN** the gauge is a ring filled to 47%, and a screen reader reads "Context, 47% used"

#### Scenario: A Claude Code session without the sensor
- **GIVEN** a Claude Code log whose last assistant record used 283k input and cache tokens, and no sensor reading for the session
- **WHEN** the chat's title bar shows
- **THEN** the ring draws no share, and a screen reader reads "Context, 283k tokens used"

#### Scenario: A Claude Code session with the sensor
- **GIVEN** the same log, and a sensor reading for the session with a window of 1,000,000 tokens
- **WHEN** the chat's title bar shows
- **THEN** the ring is filled to 28%, and a screen reader reads "Context, 28% used"

#### Scenario: No token count yet
- **GIVEN** a chat whose log holds no token count
- **WHEN** the title bar shows
- **THEN** there is no gauge

#### Scenario: The terminal
- **GIVEN** the terminal of an agent whose chat shows a gauge
- **WHEN** the terminal's title bar shows
- **THEN** there is no gauge

## ADDED Requirements

### Requirement: The context gauge is a small ring
The gauge SHALL be a small circular ring and SHALL show no text in the
title bar. With a known share the ring SHALL be filled to that share, up
to full; a share over 100% SHALL fill the ring and SHALL be named as it
is in the sheet. With no known share the ring SHALL show its track only.
The ring and the sheet SHALL use one colour at every level, with no
warning colour. The gauge SHALL be at least 48 dp to the touch and SHALL
have the role of a button; a tap SHALL open the usage sheet and SHALL
send nothing to the terminal. Usage SHALL show nowhere else in the app:
not on the Agents list, a machine card or the terminal.

#### Scenario: Filled to the share
- **GIVEN** a Codex chat that used 223k of a 475k window
- **WHEN** the title bar shows
- **THEN** the ring is filled to 47% and no "47%" text shows in the bar

#### Scenario: Over the window
- **GIVEN** a Claude Code chat that used 230k tokens, with a sensor window of 200,000
- **WHEN** the title bar shows
- **THEN** the ring is full, a screen reader reads "Context, 115% used", and the sheet shows "115% used"

#### Scenario: Track only
- **GIVEN** a Claude Code chat with tokens and no sensor window
- **WHEN** the title bar shows
- **THEN** the ring shows its track and no filled part

#### Scenario: A tap opens the sheet
- **WHEN** the person taps the ring
- **THEN** the usage sheet opens, no line shows under the title bar, and nothing is sent to the terminal

#### Scenario: Nowhere else
- **GIVEN** a Codex agent whose log reports a weekly window at 90%
- **WHEN** the Agents list and the machine cards show
- **THEN** neither shows any usage

### Requirement: The usage sheet shows the context and the plan limits
A tap on the gauge SHALL open a sheet over the chat, titled "Usage", with
the agent and machine under the title. Its first section, "Context",
SHALL show the used and window tokens ("223k of 475k tokens") with a bar
and the share ("47% used") when the share is known, and the used tokens
("283k tokens used") with no bar when it is not. Its second section,
"Plan limits", SHALL show each window the agent reports, in the order
reported, each with its name from its length ("5 hours" for 300 minutes,
"Week" for 10,080, otherwise whole hours below a day and whole days
from a day), a bar, its share ("41% used") and its reset ("Resets 15:00"
today, "Resets Tue 07:00" on a later day, in the phone's clock format and
time zone). A Codex limit with a name SHALL add it after the window's name
("Week · <name>"). Shares SHALL be rounded to whole percents. Token
counts SHALL read as today below a million ("900", "223k") and in
millions from there ("1M", "1.5M"). The sheet
SHALL follow the chat while open: a new reading updates it in place. It
SHALL close on Back, a tap above it, or a swipe down, and closing SHALL
leave the chat as it was. The sheet SHALL show no credits, spend, plan
name or buttons to buy, reset or change anything.

#### Scenario: A Codex sheet
- **GIVEN** a Codex chat at 223k of 475k tokens, whose last token count reports one window of 10,080 minutes at 6.0% resetting next Tuesday at 07:00
- **WHEN** the person opens the sheet
- **THEN** it shows "Context", "223k of 475k tokens", "47% used", then "Plan limits" with one row "Week", "6% used", "Resets Tue 07:00"

#### Scenario: A Claude Code sheet with the sensor
- **GIVEN** a Claude Code chat at 283k tokens, and a sensor reading with a window of 1,000,000, a 5-hour window at 41% resetting today at 15:00 and a weekly window at 70% resetting next Tuesday at 07:00
- **WHEN** the person opens the sheet
- **THEN** it shows "283k of 1M tokens", "28% used", then "5 hours", "41% used", "Resets 15:00" and "Week", "70% used", "Resets Tue 07:00", in that order

#### Scenario: Window names from their length
- **WHEN** windows of 300, 10,080, 120 and 2,880 minutes are reported
- **THEN** they are named "5 hours", "Week", "2 hours" and "2 days"

#### Scenario: A named Codex limit
- **GIVEN** a Codex token count whose `rate_limits` has `limit_name` "Turbo" and a weekly primary window
- **WHEN** the sheet shows
- **THEN** the row is named "Week · Turbo"

#### Scenario: Live while open
- **GIVEN** the sheet is open on a Codex chat showing "47% used"
- **WHEN** a new token count arrives with 250k of 475k
- **THEN** the sheet shows "250k of 475k tokens" and "53% used" without closing

#### Scenario: Closing
- **GIVEN** the sheet is open
- **WHEN** the person presses Back, taps above the sheet, or swipes it down
- **THEN** the sheet closes, the chat shows as before at the same place, and nothing was sent to the terminal

#### Scenario: Nothing to act on
- **GIVEN** a Codex token count whose `rate_limits` has credits and a plan type
- **WHEN** the sheet shows
- **THEN** it shows neither, and holds no button but the ways to close it

### Requirement: Codex's plan limits come from its logs
The chat SHALL read the plan limits from the `rate_limits` object of the
last `token_count` event that has one, in the open log and, while the
sheet is open, in the machine's other Codex logs written in the last 24
hours (at most the 20 newest, through herdr-core, once when the sheet
opens and at most once a minute while it stays open). Limits are the
account's, so the sheet SHALL show the reading with the latest time among
them; a log whose reading cannot be read SHALL be skipped. Each of `primary` and `secondary`
that is an object with a number `used_percent` SHALL be one window, with
`window_minutes` as its length and `resets_at` (Unix seconds) as its reset
when they are numbers; a window without a length SHALL be named by its
place ("Window"), one without a reset SHALL show no reset line. A null or
missing window SHALL be left out. `limit_name`, when a non-empty string,
SHALL name the limit. Every other field (credits, plan type, spend
control) SHALL NOT be read into the app. The reading's time SHALL be the
record's `timestamp`. A `token_count` with `rate_limits` null, missing or
not an object SHALL keep the earlier reading. A log with no reading SHALL
show "Codex has not reported its limits yet." in the sheet's limits
section.

#### Scenario: One weekly window
- **GIVEN** a token count with `"rate_limits":{"limit_id":"codex","limit_name":null,"primary":{"used_percent":6.0,"window_minutes":10080,"resets_at":1795500000},"secondary":null,"credits":{"has_credits":false},"plan_type":"team"}`
- **WHEN** the chat reads it
- **THEN** it holds one window: 6% used, 10,080 minutes, resetting at 1795500000, with no name, and nothing of credits or plan type

#### Scenario: Two windows
- **GIVEN** a token count whose primary is 300 minutes at 12.5% and secondary is 10,080 minutes at 40%
- **WHEN** the sheet shows
- **THEN** it shows "5 hours", "13% used" first and "Week", "40% used" second

#### Scenario: A later count without limits
- **GIVEN** a token count with a weekly window at 6%, then a later one with `rate_limits` null
- **WHEN** the chat reads both
- **THEN** it still shows the weekly window at 6%, as of the first count's time

#### Scenario: Odd fields
- **GIVEN** a token count whose primary has `used_percent` "six" and whose secondary has `used_percent` 3 and no `window_minutes`
- **WHEN** the chat reads it
- **THEN** it holds one window, named "Window", "3% used", and the chat shows as normal

#### Scenario: A window without a reset
- **GIVEN** a token count whose primary is 10,080 minutes at 20% with no `resets_at`
- **WHEN** the sheet shows
- **THEN** the row shows "Week", a bar and "20% used", and no reset line

#### Scenario: A fresher reading in another session
- **GIVEN** the open Codex log's last reading, made at 09:00, has the weekly window at 20%, and another Codex log on the machine has a reading made at 11:00 with 35%
- **WHEN** the sheet opens
- **THEN** the weekly row shows "35% used", and no "As of" line shows when the phone's clock reads 11:05

#### Scenario: No scan while the sheet is closed
- **GIVEN** a Codex chat open for ten minutes with the sheet closed
- **WHEN** the reads are counted
- **THEN** no other Codex log was read

#### Scenario: No limits in any log
- **GIVEN** Codex logs with token counts but never a `rate_limits` object
- **WHEN** the sheet opens
- **THEN** the context section shows as normal and the limits section reads "Codex has not reported its limits yet."

### Requirement: Claude Code's window and limits come from the usage sensor
For an open Claude Code chat the app SHALL read, in one call through
herdr-core as it reads logs, the usage sensor's file for the chat's
session and the sensor's newest file of any session on the agent's
machine, when the chat opens, when the sheet opens, and at most once a
minute while the chat shows. The session file's window size SHALL give
the share of "A context gauge shows in the top bar". Limits are the
account's, so the windows of the reading with the latest time among the
two SHALL be the sheet's plan limits, `five_hour` as "5 hours" first and `seven_day` as
"Week" second; a window of another id SHALL be left out, as its length is
not known. The app SHALL read only
the window size, the windows and the time of the reading; a file that is
missing, too large, not readable or not of the expected shape SHALL count
as no reading and SHALL NOT show an error in the chat. The sheet SHALL
say which case applies: no sensor on the machine, a sensor whose Claude
Code reports no limits, no window size for this session yet, or no
reading at all yet. The last two SHALL say that a project's own status
line keeps the sensor from running.
A Codex chat SHALL NOT read the sensor's files.

#### Scenario: No sensor on the machine
- **GIVEN** a Claude Code chat on a machine where the sensor was never turned on
- **WHEN** the sheet opens
- **THEN** the context shows "283k tokens used" and "The window size comes with the usage sensor.", and the limits section reads "Claude Code shares its limits only with its status line. Run kudde-push usage on at the machine to see them here."

#### Scenario: A sensor, no limits in the payload
- **GIVEN** a sensor reading for the session with a window size and no windows
- **WHEN** the sheet opens
- **THEN** the context shows its share, and the limits section reads "This Claude Code does not report its limits to its status line."

#### Scenario: A sensor, no reading for this session yet
- **GIVEN** the sensor is on, and its folder holds a reading from another session made 2 minutes ago with the 5-hour window at 41%, but none for this session
- **WHEN** the sheet opens
- **THEN** the gauge shows tokens only, the context section reads "No window size for this session yet. It comes when Claude Code draws its status line, unless this project sets its own.", and the limits show "5 hours", "41% used"

#### Scenario: A sensor, no reading at all
- **GIVEN** the sensor is on, and its folder holds no reading
- **WHEN** the sheet opens
- **THEN** the gauge shows tokens only, and the limits section reads "No reading yet. It comes when Claude Code draws its status line, unless this project sets its own."

#### Scenario: The freshest limits win
- **GIVEN** this session's reading made at 10:00 with the weekly window at 60%, and another session's made at 10:20 with 62%
- **WHEN** the sheet shows
- **THEN** the window share comes from this session's reading and the weekly row shows "62% used"

#### Scenario: A broken file
- **GIVEN** the session's file holds half a JSON object
- **WHEN** the chat reads it
- **THEN** the gauge shows tokens only, the chat shows no error, and the next read a minute later is made as usual

#### Scenario: Reading stays bounded
- **GIVEN** a Claude Code chat open for ten minutes with the sheet closed
- **WHEN** the reads are counted
- **THEN** at most eleven reads were made, each one call

#### Scenario: Codex never reads it
- **GIVEN** a Codex chat on a machine with the sensor on
- **WHEN** the chat and its sheet open
- **THEN** no read of the sensor's files is made

### Requirement: Old limit readings are marked
Each agent's limits SHALL carry the time of their reading. When that
reading is older than 15 minutes by the phone's clock, the limits section
SHALL show "As of <time>" under its title. A window whose reset time has
passed SHALL show no bar and no share and SHALL read "Reset at <time>, no
reading since", until a newer reading comes. A reading SHALL never be
hidden for its age alone. The context section SHALL NOT be marked: its
tokens come from the log itself.

#### Scenario: A fresh reading
- **GIVEN** a reading made 3 minutes ago
- **WHEN** the sheet shows
- **THEN** no "As of" line shows

#### Scenario: An old reading
- **GIVEN** a Codex chat whose last limit reading was made today at 14:02, and the phone's clock shows 15:30
- **WHEN** the sheet shows
- **THEN** "As of 14:02" shows under "Plan limits", and the windows show their shares as read

#### Scenario: Past the reset
- **GIVEN** a reading of a 5-hour window at 92% resetting at 15:00, and the phone's clock shows 15:20
- **WHEN** the sheet shows
- **THEN** the row reads "5 hours" and "Reset at 15:00, no reading since", with no bar and no share

#### Scenario: A newer reading after the reset
- **GIVEN** the row of the scenario above
- **WHEN** a reading arrives with the 5-hour window at 3% resetting at 20:00
- **THEN** the row shows a bar, "3% used" and "Resets 20:00"

### Requirement: The gauge and the sheet can be heard and reached
The ring SHALL be read as one node: its spoken value ("Context, 47%
used" with a known share, "Context, 283k tokens used" without), the role
of a button and the action "Show usage". With a known share it SHALL carry
range information; without one it SHALL NOT. When the sheet opens, a
screen reader SHALL hear "Usage" and its focus SHALL move into the
sheet. Each window row SHALL be read as one line: name, share and reset
("5 hours, 41% used, resets at 15:00"), or name and the reset that passed.
Every control in the sheet SHALL be at least 48 dp to the touch, and the
sheet SHALL scroll rather than cut text at the largest font size.

#### Scenario: The ring heard
- **GIVEN** TalkBack is on and a Codex chat at 47%
- **WHEN** focus lands on the ring
- **THEN** TalkBack reads "Context, 47% used", a button, with the action "Show usage", once

#### Scenario: Tokens only, heard
- **GIVEN** a Claude Code chat without the sensor
- **WHEN** focus lands on the ring
- **THEN** the node reads "Context, 283k tokens used" and carries no range information

#### Scenario: A row heard
- **GIVEN** the sheet shows a 5-hour window at 41% resetting at 15:00
- **WHEN** focus lands on that row
- **THEN** it is read as "5 hours, 41% used, resets at 15:00", in one node

#### Scenario: Largest font
- **GIVEN** the phone's largest font size and a sheet with context and two windows
- **WHEN** the sheet shows on a 412 dp wide phone
- **THEN** no text is cut or overlaps, and the sheet scrolls to its last row
