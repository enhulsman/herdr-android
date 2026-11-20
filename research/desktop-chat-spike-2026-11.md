> Copied from the `spike/desktop-chat` branch (c94443e), where the harness, the scripts and the JavaScript reader (`desk/spike/reader.js`) that these measurements name live. Decision 7 was settled from it on 2026-11-20: the JavaScript copy.

# Desktop chat: real-core measurements


Best tested full first open: **3033 ms**, fails the 300 ms limit. Best tested incremental: **575 ms**, fails the 50 ms limit.

Best capped combination: **852 ms** first open; **150 ms** incremental (best among capped runs). Neither limit passes. Confidence: high for these CLI observations, unknown for app-window timings.

## Premise and method

The branch had the extracted `kudde_chat::core::ChatCore`; the old spike
still held only a Parser. The working tree was clean before this task.
The adapter now calls `take_lookup`, `take_read`, `take_older` and `view` on
the real shared core. That crate is unchanged. No fallback was built.
The previous report is superseded by this run; its app probes remain
unrun and their source remains available for an operator.

All results below are this run's measurements. Each case ran three times;
tables give medians. The machine was running other work tonight. The
breakdown overlapped initial compiler measurements; best-combination and
slice runs were sequential after the matrix. These are comparisons under
load, not statistically significant rankings of close compiler results.
QuickJS-ng reports `0.18.0`, Binaryen `133`, Rust `1.95.0` in this run.

The compiler matrix changes only one setting from Rust `z`, wasm-opt `-Oz`,
wasm2js `-Oz`. Release uses LTO, one codegen unit, panic abort and stripped
symbols. The build script records the complete feature/lowering flags.
It defaults to rejecting an oversized module; the matrix explicitly allows
oversized artifacts for measurement, without making them eligible.

The benchmark creates distinct synthetic user-message records with ASCII
bodies, rather than repeating fixture identifiers. The first read contains
2,000,000 log bytes starting after an equally sized older segment; append
contains 6,000 bytes. Older loading adds the preceding 2,000,000 bytes to
that retained history. Maximum append spans the core's 7,864,320-byte cap
on top of 2,000,000 retained bytes. Offsets and tail framing are included
in bridge inputs. Transport and module startup are excluded. This record
mix is controlled, not a distribution of real sessions, tool outputs or
images. Fixture parity separately covers both supported agents and Unicode.

## Measured levers

Every row changes only its named lever relative to the default, except
explicit combinations. Times include request encoding, allocation, copy,
request JSON parsing, tail parsing, core work, full view clone, JSON value
construction, serialization, reply allocation, copy out, decoding, JSON
parsing and cleanup. Reset/drop of the previous core is included; allocator memory can be reused
between repetitions. Diagnostic parser replay can perturb subsequent allocator
behavior; the ordinary matrix timings are the main comparison.
Profiling uses staged ABI calls with no event-loop
yield; their sum is synchronous work, not asynchronous elapsed time.
The ABI exposes these stages only in the throwaway spike.

| Lever | First open ms | Incremental ms | core.js bytes | Complexity / eligibility |
|---|---:|---:|---:|---|
| rust-z | 3432 | 1305 | 969581 | Default; no extra production complexity |
| rust-s | 3354 | 1070 | 1273958 | Build flag only |
| rust-3 | 3370 | 1064 | 1575282 | Build flag only; **over the limit** |
| wasm-O3 | 3504 | 1302 | 983730 | Build flag only |
| wasm-O4 | 3466 | 1282 | 982991 | Build flag only |
| js-O0 | 4662 | 1556 | 1580305 | Build flag only; **over the limit** |
| js-O3 | 3653 | 1357 | 981436 | Build flag only |
| js-O4 | 4100 | 1192 | 982506 | Build flag only |
| preencoded | 3228 | 1259 | 969581 | Input UTF-8 precomputed outside timer; lower bound only, output decode still timed |
| fast | 3033 | 1028 | 969581 | ASCII typed-array / chunked native string fast path; Unicode portable fallback |
| delta | 4443 | 658 | 969581 | Compare against last returned snapshot; still clones/builds full view; retains previous JSON rows |
| cap | 960 | 315 | 969581 | 512,000-byte first cap; less initial history, same full-view boundary |
| best-full | 3078 | 575 | 1273958 | Best eligible compiler setting + fast codec + delta; full initial history |
| best | 852 | 158 | 1273958 | Same combination with 512,000-byte first cap |
| slices 16000 | 10922 | 885 | 1273958 | Sum of slice calls; largest 1000 ms; record batching, deferred view; Rust s |
| slices 4000 | 17093 | 925 | 1273958 | Sum of slice calls; largest 1019 ms; record batching, deferred view; Rust s |
| capped-combined-slices 16000 | 1178 | 150 | 1273958 | Sum of slice calls; largest 289 ms; record batching, deferred view; Rust s |
| capped-combined-slices 4000 | 1620 | 150 | 1273958 | Sum of slice calls; largest 266 ms; record batching, deferred view; Rust s |


