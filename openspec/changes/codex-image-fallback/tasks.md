## 1. Build and verify
- [x] 1.1 Verify first-build premises against the worktree and supplied reports; do not read real logs.
- [x] 1.2 Replace invented fixtures with custom_tool_call, unrelated ImageView ids, intervening events and input_text/input_image output parts sharing a turn.
- [x] 1.3 Observe red fixtures before implementation; keep the strict pairing decision in pair_image.
- [x] 1.4 Observe the public Chat::image cache regression failing before fixing source separation and file revalidation.
- [x] 1.5 Reject raw references, validate the selected fetched part and preserve skipped-record barriers across reads and history reparsing.
- [x] 1.6 Add a bounded counts-only images_check example and measure synthetic tail overhead.
- [x] 1.7 Run crate suites, CI clippy, fmt and strict validation. The two sandbox socket failures remain for an unrestricted run.

## Scenario and regression evidence
All fixture tests below live in `crates/kudde-chat/tests/codex_images.rs`.
Each fixture runs the actual tail and parser. Accepted copies are fetched through
LOG_SCRIPT and asserted equal to the invented copy bytes. Incremental parsing
is compared with reparsing the retained history, including barriers.

| Scenario | Test |
|---|---|
| Deleted file, unrelated ids, intervening events | one_view_one_copy_with_intervening_events |
| Existing/recreated/replaced file; deletion again | mobile hub::chat::tests::image_rechecks_recreated_file_and_separates_copy_cache |
| Same number greater than one | same_number_more_than_one |
| Unequal counts | unequal_numbers |
| Missing copy | missing_copy |
| Copies without views | copies_without_view |
| View with no open call | view_without_open_call |
| View with multiple open calls | view_with_multiple_open_calls |
| Output call never seen | output_call_never_seen |
| Second output revokes a pair | second_output_revokes_pair |
| Output turn mismatch | mismatched_turn_ids |
| View turn mismatch | view_turn_mismatch |
| Malformed/unknown output part | unknown_output_part |
| Oversized copy and decoded-byte bound | oversized_copy |
| Review: forged reference | forged_reference_is_not_trusted; escaped_raw_reference_is_not_trusted |
| Review: selected field belongs to input_image | fetched_field_must_be_selected_input_image |
| Review: skipped record before output | skipped_between_view_and_output |
| Review: skipped record after pairing | skipped_after_pairing_revokes |
| Claude health unaffected by internal barriers | internal_skip_barriers_do_not_change_claude_format_health |
| Review: cache source isolation and file recheck | mobile hub::chat::tests::image_rechecks_recreated_file_and_separates_copy_cache |

The first fixture run had 16 failures; the no-open-call refusal already passed.
The mobile regression then failed on returning copy bytes after file recreation.
The additional escaped-reference regression failed on accepting the raw reference.
A further integration regression failed on internal barriers changing Claude format health; those barriers are now excluded from its format counts. All now pass. Existing tail assertions were preserved: barriers are explicitly
added for chat consumers rather than changing raw Chunk.text semantics.

## Tail cost (synthetic measurement, not a real-log estimate)
Historical first-build measurement, compared with dropping all response_item
records (current wire behavior is described in sections 3 and 5):
- custom_tool_call: 135 + call-id bytes + turn-id bytes of JSON, plus offset
  digits and two wire separator bytes. Supplied real-shape fixture: 204 JSON /
  209 wire bytes for call 1, 204 JSON / 210 wire bytes for call 2.
  Name, input, patches and all other call fields are omitted. An oversized
  call cannot fit the bounded read and becomes an uncertainty barrier.
- custom_tool_call_output: retained output structure and input_text, with each
  canonical image data URL replaced by @offset:index. Fixture: 269 JSON /
  274 wire bytes. Cost varies with text and part count; image bytes stay out.
- ImageView and intervening events: zero extra wire bytes versus existing tail.
- Skipped records: zero additional wire bytes; the existing skip header becomes
  a 38-byte internal barrier record for retained history.

The supplied report measured about 8% extra for outputs alone on 53 logs.
These call-header measurements cannot establish a new real-log percentage.

## Checks and morning follow-up
`cargo test -p kudde-chat -p kudde-mobile` passes the chat suite and 154 mobile
unit tests; only here_sockets_are_not_refused and
other_connect_failures_stay_connection_failures fail in this sandbox.
Rerunning with exactly those two skipped also completes integration/doc tests.
CI clippy, formatting and strict change validation pass.

