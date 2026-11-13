## ADDED Requirements

### Requirement: Stay connected is an option, off by default
The Machines screen SHALL have a "Stay connected" row with a switch; a tap
anywhere on the row toggles it in place, and screen readers hear it as a
switch with its state. The second line SHALL read "Keeps the connection open
in the background" while off, "Shows a notification, uses more battery" while
on, or "Notifications are off" while on with the notification permission
refused or the Status channel blocked. It SHALL be off on a fresh install and
after an update from a version without it. Turning it on SHALL ask for the
notification permission when it is not granted; the option SHALL work whether
or not the permission is granted.

#### Scenario: Fresh install
- **WHEN** Ezra opens the Machines screen for the first time
- **THEN** the row reads "Stay connected" over "Keeps the connection open in the background", its switch is off, and no status notification is shown

#### Scenario: Turned on
- **WHEN** Ezra taps the row while it is off and notifications are allowed
- **THEN** its switch is on, it reads "Shows a notification, uses more battery", and the status notification appears

#### Scenario: Notifications refused
- **WHEN** Ezra turns the option on and refuses the notification permission
- **THEN** its switch is on, the row reads "Notifications are off", and the connection still stays up in the background

### Requirement: While on, connections stay up in the background
The app SHALL keep its connections open in the background while the option is
on, by running a foreground service of type `specialUse`; android-client-v1's
"Connections end in the background" holds only while it is off. The service
SHALL be started only from the foreground (turning the option on, or an
activity start while it is on) and SHALL stop when the option is turned off.
After the process has ended, nothing SHALL restart it until the app is opened.

#### Scenario: Pocketed phone
- **WHEN** the option is on and the app has been in the background for 10 minutes with the screen off and the network steady
- **THEN** the agent list is current on return and the machine's SSH log shows no new login between leaving and returning

#### Scenario: Doze
- **WHEN** the option is on and the phone is forced into Doze
- **THEN** the connections stay open and agent changes still reach the notification

#### Scenario: Turned off
- **WHEN** Ezra turns the option off and leaves the app
- **THEN** the status notification is gone and 60 s later no SSH session remains open

#### Scenario: Process ended
- **WHEN** the option is on and Android ends the app's process
- **THEN** no service restarts by itself, and opening the app connects as today and brings the notification back

### Requirement: Reconnecting in the background never prompts
A dropped connection SHALL reconnect without any prompt while the option is on
and the app is in the background, by the V1 retry rules. When a reconnect
needs the key and its authentication window has closed, the machine SHALL
wait. Each unlock of the phone SHALL retry, once, every machine that waits for
the key or waits out a backoff. When an activity starts while a machine waits
for the key and no prompt has been shown since the app last spent 60 s in the
background, the app SHALL retry once without a prompt and SHALL show the
fingerprint prompt only if the key is still needed.

#### Scenario: Network switch while pocketed
- **WHEN** the phone moves from Wi-Fi to mobile data with the app in the background, within 30 minutes of an unlock
- **THEN** the machines reconnect with no prompt, at the latest once the phone is unlocked, and the notification returns to its counts

#### Scenario: Key window closed while locked
- **WHEN** the connection drops while the phone has been locked for more than 30 minutes
- **THEN** the machines stay disconnected and no prompt or alerting notification appears

#### Scenario: Unlocking reconnects
- **WHEN** a machine waits for the key and Ezra unlocks the phone without opening the app
- **THEN** the machine reconnects with no fingerprint prompt

#### Scenario: Unlocking with the app on top
- **WHEN** a machine waits for the key and Ezra unlocks the phone with the app as the top screen
- **THEN** the machine reconnects and no fingerprint prompt appears

#### Scenario: Retry still needs the key
- **WHEN** a machine waits for the key and the retry after an unlock still needs it
- **THEN** no further retry happens until the next unlock or until the app is opened, where one prompt appears

#### Scenario: Prompt refused, then a quick switch
- **WHEN** Ezra cancels the fingerprint prompt and switches back to the app within 60 s
- **THEN** no connection attempt and no prompt follow by themselves

#### Scenario: Opening from an alert after a refused prompt
- **WHEN** Ezra cancelled a prompt, the app then spent more than 60 s in the background, and he taps an alert while the key's window is closed
- **THEN** one fingerprint prompt appears, then the terminal opens

### Requirement: The status notification shows counts only
The app SHALL show one ongoing notification on a "Status" channel of low
importance while the option is on, and it SHALL never make a sound, vibrate or
alert. Its text SHALL be a function of the agent list, the machine states and
whether the hub started, SHALL be reposted only when that text changes, and
SHALL name no agent, project, folder or machine, so the lock screen shows it in
full. Counts SHALL include only agents on connected machines: "need you"
counts agents that need you or are in error, "done" counts agents that
finished and have not been opened in the app since, "working" counts working
agents.