The preencoded input row measures a lower bound for avoiding script-side
UTF-8 encoding; it does not measure the app Buffer implementation. The
fast codec is a separately measured usable script optimization, not an
assumption that native conversion is free. Non-ASCII falls back to the
existing Unicode/surrogate-safe codec.

The delta prototype returns changed rows relative to the prior stored
snapshot and a byte-position revision marker. It reduces output work,
but still calls the unmodified full `ChatCore::view()` and builds the full
JSON value before comparing. It is not a production arbitrary-revision
protocol: removals/reordering, missed revisions and multiple consumers
need explicit handling. A real D10 change must track dirty rows/revisions
inside the shared core and avoid the full clone, construction and comparison.
No speed for that unbuilt change is claimed.

## Breakdown under qjs

Values below are independent medians, so columns need not sum exactly.
A zero means below the effective clock resolution, not free work.
Core work includes internal message parsing, retention and key maintenance;
older loading also reparses all retained lines. The parser column is a
separately timed fresh `Parser::feed_lines` replay of the incoming chunk,
excluded from the total. It is diagnostic, **not an exact partition** of
internal parsing versus core state work. In particular older loading parses
more than the incoming chunk. The public core API does not expose that
boundary; its exact internal split remains unmeasured. This part of the
card is only partially fulfilled. No subtraction is
presented as a measured state-only cost.

| Case | Request JSON / reset | Tail parse | Parser replay diagnostic | Core including parsing | View clone | View JSON construction | JSON serialize | JS UTF-8 encode/decode | Memory allocate/copy/reply | JS JSON parse | Total excluding replay |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| first | 290 | 103 | 1517 | 1653 | 71 | 288 | 403 | 685 | 106 | 2 | 3601 |
| incremental | 1 | 1 | 0 | 109 | 77 | 267 | 394 | 280 | 62 | 2 | 1188 |
| older | 274 | 132 | 1504 | 16111 | 143 | 655 | 868 | 1090 | 169 | 4 | 19206 |
| maximum | 1256 | 645 | 5996 | 53561 | 354 | 1341 | 2188 | 3053 | 466 | 9 | 64375 |


First open: core work is the largest stage; fresh-parser replay suggests
parsing is substantial. Small append: rebuilding, serializing and decoding
the retained view dominate collectively. Older loading: the core's retained
history reparse/key work dominates. Maximum append: core parsing/state/key
work dominates. Confidence: high for stage measurements, moderate for
attributing internal core costs without instrumentation inside that crate.

## Bounded slices

The slice prototype feeds whole records into the real core and defers view
creation until the last call. It verifies the final full JSON equals an
unsliced core read, including keys and metadata. It does not defer or omit
the last view cost. Record boundaries can exceed the requested slice size. Slice-row incremental
timings append one synthetic 6,000-byte record after the sliced open, using
the same view mode. The unsliced matrix uses multiple records in that byte
budget, so these incremental comparisons also differ in record shape.
No event-loop scheduling was measured; summed time excludes possible delays
between calls. Numbers below are medians of three full sliced opens.

| Requested bytes per slice | Actual total bytes | Calls | Largest call ms | Final call ms | Sum of calls ms |
|---|---:|---:|---:|---:|---:|
| 16000 | 2001390 | 119 | 1000 | 1000 | 10922 |
| 4000 | 2001390 | 356 | 1019 | 1019 | 17093 |

Capped combination with slicing (same compiler, fast codec and delta; final view still included):