Build the coordinator tool with `cargo build -p kudde-chat --example images_check`;
then run `target/debug/examples/images_check <log-file>`. It reads bounded chunks
to EOF and prints only viewed/paired/placeholder counts by reason, never content.
No real session logs were read in this build. The coordinator must check real-log
counts and rerun socket tests outside the sandbox. Positional widening remains
an owner decision; unseen format variants remain unverified.


## 2. Real-structure fixture correction
- [x] 2.1 Confirm the corrected card: eleven records, two calls, three views;
  read only the supplied sanitized evidence, never real session logs.
- [x] 2.2 Copy the supplied fixture byte-for-byte and observe
  `real_shape_fixture_pairs_only_the_single_view_call` fail (no paired count).
- [x] 2.3 Correct both response-item turn reads and preserve the nested field
  when the tail reduces calls to identity headers. Correct hand-made call and
  output fixtures, including the mismatched-turn and forged-reference cases.
- [x] 2.4 Generate one-image records around READ_MAX; observe the under/at
  pairing assertions fail first, then pass without changing limits.
- [x] 2.5 Run the required crate tests, CI clippy, fmt, strict spec validation
  and the counts example. The same two sandbox socket failures remain.

### Field audit against the supplied fixture
All paths below refer to raw records, before tail transformation.

| Field used | Real location and handling |
|---|---|
| Record discriminator | `type`: response_item for call/output; event_msg for views |
| Payload discriminator | `payload.type`: custom_tool_call, custom_tool_call_output, item_completed |
| Call identity, both records | `payload.call_id`; matched only to the observed call, never the view id |
| Call/output turn | `payload.internal_chat_message_metadata_passthrough.turn_id`; the reduced call header retains this nesting |
| View turn | `payload.turn_id` |
| View discriminator | `payload.item.type`: ImageView |
| View identity | `payload.item.id`: exec-prefixed, unrelated to call_id; not used for copy matching |
| Viewed file | `payload.item.path`; still preferred over the paired copy |
| Output parts | `payload.output`: array; count and validate parts, without positional pairing |
| Part discriminators | `payload.output[i].type`: input_text or input_image |
| Copy bytes | `payload.output[i].image_url`: data:image/png;base64,... in this fixture; tail replaces with its own @offset:index reference |
| Text/detail | `payload.output[i].text` and `.detail`; neither proves pairing; detail is ignored |
| Copy provenance | Byte offset of the output record and base64-field index are computed by the tail, not read from record ids or metadata |

`timestamp`, `ordinal`, response `payload.id`, `metadata`, call `name`/`input`,
metadata `create_time`, view thread id/timing, and token-usage records provide
no pairing evidence and are not required. Session metadata is not used for
pairing (the parser separately reads `payload.cwd` and `.cli_version`).
Intervening token-usage and CommandExecution records do not close a call.

### Record-size result (confidence: high)
`tail::READ_MAX` and SCRIPT's max are both 7,864,320 bytes (7.5 MiB).
AWK measures `length($0) + 1` under LC_ALL=C: UTF-8 bytes plus newline.
A complete one-image output is skipped when its total record size exceeds
7,864,320 bytes: the first skipped size is 7,864,321 (7,864,320 JSON bytes
plus one newline). An exact-limit record fits when read from its own start;
if preceding records use the current window, it waits for the next read.
The generated tests use 7,864,319, 7,864,320 and 7,864,321 bytes, padding an
input_text part alongside exactly one input_image. They assert skip offsets,
retained view counts, recovery/fetch below and at the bound, and conservative
placeholder behavior above it, including retained-history replay.
This is a whole-record limit, not an image-only size threshold: JSON envelope,
metadata, other parts and base64 expansion all consume it. LOG_SCRIPT also
reads at most 7,864,320 bytes; the existing decoded image cap is 8,000,000.
No limit changed.

### Added scenario evidence
- Real fixture: `real_shape_fixture_pairs_only_the_single_view_call` asserts
  three images, one fallback, two File placeholders, reason counts and exact
  PNG copy bytes from the first call via LOG_SCRIPT.
- Read boundary: `single_image_output_just_under_read_limit`,
  `single_image_output_exactly_at_read_limit`,
  `single_image_output_just_over_read_limit`.
- Unequal counts stay covered by `unequal_numbers` with corrected nesting;
  the supplied fixture does not contain an unequal-count call.