With at least one machine connected, the title SHALL be the counts in that
order joined by " · ", each left out at zero ("2 need you · 1 done · 3
working", "1 needs you", "2 done", "3 working"), or "Nothing needs you" when
all three are zero; the second line SHALL be "Connected" when every
machine is connected, "Connected to <n> of <m> machines · unlock to reconnect"
when another machine waits for the key, else "Connected to <n> of <m>
machines".

With no machine connected there SHALL be no second line, and the title SHALL
be, in this order: "Not connected · open the app" when the hub could not
start; "Disconnected · unlock to reconnect" when a machine waits for the key;
"Reconnecting" when a machine is connecting or retrying by itself; "Not
connected · open the app" when a machine needs the person in the app (a host
key to confirm or a changed one, a version too old on either side, the Network
permission off); "No machines" when the list is empty.

Tapping it SHALL open the app as it was left and SHALL never open a terminal
by itself. A notification swiped away SHALL come back the next time its text
changes.

#### Scenario: Agents busy
- **WHEN** one machine is connected with two agents needing permission, one in error and three working
- **THEN** the notification reads "3 need you · 3 working" over "Connected"

#### Scenario: One needs you
- **WHEN** one agent needs you and none is working on the only, connected machine
- **THEN** the notification reads "1 needs you" over "Connected"

#### Scenario: Finished and not yet opened
- **WHEN** on the only, connected machine one agent is in error, two finished and have not been opened in the app since, and three are working
- **THEN** the notification reads "1 needs you · 2 done · 3 working" over "Connected"

#### Scenario: A finish opened in the app
- **WHEN** Ezra opens one of two finished agents in the app and nothing else changes
- **THEN** the notification reads "1 done", and once Ezra opens the other it reads "Nothing needs you"

#### Scenario: Quiet
- **WHEN** every agent is idle, or finished and opened in the app since
- **THEN** the notification reads "Nothing needs you" over "Connected"

#### Scenario: One of two machines down
- **WHEN** WSL is connected with one working agent and the second machine is unreachable with two agents needing you as of its last connection
- **THEN** the notification reads "1 working" over "Connected to 1 of 2 machines"

#### Scenario: One of two machines waits for the key
- **WHEN** WSL is connected with nothing to do and the second machine waits for the key
- **THEN** the notification reads "Nothing needs you" over "Connected to 1 of 2 machines · unlock to reconnect"

#### Scenario: Waiting for the key
- **WHEN** no machine is connected and one waits for the key
- **THEN** the notification reads "Disconnected · unlock to reconnect"

#### Scenario: Reconnecting
- **WHEN** no machine is connected and one is retrying after a drop
- **THEN** the notification reads "Reconnecting"

#### Scenario: A host key to confirm
- **WHEN** the only machine presents a changed host key
- **THEN** the notification reads "Not connected · open the app"

#### Scenario: No machines
- **WHEN** the option is on and the machine list is empty
- **THEN** the notification reads "No machines"

#### Scenario: Progress ticks
- **WHEN** a working agent's progress changes and the counts and machine states do not
- **THEN** the notification is not reposted

#### Scenario: Private lock screen
- **WHEN** the lock screen shows silent notifications and hides sensitive content
- **THEN** the lock screen still shows the counts

#### Scenario: Swiped away
- **WHEN** Ezra swipes the status notification away and an agent then starts working
- **THEN** the notification comes back with the new counts and the connections were kept throughout

#### Scenario: Tap
- **WHEN** Ezra taps the status notification
- **THEN** the app opens as it was left, and no terminal opens by itself

### Requirement: Alerts stay with ntfy
The status notification SHALL never post, replace or cancel an agent alert,
and the live connection SHALL NOT alert. Alerts SHALL come only through
UnifiedPush as in the android-client-v1 alerts capability, whether the option
is on or off.

#### Scenario: Agent needs you while connected
- **WHEN** the option is on, the app is in the background and an agent stays blocked past the watcher's delay
- **THEN** exactly one "Needs you" alert arrives, through ntfy, and the status notification only changes its counts

### Requirement: An open terminal detaches after 60 s in the background
A terminal open when the app leaves the foreground SHALL be detached once the
app has spent 60 s in the background while the option is on, keeping its last
screen, so the desk takes its own size back. Returning to the app SHALL reopen
it, showing that screen until the live one is drawn. Returning before the
detach SHALL find it still attached. Every return, after one or many
background stays, SHALL leave a terminal that takes input.

#### Scenario: Terminal left open, screen on
- **WHEN** Ezra switches from an open terminal to another app with the option on and comes back after 5 minutes
- **THEN** the desk had its own size back 60 s after he left, and the terminal shows its last screen and then the live one at the phone's size

#### Scenario: Terminal left open, screen off
- **WHEN** Ezra locks the phone with a terminal open and the option on
- **THEN** the terminal is detached within about a minute after the screen next turns on, unless the app is reopened first, and on return it is live and takes input either way

#### Scenario: Quick switch
- **WHEN** Ezra switches away from an open terminal for 20 s
- **THEN** the terminal is still attached on return, with no reopen

#### Scenario: Two background stays in a row
- **WHEN** Ezra leaves an open terminal for 5 minutes, returns, and does it again
- **THEN** each stay detaches it and each return reopens it, and typing works after both
