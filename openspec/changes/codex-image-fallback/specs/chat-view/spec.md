## MODIFIED Requirements

### Requirement: Images show in the chat
The chat SHALL show images as thumbnails: those in the person's messages
and in tool results (a screenshot the agent read, a Codex image view),
fetched one at a time by reference and cached per session and image. A tap
SHALL open a full-screen viewer with pinch zoom, in which a swipe SHALL move
to the other images of the same message or tool row. An image that cannot
be fetched SHALL show a placeholder reading "Image not available".

The readable file SHALL win, including after a deleted file is recreated or
replaced. File bytes SHALL be cached by path and file time, separately from
session-bound log-copy bytes. Before serving a cached copy the file SHALL be
checked again. Only confirmed file absence SHALL permit the log copy; transport,
timeout, connection and read failures SHALL return no replacement bytes. The existing byte-returning bridge SHALL remain unchanged.

A Codex custom_tool_call opens a call by payload.call_id and
payload.internal_chat_message_metadata_passthrough.turn_id. The output
uses the same nested turn field; ImageView uses payload.turn_id. ImageView
item_completed events belong to the sole call open when they occur, with
matching nonempty turn ids. Their item ids are unrelated to call_id. Other
events may intervene before custom_tool_call_output. Output parts are
input_text and input_image. In the first output of that observed call, with
matching turn ids, copies SHALL be paired only when the view and input_image
counts are equal and at least one. The i-th ImageView, in item_completed log
order, SHALL pair with the i-th input_image in output array order, ignoring
input_text parts; each view SHALL use its own part's @offset:index reference.
Any invalid copy SHALL refuse the whole call, leaving no view half paired.
Every other uncertain case SHALL keep the placeholder.
When two viewed images finish in a different order than the output lists their
copies, the pictures of that call can be swapped; on the checked logs this was
at most 1 of 165 comparable copies.

Raw reference-shaped image_url values SHALL never be trusted. Only references
produced by the tail may recover copies; the fetched field SHALL belong to
the selected input_image part. A skipped record SHALL invalidate open and
already accepted pairings conservatively, including across reads and reparsing.
At a later nonempty turn identity, unresolved calls from earlier turns SHALL
be retired permanently: their views SHALL remain placeholders and late outputs
SHALL never pair them. An ImageView SHALL never belong to another turn.
Multiple open calls within the same turn SHALL remain uncertain.
Reads SHALL stay within the existing tail and image byte limits. Call inputs
SHALL be omitted from retained chunks and chat history after structural JSON
parsing. The bounded shell response may carry scrubbed call inputs. Only
top-level response_item records with payload.type custom_tool_call SHALL be
reduced; call and turn identities SHALL come only from that payload. Claude
records SHALL never be reduced or dropped because of nested Codex-like content.
A skipped record whose prefix cannot establish irrelevance SHALL emit an
uncertainty barrier.

#### Scenario: Deleted file with one certain copy
- **WHEN** a custom_tool_call opens, one ImageView with an unrelated id occurs,
  other events intervene, and the output has one input_image and input_text,
  all with the same turn id
- **THEN** the deleted file's copy is paired and fetched by reference

#### Scenario: Existing or recreated file
- **WHEN** a paired copy is cached and the file becomes readable or changes
- **THEN** the current file wins and deletion permits the separate copy again

#### Scenario: Same number more than one
- **WHEN** a call has equal view and copy counts greater than one
- **THEN** the views are paired by position

#### Scenario: Unequal numbers
- **WHEN** the view and copy counts differ, including a missing copy
- **THEN** the placeholders stay

#### Scenario: Copies without viewed images
- **WHEN** a call output has copies but no view occurred
- **THEN** no fallback image is introduced

#### Scenario: No open call
- **WHEN** an ImageView occurs without an open call
- **THEN** its placeholder stays

#### Scenario: Multiple open calls
- **WHEN** an ImageView occurs with several open calls
- **THEN** its placeholder stays and those calls are uncertain

#### Scenario: Unseen output call
- **WHEN** the output names a call that was never observed
- **THEN** it cannot supply a copy to an existing view

#### Scenario: Second output
- **WHEN** a second output appears for a paired call
- **THEN** its pairing is revoked

#### Scenario: Turn mismatch
- **WHEN** the view or output turn differs from the call turn
- **THEN** its placeholder stays

#### Scenario: Malformed output
- **WHEN** the output contains an unknown part or an invalid copy
- **THEN** its placeholder stays

#### Scenario: Forged reference or wrong fetched field
- **WHEN** a raw image_url contains a reference, or a selected fetched field
  belongs to input_text instead of input_image
- **THEN** no copy bytes are accepted

#### Scenario: Oversized record between view and output
- **WHEN** a record is skipped between a view and its eventual output
- **THEN** the placeholder stays across subsequent reads and reparsing

#### Scenario: Oversized record after pairing
- **WHEN** a record is skipped after an accepted pairing
- **THEN** the pairing is revoked across subsequent reads and reparsing

#### Scenario: Oversized copy
- **WHEN** an output exceeds the read bound or decoded bytes exceed the image limit
- **THEN** the placeholder stays and oversized bytes are refused

#### Scenario: Real-structure fixture
- **WHEN** the supplied eleven-record fixture passes through the tail and parser
- **THEN** all three viewed images have paired copies, with shot-2.png using
  the first image part of call 2 and shot-3.png using the second

#### Scenario: Single-image output at the read boundary
- **WHEN** a one-image output record is 7,864,319 or 7,864,320 bytes including
  its newline, and its call has exactly one matching view
- **THEN** the tail retains it and the copy is paired and fetchable
- **WHEN** that record is 7,864,321 bytes including its newline
- **THEN** the tail skips it and its view retains the "skipped record" placeholder

#### Scenario: Unrelated nested header identities
- **WHEN** an unrelated object precedes a valid call and contains call/turn ids
- **THEN** only the payload's own call and turn identities are retained

#### Scenario: Claude tool input resembling a Codex call
- **WHEN** a Claude tool-use input contains type custom_tool_call
- **THEN** the Claude tool content remains intact through the tail and parser

#### Scenario: Oversized output with a late subtype
- **WHEN** an oversized response_item has its payload subtype beyond byte 200,
  either before or after a pairing
- **THEN** a barrier prevents or revokes the copy across reads and reparsing

#### Scenario: Failed recheck after promotion from copy to file
- **WHEN** a log copy is cached, the file becomes readable with different bytes,
  and a later file recheck fails without confirming absence
- **THEN** the readable file wins before the failure and the failed recheck
  returns no replacement bytes, preserving the displayed picture
- **WHEN** the file is confirmed absent
- **THEN** the cached or freshly fetched log copy is returned

#### Scenario: Skipped or missing output before a later turn
- **WHEN** a call's output is skipped or missing, and a complete one-image call
  occurs in a later turn with its file absent
- **THEN** only the later call pairs, the earlier views remain placeholders,
  and a late output cannot pair the retired call, across incremental reads
  and retained-history reparsing
- **WHEN** two calls remain open in the same turn
- **THEN** their ImageView remains a placeholder