- Before implementation: the fixture alone failed; after correcting synthetic
  nesting and adding boundary tests, 19 passed and 4 failed. After the fix,
  all 23 image tests pass. Earlier scenario/review mappings above still apply.

Coordinator fixture check:
`cargo run -p kudde-chat --example images_check -- crates/kudde-chat/tests/fixtures/codex-real-shape.jsonl`

```text
viewed 3
paired 1
placeholder multiple images: 2
```

The record-size result establishes bounded synthetic behavior, not recovery
rates on real logs. The coordinator's later real-log check remains outstanding.


### Final check output for this round
- `cargo test -p kudde-chat --test codex_images`: 23 passed; 0 failed.
- `cargo test -p kudde-chat -p kudde-mobile`: chat suites pass; mobile ends
  `154 passed; 2 failed`; only `ssh::tests::here_sockets_are_not_refused` and
  `ssh::tests::other_connect_failures_stay_connection_failures` fail.
- `cargo test -p kudde-chat -p kudde-mobile -- --skip here_sockets_are_not_refused --skip other_connect_failures_stay_connection_failures`:
  mobile `154 passed; 0 failed; 2 filtered out`; restricted-key integration
  `3 passed; 0 failed`; both doc-test suites `0 passed; 0 failed`.
- `cargo clippy --locked --all-targets --features kudde-mobile/vt,kudde-mobile/spike -- -D warnings`:
  `Finished dev profile [unoptimized + debuginfo] target(s)`; no warnings.
- `cargo fmt --all --check`: exit 0, no output.
- `openspec validate codex-image-fallback --strict`:
  `Change 'codex-image-fallback' is valid`.
- `git diff --check`: exit 0, no output. Fixture matches the supplied bytes.

## 3. Second-review hardening
- [x] 3.1 Reproduce findings 1 and 3 through production SCRIPT, parse_chunk
  and Parser; observe all four new regression tests fail before implementation.
- [x] 3.2 Move JSON record classification and call reduction into parse_chunk,
  using serde_json and only payload-owned identities. Preserve Claude content.
- [x] 3.3 Emit barriers for oversized records whose prefixes cannot establish
  irrelevance, including response_item with its subtype beyond byte 200.
- [x] 3.4 Run all existing Claude fixtures and crate suites, CI clippy, fmt,
  strict validation and the real-shape counts example.
- [x] 3.5 **Kotlin cache revalidation (review finding 2):**
  Recheck `copy:` thumbnails every 30 seconds on remount; viewer opens always recheck, retaining displayed pixels on failure.
  Share observable decoded pixels across thumbnail/viewer; add fake-byte, injectable-clock Android regressions (not run here; coordinator checks pending).

### Reproduction evidence (confidence: high)
Tests in `crates/kudde-chat/tests/codex_images.rs` run the production shell,
chunk parser and conversation parser; pairing checks also compare incremental
history with reparsing. Generated data stays in the build directory.

| Finding | Regression | Before | After |
|---|---|---|---|
| 1: oversized output subtype after byte 200, before pairing | review_padded_skip_before_pairing | paired 1, expected 0 | paired 0; skipped-record barrier retained |
| 1: same oversized output after pairing | review_padded_skip_after_pairing | paired 1, expected 0 | paired 0; skipped-record barrier retained |
| 3: earlier unrelated object contains identities | review_header_uses_only_payload_identity | paired 0, expected 1 | paired 1 using payload identities; copy fetched |
| 3: Claude input.type is custom_tool_call | review_claude_nested_call_type_is_preserved | tool row lost | original row and parsed tools preserved |
| 2: decoded bitmap retained under unchanged key | next card 3.5 | review documents Kotlin bypass | deliberately unfixed; no Kotlin edits |

### Decision and wire cost
Use Rust's existing serde_json parser for authoritative structural classification
and call reduction. Section 5 adds a narrow shell fast path for fixed canonical
prefixes; any deviation still reaches parse_chunk. parse_chunk reads top-level
type, payload.type, payload.call_id and
payload.internal_chat_message_metadata_passthrough.turn_id; it reduces only
actual Codex calls. Nested unrelated identities cannot participate. Claude
rows remain unchanged by this classification. Inputs cross the bounded wire
but are removed before retained history. No dependency or byte limit changed.

Current wire cost, superseding the first-build shell-header measurements:
- Supplied fixture call 1: 483 wire bytes instead of 209, **+274**.
- Supplied fixture call 2: 397 wire bytes instead of 210, **+187**.
- Fixture token-usage rows: **0 wire bytes** (formerly 758 and 764).
- Outputs, views and other retained event rows: **0 extra wire bytes**.
- Canonical known irrelevant response/world-state records cost zero wire bytes.
  Their noncanonical variants cross at scrubbed JSON length plus offset digits
  and two separator bytes, then Rust drops them structurally.