| Requested bytes per slice | Actual total bytes | Calls | Largest call ms | Final call ms | Sum of calls ms |
|---|---:|---:|---:|---:|---:|
| 16000 | 513366 | 31 | 289 | 289 | 1178 |
| 4000 | 513366 | 92 | 266 | 266 | 1620 |

The capped combined slice runs reached a median largest call of 266 ms; that meets the 300 ms per-call budget, while total open time still exceeds it.


Neither full-history slicing scheme brings every call under the first-open limit;
the final full view remains expensive, and total first-open latency stays
well above the limit. These runs do not prove that every possible bounded
state/view implementation must fail. They do show that slicing reads alone
is insufficient. No number of slices can be promised to meet both limits
for the full-history case from these measurements. The capped combination
append still exceeds the 50 ms budget. Incremental and older work also need bounded
state/key/view work; merely postponing it would hide a stall.

## Interpretation for decision 7

The converted real core fails the CLI timing gates under every tested
eligible combination, including the smaller first cap. Compiler flags and
bridge reductions do not remove the core/history/view costs. Confidence:
high for this measured failure; moderate that the same mechanisms threaten
the app window. This is evidence for the owner to revisit decision 7, not
an app-window pass/fail or a choice of fallback.

QuickJS-ng CLI and the app's embedded QuickJS share no demonstrated timing
calibration here. App engine version/build options, host CPU, allocator,
Buffer codec, GPUI layout/render costs, concurrent panes and scheduling are
unknown. CLI calls execute synchronously, matching the important inability
to interrupt one long core call, but absolute milliseconds cannot be
transferred to the app. Window tests and the remaining UI probes still
require the operator. No host app, phone, network transport or live session
was touched. No package/tool installs, commits or staging were made.

## Reproduce and evidence

Run from the repository root, offline:

```sh
scripts/spike-build-desk.sh && qjs scripts/spike-qjs-check.mjs
scripts/spike-test-native.sh
qjs scripts/spike-profile-check.mjs
CARGO_TARGET_DIR="$PWD/target" CARGO_NET_OFFLINE=true cargo test -p kudde-chat
qjs scripts/spike-bench.mjs baseline breakdown
qjs scripts/spike-bench.mjs rust-z normal
scripts/spike-matrix.sh
qjs scripts/spike-bench.mjs fast fast
scripts/spike-best.sh
SPIKE_RUST_OPT=s scripts/spike-build-desk.sh
qjs scripts/spike-slices.mjs best
scripts/spike-build-desk.sh
python3 scripts/spike-report.py
```

The report generator reads the JSON artifacts in `target/desk-spike/`.
The best combination's selected compiler settings are recorded below.
Generated modules and raw logs stay ignored under the worktree target.
The required check exercises the real core with both fixture agents,
whole/incremental/Unicode/error replies, raw tail and older reads, and
compares against the native oracle. The dedicated profile check and native
boundary suite check staged-vs-ordinary output, delta omission and codec
parity. Tests were written and observed failing before the related changes.


Combination used: Rust `s`, wasm-opt `-Oz`, wasm2js `-Oz`, ASCII fast codec, snapshot delta; capped variant reads 512,000 bytes.


Final parity/timing check output:

```text
parity ok (Claude Code + Codex, full + incremental + Unicode + errors)
qjs real-core proxy: first 2003970 bytes 5997 ms; incremental 5982 bytes 1407 ms (medians)
samples [{"first":6016,"incremental":1429},{"first":5997,"incremental":1407},{"first":5887,"incremental":1392}]
proxy only; app-window limits remain unmeasured
profile and delta ok
```

Shared chat suite: 159 passed, no failures. Spike boundary suite: seven tests passed. No shared-core source or lockfile changes.

### Raw timing samples from this run

Each list contains the three total times in milliseconds; diagnostic parser replay is excluded.


- rust-z: first [3742, 3418, 3432]; incremental [1349, 1305, 1262]

- rust-s: first [3416, 3354, 3222]; incremental [1100, 1070, 1025]

- rust-3: first [3370, 3247, 3593]; incremental [1064, 1222, 1017]

- wasm-O3: first [3687, 3480, 3504]; incremental [1317, 1302, 1262]

- wasm-O4: first [3640, 3466, 3446]; incremental [1282, 1287, 1239]