- Only the last canonical token_count per read crosses the wire; noncanonical
  counts also cross. Rust chooses the count with the greatest file offset.
- General call overhead versus reduction equals scrubbed full JSON bytes minus
  the former reduced header bytes (offset framing cancels). Inputs can consume
  the existing 7,864,320-byte read budget; oversized calls become barriers.
  Retained history does not keep those inputs. No real-log percentage is inferred.

The shell suppresses a skip marker only for the existing anchored api-request
family or a canonical prefix proving a fixed irrelevant kind. An unknown,
noncanonical, call/output or token-count oversized record still gets a barrier.

### Check output
- `cargo test -p kudde-chat -p kudde-mobile`: all chat suites pass, including
  existing Claude fixture tests and 27 codex_images tests. Mobile: `154 passed;
  2 failed`; only `here_sockets_are_not_refused` and
  `other_connect_failures_stay_connection_failures`, as anticipated by the card.
- With exactly those two tests skipped: mobile `154 passed; 0 failed;
  2 filtered out`; restricted-key integration `3 passed; 0 failed`;
  both doc-test suites `0 passed; 0 failed`.
- CI clippy: `Finished dev profile [unoptimized + debuginfo]`; no warnings.
- `cargo fmt --all --check`: exit 0, no output.
- `openspec validate codex-image-fallback --strict`:
  `Change 'codex-image-fallback' is valid`.
- `cargo run -p kudde-chat --example images_check -- crates/kudde-chat/tests/fixtures/codex-real-shape.jsonl`:
  `viewed 3`, `paired 1`, `placeholder multiple images: 2`.
- `git diff --check`: exit 0, no output. Cargo checks used `--offline`;
  TMPDIR was inside the worktree. No real session logs were read.

## 4. Equal-count positional pairing (007e)
- [x] 4.1 Verify the starting strict rule, 27 tests and eleven-record fixture.
- [x] 4.2 Observe the updated two-view and real-shape tests and all new
  regressions fail before implementation; fetch distinct two/three-view bytes
  through LOG_SCRIPT and compare incremental parsing with retained history.
- [x] 4.3 Pair equal, nonzero view/copy counts by completion/output position;
  validate all copies before assigning any, preserving every other refusal.
- [x] 4.4 Update the proposal/spec with the owner's rule and known ordering
  limit; sum each tail read's stdout length in images_check.
- [x] 4.5 Run crate suites, CI clippy, fmt, strict validation and fixture counts.
  Task 3.5 remains open; this card changes no Kotlin or dependencies.

### Before/after evidence (confidence: high)
The five multi-view refusal regressions first require a valid two-pair baseline,
then test the refusal. This prevents the previous blanket multi-view refusal
from passing a new regression without exercising revocation or atomic refusal.
All new cases compare incremental results with reparsed retained history;
accepted copies are fetched through production LOG_SCRIPT.

| Test | Failure before implementation | Result after |
|---|---|---|
| same_number_more_than_one (approved expectation change) | 0 pairs, expected 2 | Two distinct copies fetched in position |
| three_views_pair_by_position_with_interleaved_text | 0 pairs, expected 3 | Three distinct copies fetched, ignoring text |
| real_shape_fixture_pairs_all_three_views_by_position (approved rename/expectations) | paired Some(1), expected Some(3) | Exact PNG bytes matched to all three paths |
| many_second_output_revokes_all_pairs | baseline 0 pairs, expected 2 | Second output revokes both |
| many_skipped_before_output_refuses_all | baseline 0 pairs, expected 2 | Barrier prevents both |
| many_skipped_after_pairing_revokes_all | baseline 0 pairs, expected 2 | Barrier revokes both |
| many_one_view_turn_mismatch_refuses_all | baseline 0 pairs, expected 2 | Neither view paired |
| many_one_forged_copy_refuses_all | baseline 0 pairs, expected 2 | Valid first copy is also refused |
| example_reports_position_pairs_and_total_wire_bytes | paired 1, placeholder multiple images: 2, no wire line | Exact counts and wire total match independent tail reads |

### Check output
All Cargo commands used --offline and a TMPDIR within the worktree.
- `cargo test --offline -p kudde-chat -p kudde-mobile`: all chat suites pass,
  including 33 image tests and the example integration test. Mobile ends
  `154 passed; 2 failed`; only `here_sockets_are_not_refused` and
  `other_connect_failures_stay_connection_failures`, the anticipated sandbox
  socket failures.
- With exactly those two tests skipped: mobile `154 passed; 0 failed;
  2 filtered out`; restricted-key integration `3 passed; 0 failed`;
  both doc-test suites `0 passed; 0 failed`.
- `cargo clippy --offline --locked --all-targets --features kudde-mobile/vt,kudde-mobile/spike -- -D warnings`:
  `Finished dev profile [unoptimized + debuginfo]`; no warnings.
- `cargo fmt --all --check`: exit 0, no output.
- `openspec validate codex-image-fallback --strict`:
  `Change 'codex-image-fallback' is valid`.
- `cargo run --offline -p kudde-chat --example images_check -- crates/kudde-chat/tests/fixtures/codex-real-shape.jsonl`:

```text
viewed 3
paired 3
wire bytes 6027
```

Earlier strict-rule evidence above records earlier rounds; this section and
current proposal/spec supersede that policy. No prior work was discarded and
no real logs were read. The coordinator still runs the real-log check and the
socket tests outside the sandbox; Kotlin cache revalidation remains task 3.5.


## 5. Conservative wire trimming (007g)
- [x] 5.1 Verify a clean tree, structural Rust filtering and canonical fixture prefixes;
  measure the unchanged fixture at 6027 wire bytes, viewed 3 and paired 3.
- [x] 5.2 Observe all five production-script wire regressions fail before implementation.
- [x] 5.3 Drop only fixed irrelevant canonical prefixes; buffer the last canonical
  token count within the existing wire budget. Preserve structural classification,
  scrubbed call identities/outputs, byte offsets and uncertainty barriers.
- [x] 5.4 Correct wire-cost documentation and verify the fixture at 4505 bytes.
- [x] 5.5 Run the required suites, CI clippy, formatting and strict validation;
  complete the suites with exactly the two known sandbox socket tests skipped.

### Prefix and ordering decisions (confidence: high)
The fast path inspects at most the first 200 bytes, starting at byte zero:
`timestamp` (an unescaped string), `ordinal` (a nonnegative integer), `type`,
then `payload`. For response_item and event_msg, payload.type must come first.
For world_state and token_usage_record the top-level kind alone suffices after
`payload:{`; the fixture's token-usage payload has no type field. No content or
nested field is searched. Unknown subtypes, different key order, extra prefix
keys, whitespace and long/escaped timestamps pass through for Rust to decide.
All listed dropped kinds are unused by the parser; none needed exemption.

Only response_item message, reasoning, additional_tools, function_call,
function_call_output and configuration_update, plus world_state and
token_usage_record, are unconditionally hidden. custom_tool_call and
custom_tool_call_output remain scrubbed full records on the wire. Every event
other than earlier canonical token_count records remains. The last canonical
count is emitted at the end of the read. A later noncanonical count may already
have crossed, so Rust chooses by original file offset, preserving existing
last-count semantics. The buffered count is included in the output budget.
Oversized token counts still need barriers: they are not unconditionally hidden.

### Tests first: observed failures
All tests are in `crates/kudde-chat/tests/tail_wire.rs`. Each runs production SCRIPT.
The forgery/deviation tests also include a canonical drop control, proving the
filter is active while their preservation assertions stay intact.

| New test | Before implementation |
|---|---|
| wire_drops_only_known_prefixes_and_keeps_last_count | message record crossed the wire |
| wire_forged_prefixes_cannot_hide_claude_or_codex_output | canonical control was not filtered; forged rows survived |
| wire_deviations_pass_through_for_structural_parsing | canonical control was not filtered; deviations survived |
| wire_oversized_known_drop_has_no_barrier_but_unknown_does | hidden message had a skip barrier, expected none |
| wire_mixed_count_formats_keep_the_latest_by_file_offset | earlier canonical count crossed the wire |

After implementation: all five pass. Existing tests were unchanged, including
payload identity, nested Claude input, padded oversized skips and Claude fixtures.

### Check output
All Cargo commands used --offline; TMPDIR was under the worktree's target/.
- `cargo test --offline -p kudde-chat -p kudde-mobile`: all chat suites pass;
  mobile `154 passed; 2 failed`, only the known sandbox socket tests
  `here_sockets_are_not_refused` and `other_connect_failures_stay_connection_failures`.