- js-O0: first [4898, 4577, 4662]; incremental [1626, 1556, 1517]

- js-O3: first [3750, 3534, 3653]; incremental [1265, 1357, 1358]

- js-O4: first [4287, 4100, 3553]; incremental [1437, 1192, 1189]

- preencoded: first [3560, 3225, 3228]; incremental [1259, 1274, 1249]

- fast: first [3134, 3012, 3033]; incremental [1028, 1032, 977]

- delta: first [4443, 4642, 4169]; incremental [658, 904, 640]

- cap: first [960, 1328, 920]; incremental [308, 355, 315]

- best-full: first [3300, 3078, 3011]; incremental [572, 575, 575]

- best: first [836, 859, 852]; incremental [158, 158, 158]

- breakdown first: [3601, 3574, 3610]

- breakdown incremental: [1188, 1279, 1178]

- breakdown older: [18529, 19206, 19995]

- breakdown maximum: [64449, 64375, 61501]


Slice samples: `[{"cap": 16000, "repeat": 0, "bytes": 2001390, "slices": 119, "max": 1106, "total": 11129, "last": 1106, "incremental": 929, "moduleBytes": 1273958}, {"cap": 16000, "repeat": 1, "bytes": 2001390, "slices": 119, "max": 991, "total": 10922, "last": 991, "incremental": 874, "moduleBytes": 1273958}, {"cap": 16000, "repeat": 2, "bytes": 2001390, "slices": 119, "max": 1000, "total": 10886, "last": 1000, "incremental": 885, "moduleBytes": 1273958}, {"cap": 4000, "repeat": 0, "bytes": 2001390, "slices": 356, "max": 1019, "total": 17099, "last": 1019, "incremental": 953, "moduleBytes": 1273958}, {"cap": 4000, "repeat": 1, "bytes": 2001390, "slices": 356, "max": 977, "total": 17068, "last": 977, "incremental": 899, "moduleBytes": 1273958}, {"cap": 4000, "repeat": 2, "bytes": 2001390, "slices": 356, "max": 1026, "total": 17093, "last": 1026, "incremental": 925, "moduleBytes": 1273958}]`



Combined slice samples: `[{"cap": 16000, "repeat": 0, "bytes": 513366, "slices": 31, "max": 301, "total": 1193, "last": 301, "incremental": 149, "moduleBytes": 1273958}, {"cap": 16000, "repeat": 1, "bytes": 513366, "slices": 31, "max": 269, "total": 1157, "last": 269, "incremental": 154, "moduleBytes": 1273958}, {"cap": 16000, "repeat": 2, "bytes": 513366, "slices": 31, "max": 289, "total": 1178, "last": 289, "incremental": 150, "moduleBytes": 1273958}, {"cap": 4000, "repeat": 0, "bytes": 513366, "slices": 92, "max": 264, "total": 1617, "last": 264, "incremental": 149, "moduleBytes": 1273958}, {"cap": 4000, "repeat": 1, "bytes": 513366, "slices": 92, "max": 266, "total": 1634, "last": 266, "incremental": 150, "moduleBytes": 1273958}, {"cap": 4000, "repeat": 2, "bytes": 513366, "slices": 92, "max": 267, "total": 1620, "last": 267, "incremental": 150, "moduleBytes": 1273958}]`

## Floors for the two fallbacks

Measured 2026-11-20T00:01:12Z, on Linux x86_64, QuickJS-ng 0.18.0, rustc 1.95.0 (59807616e 2026-04-14). The machine was busy tonight. Every timing below is the median of seven sequential calls from this run; no earlier timing is reused. Confidence: high for these local CLI observations; unknown for the app window or another machine.

| Fallback | First open ms (300 ms limit) | Small append ms (50 ms limit) | Largest record ms (50 ms reference) |
|---|---:|---:|---:|
| JavaScript syntax/field floor under qjs | 3.403 (below) | 0.012 (below) | 0.004 (below) |
| Native real core, fresh process per call | 35.671 (below) | 42.903 (below) | 1.241 (below) |

The largest record uses the interactive 50 ms limit only as a reference, not a separate signed-off gate. For the native row these are local process elapsed times, not work on the window thread. Neither row establishes that a built fallback passes the app gates.