- With exactly those two skipped: mobile `154 passed; 0 failed; 2 filtered out`;
  integration and doc tests pass.
- `cargo clippy --offline --locked --all-targets --features kudde-mobile/vt,kudde-mobile/spike -- -D warnings`:
  `Finished dev profile`; no warnings.
- `cargo fmt --all --check`: exit 0.
- `openspec validate codex-image-fallback --strict`:
  `Change 'codex-image-fallback' is valid`.
- `images_check` fixture: `viewed 3`, `paired 3`, `wire bytes 4505`.
  Before: 6027 bytes. Reduction: 1522 bytes, exactly the two token-usage rows.
- `git diff --check`: exit 0.

No real logs, phone or live infrastructure were accessed. The coordinator still
measures real-log wire cost and reruns the two socket tests unrestricted.


## 7. Review fixes (007h)
- [x] 7.1 Verify both review premises and observe the new cache-recheck and
  later-turn regressions fail before implementation.
- [x] 7.2 Distinguish confirmed absence from all failed file rechecks internally;
  preserve the byte-returning bridge and separate file/copy caches.
- [x] 7.3 Retire unresolved calls at a later turn, preserving placeholders,
  late-output refusal, same-turn ambiguity and skipped-record revocation.
- [x] 7.4 Add the two review scenarios without changing positional pairing,
  dependencies, Kotlin or existing test assertions.
- [x] 7.5 Run both crate suites, CI clippy, fmt, strict validation and
  task 4.5's images_check fixture count; record any sandbox socket failures.

### Regression evidence (confidence: high)
Promise 1: `hub::chat::tests::failed_file_rechecks_never_restore_a_cached_copy`
first failed by returning cached `copy` bytes after a failed file read.
It covers uncached/cached fallback after absence, promotion to changed file
bytes, nonzero exit, timeout, truncation, empty reply, RPC error, partial bytes,
malformed header, no connection, directory and oversized-file refusal.

Promise 2: `skipped_output_does_not_block_a_later_turn` and
`missing_output_does_not_block_a_later_turn` first failed with zero pairs
instead of one. Both run the production tail and compare incremental parsing,
line-by-line reads and a full retained-history reparse. They assert that the
old view remains a placeholder and only the new view pairs despite late old
outputs. `a_later_turn_view_never_belongs_to_an_earlier_call` refuses a
cross-turn view; `two_open_calls_in_the_same_later_turn_stay_uncertain`
preserves the same-turn ambiguity rule. `an_unknown_call_turn_stays_uncertain`
ensures an unknown turn is not treated as a proven earlier turn. Existing tests and pair_image remain
unchanged.


### Check output
All Cargo builds ran sequentially with `-j 3` and `--offline`; the chat suite
also set `CARGO_BUILD_JOBS=3` for its existing nested example invocation.
- `cargo test -j 3 --offline -p kudde-chat`: 163 tests passed, including
  all 38 image regressions; final doc-test result `0 passed; 0 failed`.
- `cargo test -j 3 --offline -p kudde-mobile`: `155 passed; 2 failed`.
  Only `ssh::tests::here_sockets_are_not_refused` and
  `ssh::tests::other_connect_failures_stay_connection_failures` failed with
  the anticipated sandbox socket restrictions.
- Repeating the mobile command with exactly those two tests skipped:
  `155 passed; 0 failed; 2 filtered out`; restricted-key integration
  `3 passed; 0 failed`; doc-tests `0 passed; 0 failed`.
- `cargo clippy -j 3 --offline --locked --all-targets --features kudde-mobile/vt,kudde-mobile/spike -- -D warnings`:
  `Finished dev profile [unoptimized + debuginfo]`; no warnings.
- `cargo fmt --check`: exit 0, no output.
- `openspec validate codex-image-fallback --strict`:
  `Change 'codex-image-fallback' is valid`.
- `cargo run -j 3 --offline -p kudde-chat --example images_check -- crates/kudde-chat/tests/fixtures/codex-real-shape.jsonl`:
  `viewed 3`, `paired 3`, `wire bytes 4505`.
- `git diff --check`: exit 0. All 33 pre-existing image tests and all 12
  pre-existing mobile chat unit tests are unchanged, as is `pair_image`.

Morning follow-up: rerun the two socket checks outside the sandbox. The known
positional-pairing design risk remains outside this card; no Gradle, phone,
network Git or live infrastructure commands were run. Changes remain unstaged
and uncommitted.