Input is the unchanged `text` generator extracted at runtime from `scripts/spike-bench.mjs`: 2,000,000 ASCII bytes / 1,014 distinct user records, followed by 6,000 bytes / 3 records. The largest complete record across those inputs is 2,044 bytes including its newline; it is timed alone. This dataset has no multi-megabyte single record, tools, images or Codex turn mix.

The JavaScript timer includes splitting lines, the engine's `JSON.parse` for every nonempty line, and allocating/appending plain role/text/tool-name rows. Small append keeps the initial list; no full view clone or serialization is done. Input generation and file loading are outside timing. `os.now()` microseconds are converted to milliseconds; a test checks its units against the wall clock. Microsecond-sized samples are floors, not reliable predictions of equally small production stalls.

The native release oracle calls the real `ChatCore` via its existing adapter (`parse`, then `append`). Each timed call starts and exits a fresh process, consumes prepared request JSON through a pipe, and writes full view JSON through stdout. Timing includes process start/exit, pipe transfer, request decoding, core work and all output serialization; Python result decoding is outside it. The append call replays the initial history and emits both the initial and updated full views in the same fresh process. It is therefore a conservative measurement of this oracle path, not an isolated retained-state append or a demonstrated minimal helper protocol. The adapter constructs chunks from the same log bytes at offset zero; unlike 001b it does not parse tail framing or start after an older segment. It does not run log lookup, read a real log file, cross a network or exercise the host app's `exec` API (which has no stdin). A deployable helper would need its own file/argument interface.

The stripped native binary is **455,984 bytes**, measured with `stat` and verified as stripped with `file`; it is a dynamically linked Linux x86_64 release build, opt-level z, LTO, one codegen unit and panic abort from `spike-build-desk.sh`. Under qjs, `JSON.parse` of its exact first-open stdout (1,981,444 bytes) takes **1.746 ms**. This separate script-side timer excludes receiving/decoding the output string, loading the file and rendering the view.

JavaScript copy costs not shown by the floor:

- Two parsers must stay in step as both private log formats change. Confidence: high.
- The real rules add tool grouping/results, queued messages, Codex turn ordering, format-health checks, stable keys, bounded history, images and metadata; parity requires replaying both agents and comparing phone/desk rows and send decisions. Confidence: high.
- Source surface counted tonight, physical lines including comments/blanks and embedded shell: `lib.rs` 1,233 (parser/types/helpers), `core.rs` 570 (history/state/view), `locate.rs` 258, `tail.rs` 150, `send.rs` 96, `image.rs` 58: 2,365 total, roughly 2.4k lines of behavior to mirror or retain through equivalent scripts. The parser/state portion alone is 1,803; `core_tests.rs` 889 is excluded. These are Rust source counts, not a JavaScript line-count or effort forecast. Confidence: high for counts, moderate for scope estimate.

Program costs not shown by the floor:

- A binary must be built, signed and installed/updated for every supported agent OS/architecture; this run proves only Linux x86_64. Confidence: high.
- Every agent machine needs that executable plus a compatible protocol/version and error handling; local startup timings exclude remote round trips, file I/O, installer work and UI work. Confidence: high.
- A gallery mod-folder install alone does not put a binary on agent machines. It would need a documented separate signed-helper install step (or an explicitly designed installer) fetching the matching release onto each agent machine; no such gallery-to-machine delivery was built or tested here. Confidence: high for the extra distribution requirement, unknown for unattended gallery installation.

Reproduce from the repository root, offline; these scripts require only the existing build and installed tools:

```sh
scripts/spike-build-desk.sh
python3 -B scripts/spike-floor-check.py
python3 -B scripts/spike-floor-run.py
CARGO_TARGET_DIR="$PWD/target" CARGO_NET_OFFLINE=true cargo test -p kudde-chat
```

Floor tests were written and observed failing before implementation, then passed. The shared chat suite passed: 159 tests, zero failures. Generated inputs, exact stdout and raw results stay ignored under `target/desk-spike/floor-*`. No fallback, deployment pipeline or installation mechanism is implemented by these scripts; decision 7 remains with the owner.

Raw samples, milliseconds (seven calls in each list):

- javascript: first [3.722, 3.523, 3.462, 3.403, 3.328, 3.361, 3.317]; append [0.012, 0.013, 0.015, 0.012, 0.012, 0.012, 0.011]; largest [0.015, 0.005, 0.005, 0.004, 0.004, 0.004, 0.004].
- native: first [36.504, 35.031, 34.305, 36.753, 35.671, 36.021, 34.314]; append [43.597, 40.580, 42.123, 43.355, 42.903, 43.005, 42.486]; largest [1.259, 1.241, 1.000, 1.312, 1.167, 1.135, 1.460].
- qjs view JSON.parse: [1.830, 1.746, 1.663, 1.697, 1.684, 2.058, 1.790].


## The JavaScript reader, measured

Task 001d, measured under QuickJS-ng **0.18.0**. The premise was checked
before editing: clean tree, branch `spike/desktop-chat`, extracted Rust core
and native vehicle present, no JavaScript reader present. Confidence: high.
The parity harness was run before `reader.js` existed and failed to import
it. Claude was checked before starting Codex; Codex was checked before the
conversation-state stage. Each later expansion was observed failing before
its implementation. No change to `kudde-chat`, dependency or decision 7.

`desk/spike/reader.js` is one dependency-free ES module, **27,870 bytes,
435 physical lines**, including blank lines and comments. It uses no wasm.
The counting allocation below assigns common JSON/UTF-8 helpers and Parser
building to stage A; keep-keys belongs to C. It measures source surface,
not effort or complexity: JavaScript statements are considerably denser.
Confidence: high for counts; moderate for the comparison's usefulness.

| Stage | JavaScript physical lines | Rust physical lines mirrored |
|---|---:|---:|
| A: shared building/helpers and Claude Code | 245 | 1,003 |
| B: Codex, including late-command ordering | 51 | 190 |
| C: retained conversation, keys, view and lifecycle | 139 | 610 |
| Total | 435 | 1,803 |

Rust allocation: `lib.rs` lines 1–847 and 1038–1193 for A, 848–1037 for B,
1194–1233 plus all 570 lines of `core.rs` for C. JavaScript allocation:
1–245, 246–296, 297–435. Shared helpers also support later stages.

### Exact comparisons

The harness runs the release native vehicle and this module under `qjs`,
then compares the entire decoded replies: no removed fields, numerical
rounding tolerance, row sorting or key normalization. JSON object member
order is immaterial; array order and strings, including serialized tool
inputs, are exact. Confidence: high for this observed corpus.

| Fixture | Whole view | Selected split reads | Older load |
|---|---|---|---|
| `claude.jsonl` | exact | exact | exact |
| `claude-agents.jsonl` | exact | exact | exact |
| `codex.jsonl` | exact | exact | exact |

Final output: **`parity ok (194 inputs compared; 0 differing)`**.
Complete JSON log literals are lifted automatically from Rust source/tests;
format templates are not executed. Five ordering sequences reconstruct
`codex_order.rs` helpers. Additional comparisons cover partial records,
queue placeholders/removal/pop-all, images, numeric and Unicode map keys,
float tool inputs, offsets and gauges above 2^53, malformed tail output,
UTF-8 fragments, invalid bytes, invalid surrogate escapes, older windows,
skipped records, failed reads, session changes/exits, empty-session guard,
turn grace/recovery, command exemption, lookup clocks, watch state and
leave/return with history dropping. This is a finite differential corpus,
not a proof of equivalence on arbitrary future private formats or every
floating-point value. Confidence: moderate for general behavioral coverage.

Native changes add only spike oracle input/output modes: raw byte fragments
and state actions/planned reads. They exercise the unchanged real core.
There is no new transport, sending, Markdown or screen implementation.

### Timings

Each operation below has three sequential samples; the table reports their
median in milliseconds. Inputs use the unchanged `text` and `raw` generators
from `spike-bench.mjs`: 2,000,000 bytes starting at offset 2,000,000,
6,000-byte append on that retained history, 2,000,000 older bytes, and a
7,864,320-byte incremental read after resetting to 2,000,000 retained bytes.
Input generation/loading and module startup are outside the timer. Timed
work includes tail framing, parsing, retained state/key updates and a fully
owned view snapshot. It excludes a JSON bridge, stdout serialization, I/O,
network and rendering; the JS API needs no wasm request/reply bridge.
`os.now()/1000` was checked against 25 ms of wall-clock advance (24.837 ms).

| Operation | Median ms | Reference | Observation |
|---|---:|---:|---|
| 2 MB first open | 373.149 | 300 ms | above |
| Small update on long history | 5.167 | 50 ms | below |
| Loading older messages | 598.793 | 50 ms interactive reference | above |
| Largest incremental read | 1,692.547 | 50 ms | above |

Raw samples, milliseconds:

- first: [373.149, 317.995, 702.951]
- incremental: [5.167, 4.387, 30.375]
- older: [561.226, 598.793, 8453.824]
- maximum: [1555.593, 1692.547, 3667.730]

The third repetition was much slower, particularly older loading; system
contention was not isolated. Confidence: high for captured CLI samples,
moderate for their repeatability, unknown for app-window performance. These
are the reader's measured costs, not the earlier syntax-only floor. The
300/50 ms gates are not both met in this run. No choice on decision 7 is made.

### Language boundaries

- Integers: `Number` loses integers above 2^53. The JSON reader protects
  integer tokens before conversion, retains large integers as `BigInt`,
  uses BigInt for physical offsets/clocks, and writes exact decimal JSON.
  Uint64 gauges and references include explicit large-value comparisons.
- UTF-8 split inside a character: byte input waits for the whole newline-
  terminated record before decoding; widths 1, 2, 3, 7 and 4096 are compared.
- Invalid UTF-8: a small lossy decoder follows Rust's replacement-sequence
  consumption, while offsets count original bytes. The native byte oracle
  uses `String::from_utf8_lossy` and the same original-byte offsets.
- UTF-16 length differs from byte length: offset accounting uses UTF-8 byte
  lengths (with an ASCII fast path), never string length for Unicode data.
  Rust whitespace trimming is explicit. Lone surrogate JSON escapes are
  rejected, as by serde_json, instead of accepted as JavaScript strings.
- Maps: insertion order differs from Rust's sorted JSON object keys. Tool
  inputs and FileChange paths sort by Unicode scalar order, including
  numeric-looking keys and supplementary characters. State maps use `Map`;
  output ordering is driven by rows and held-key order.
- Floats: JavaScript forgets `1.0` versus integer `1`, negative zero and
  serde_json's exponent formatting. Float tokens retain a wrapper and
  format those distinctions explicitly. Representative decimal, integral
  and negative-zero mechanisms are present; exhaustive float equivalence
  is not established by this corpus.

Confidence: high for the named passing boundary cases; moderate that these
mechanisms cover all real-session combinations. Further format changes
need new comparisons, not silent relaxations of parity.

### Keeping the readers in step

Run the harness as a CI differential check whenever either reader, the
fixtures or shared Rust tests change. It automatically picks up complete
literal log text; new helper-built scenarios need explicit reconstructions
or a future native test-vector exporter. Maintainers must add fixtures for
new private-format shapes before changing either implementation. Confidence:
high for this maintenance requirement; moderate for the proposed coverage.
No CI settings were changed in this task.

Final local costs: build 5.856 s, parity 0.518 s, timings 19.398 s,
shared suite 5.123 s, spike suite 2.337 s. These are warm-cache elapsed
observations, not cold CI estimates. The native adapter adds 95 physical
lines. All 159 shared tests and seven spike boundary tests passed.
Confidence: high for observed counts/costs, unknown for cold CI cost.

Reproduce offline from the repository root:

```sh
mkdir -p target/desk-spike/reader/tmp
export TMPDIR="$PWD/target/desk-spike/reader/tmp"
export CARGO_TARGET_DIR="$PWD/target" CARGO_NET_OFFLINE=true
scripts/spike-build-desk.sh
python3 -B scripts/spike-reader-parity.py
qjs scripts/spike-reader-bench.mjs
cargo test -p kudde-chat
cargo test -p kudde-desk-spike
```

Generated inputs, differing replies if any, raw samples and check logs stay
ignored in `target/desk-spike/reader/`. No commits, staging, installations,
network git calls, live infrastructure or phone work occurred. Execution
exception: an accidental empty temporary file outside the worktree was
created and removed; an initial shared-suite run used the default temporary
directory before final checks constrained `TMPDIR` to the worktree. The
worktree-only-write instruction was therefore breached in that early work.
