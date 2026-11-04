//! When the watcher alerts (alerts spec, "The watcher follows the shared
//! attention model" and "Alerts are neither lost nor repeated across restarts").
//!
//! Pure: fed the core's terminal list and the clock, it returns what to push.
//! Attention comes from `herdr_proto::attention`, as on the phone; the watcher
//! never looks at a terminal, so a finish stays unseen until the agent works
//! again. Every alert is keyed by `(state, turn)`: the turn counter rises each
//! time an agent leaves `working`, so the same key is never alerted twice, and
//! the record of keys survives a restart.

use std::collections::{BTreeMap, BTreeSet};

use herdr_proto::attention::{Attention, Tracker};
use serde_json::{Map, Value, json};

/// The tracker's name for the one machine a watcher sees.
const HERE: &str = "here";

/// What an alert reports.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Word {
    Blocked,
    Error,
    Done,
}

impl Word {
    pub fn as_str(self) -> &'static str {
        match self {
            Word::Blocked => "blocked",
            Word::Error => "error",
            Word::Done => "done",
        }
    }

    fn parse(s: &str) -> Option<Word> {
        match s {
            "blocked" => Some(Word::Blocked),
            "error" => Some(Word::Error),
            "done" => Some(Word::Done),
            _ => None,
        }
    }

    fn needs_you(self) -> bool {
        matches!(self, Word::Blocked | Word::Error)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Action {
    Alert {
        terminal: String,
        name: String,
        state: Word,
        /// The program's request kind ("permission", "question"), blocked only.
        kind: Option<String>,
    },
    /// An alerted terminal left needs-you: the phone removes its notification.
    Cleared { terminal: String },
}

/// Which terminals sit in a pane of the `herdr-app` layout.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Layout {
    Placed(BTreeSet<String>),
    /// No usable layout: every agent may alert (the note says why).
    Unreadable(String),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Alerted {
    word: Word,
    turn: u64,
    cleared: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Pending {
    word: Word,
    turn: u64,
    due_ms: u64,
}

pub struct Rules {
    grace_ms: u64,
    tracker: Tracker,
    layout: Layout,
    terminals: Vec<Value>,
    record: BTreeMap<String, Alerted>,
    pending: BTreeMap<String, Pending>,
}

impl Rules {
    /// `record` is what [`Rules::record`] saved before a restart (or null).
    pub fn new(grace_ms: u64, record: &Value) -> Rules {
        let record = record["terminals"]
            .as_object()
            .into_iter()
            .flatten()
            .filter_map(|(address, e)| {
                let alerted = Alerted {
                    word: Word::parse(e["state"].as_str()?)?,
                    turn: e["turn"].as_u64()?,
                    cleared: e["cleared"] == true,
                };
                Some((address.clone(), alerted))
            })
            .collect();
        Rules {
            grace_ms,
            tracker: Tracker::default(),
            layout: Layout::Unreadable(crate::layout::NO_DOCUMENT.into()),
            terminals: Vec::new(),
            record,
            pending: BTreeMap::new(),
        }
    }

    pub fn set_layout(&mut self, layout: Layout) {
        self.layout = layout;
    }

    /// The core's full terminal list (from `EventLines`), after a snapshot,
    /// an event or a `missed`. Clears go out at once; alerts wait for [`Rules::due`].
    pub fn observe(&mut self, terminals: &[Value], now_ms: u64) -> Vec<Action> {
        self.tracker.observe(HERE, terminals, now_ms);
        self.terminals = terminals.to_vec();
        let now = self.current();
        let mut actions = Vec::new();

        // Alerted terminals that left needs-you, or went away.
        self.record.retain(|address, a| {
            let here = now.get(address);
            let still = here.is_some_and(|(w, _)| w.is_some_and(Word::needs_you));
            if a.word.needs_you() && !a.cleared && !still {
                a.cleared = true;
                actions.push(Action::Cleared {
                    terminal: address.clone(),
                });
            }
            here.is_some()
        });

        // A state not alerted yet starts its delay; one that moved on cancels it.
        self.pending.retain(|address, p| {
            now.get(address)
                .is_some_and(|(w, turn)| *w == Some(p.word) && *turn == p.turn)
        });
        for (address, (word, turn)) in &now {
            let Some(word) = *word else { continue };
            let alerted = self
                .record
                .get(address)
                .is_some_and(|a| a.word == word && a.turn == *turn);
            if !alerted && !self.pending.contains_key(address) {
                self.pending.insert(
                    address.clone(),
                    Pending {
                        word,
                        turn: *turn,
                        due_ms: now_ms + self.grace_ms,
                    },
                );
            }
        }
        actions
    }

    /// Alerts whose grace delay has passed with the state unchanged.
    pub fn due(&mut self, now_ms: u64) -> Vec<Action> {
        let ready: Vec<(String, Pending)> = self
            .pending
            .iter()
            .filter(|(_, p)| p.due_ms <= now_ms)
            .map(|(a, p)| (a.clone(), *p))
            .collect();
        let rows: BTreeMap<String, String> = self
            .tracker
            .rows()
            .into_iter()
            .map(|r| (r.address, r.name))
            .collect();
        let mut actions = Vec::new();
        for (address, p) in ready {
            self.pending.remove(&address);
            if !self.placed(&address) {
                continue;
            }
            let Some(name) = rows.get(&address) else {
                continue;
            };
            let kind = self.kind(&address, p.word);
            self.record.insert(
                address.clone(),
                Alerted {
                    word: p.word,
                    turn: p.turn,
                    cleared: false,
                },
            );
            actions.push(Action::Alert {
                terminal: address,
                name: name.clone(),
                state: p.word,
                kind,
            });
        }
        actions
    }

    /// The needs-you alerts sent and not cleared, as they would go out now: for
    /// a phone that registers while they are open (it missed them).
    pub fn open_alerts(&self) -> Vec<Action> {
        let rows: BTreeMap<String, String> = self
            .tracker
            .rows()
            .into_iter()
            .map(|r| (r.address, r.name))
            .collect();
        self.record
            .iter()
            .filter(|(_, a)| a.word.needs_you() && !a.cleared)
            .filter_map(|(address, a)| {
                let name = rows.get(address)?;
                Some(Action::Alert {
                    terminal: address.clone(),
                    name: name.clone(),
                    state: a.word,
                    kind: self.kind(address, a.word),
                })
            })
            .collect()
    }

    fn kind(&self, address: &str, word: Word) -> Option<String> {
        (word == Word::Blocked)
            .then(|| self.terminal(address))
            .flatten()
            .and_then(|t| t.pointer("/status/kind"))
            .and_then(Value::as_str)
            .filter(|k| !k.is_empty())
            .map(str::to_owned)
    }

    /// When [`Rules::due`] next has something to say.
    pub fn next_due(&self) -> Option<u64> {
        self.pending.values().map(|p| p.due_ms).min()
    }

    /// What to persist: `{"terminals": {address: {state, turn, cleared}}}`.
    pub fn record(&self) -> Value {
        let terminals: Map<String, Value> = self
            .record
            .iter()
            .map(|(address, a)| {
                let entry = json!({"state": a.word.as_str(), "turn": a.turn, "cleared": a.cleared});
                (address.clone(), entry)
            })
            .collect();
        json!({ "terminals": terminals })
    }

    /// Every agent the tracker lists: what it would alert as, and its turn.
    fn current(&self) -> BTreeMap<String, (Option<Word>, u64)> {
        self.tracker
            .rows()
            .into_iter()
            .map(|r| {
                let word = match r.attention {
                    Attention::NeedsYou => Some(Word::Blocked),
                    Attention::Error => Some(Word::Error),
                    Attention::Done => Some(Word::Done),
                    Attention::Working | Attention::Idle => None,
                };
                let turn = self
                    .terminal(&r.address)
                    .and_then(|t| t["turn"].as_u64())
                    .unwrap_or(0);
                (r.address, (word, turn))
            })
            .collect()
    }

    fn terminal(&self, address: &str) -> Option<&Value> {
        self.terminals.iter().find(|t| t["address"] == address)
    }

    fn placed(&self, address: &str) -> bool {
        match &self.layout {
            Layout::Placed(set) => set.contains(address),
            Layout::Unreadable(_) => true,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const GRACE: u64 = 30_000;

    fn agent(address: &str, state: &str, turn: u64) -> Value {
        json!({
            "address": address, "state": "live", "reported": true, "parent": null,
            "turn": turn, "name": "",
            "status": {"state": state, "kind": "permission", "msg": "Run npm test?", "app": "claude"},
        })
    }

    fn rules() -> Rules {
        Rules::new(GRACE, &Value::Null)
    }

    fn alert(terminal: &str, state: Word) -> Action {
        Action::Alert {
            terminal: terminal.into(),
            name: "Claude Code".into(),
            state,
            kind: (state == Word::Blocked).then(|| "permission".to_owned()),
        }
    }

    fn cleared(terminal: &str) -> Action {
        Action::Cleared {
            terminal: terminal.into(),
        }
    }

    // Codex review, M3: a phone that registers after an agent blocked gets
    // the alerts still open, so the watcher must be able to list them.
    #[test]
    fn open_alerts_are_the_alerted_needs_you_ones_not_cleared() {
        let mut r = rules();
        assert_eq!(r.open_alerts(), vec![]);
        r.observe(&[agent("t1", "blocked", 0), agent("t2", "working", 0)], 0);
        assert_eq!(r.open_alerts(), vec![], "not alerted yet");
        r.due(GRACE);
        assert_eq!(r.open_alerts(), vec![alert("t1", Word::Blocked)]);
        // A finish is a moment, not an open alert.
        r.observe(&[agent("t1", "blocked", 0), agent("t2", "idle", 0)], GRACE);
        r.due(2 * GRACE);
        assert_eq!(r.open_alerts(), vec![alert("t1", Word::Blocked)]);
        // Answered: nothing open.
        r.observe(
            &[agent("t1", "working", 1), agent("t2", "idle", 0)],
            3 * GRACE,
        );
        assert_eq!(r.open_alerts(), vec![]);
    }

    #[test]
    fn blocked_past_the_grace_delay_alerts_exactly_once() {
        let mut r = rules();
        assert_eq!(r.observe(&[agent("t1", "working", 0)], 0), vec![]);
        assert_eq!(r.observe(&[agent("t1", "blocked", 0)], 1_000), vec![]);
        assert_eq!(r.next_due(), Some(1_000 + GRACE));
        assert_eq!(r.due(GRACE), vec![]);
        assert_eq!(r.due(1_000 + GRACE), vec![alert("t1", Word::Blocked)]);
        assert_eq!(r.due(2 * GRACE), vec![]);
        assert_eq!(r.next_due(), None);
        // Later events about the same blocked turn change nothing.
        assert_eq!(r.observe(&[agent("t1", "blocked", 0)], 50_000), vec![]);
        assert_eq!(r.due(200_000), vec![]);
    }

    #[test]
    fn answered_within_the_delay_sends_nothing() {
        let mut r = rules();
        r.observe(&[agent("t1", "working", 0)], 0);
        r.observe(&[agent("t1", "blocked", 0)], 1_000);
        assert_eq!(r.observe(&[agent("t1", "working", 0)], 11_000), vec![]);
        assert_eq!(r.next_due(), None);
        assert_eq!(r.due(100_000), vec![]);
    }

    #[test]
    fn leaving_needs_you_clears_the_alert_then_the_finish_alerts() {
        let mut r = rules();
        r.observe(&[agent("t1", "working", 0)], 0);
        r.observe(&[agent("t1", "blocked", 0)], 0);
        r.due(GRACE);
        // Answered at the desk; the agent finishes without working again.
        assert_eq!(
            r.observe(&[agent("t1", "idle", 0)], 40_000),
            vec![cleared("t1")]
        );
        assert_eq!(r.observe(&[agent("t1", "idle", 0)], 41_000), vec![]);
        assert_eq!(r.due(40_000 + GRACE), vec![alert("t1", Word::Done)]);
    }

    #[test]
    fn answered_and_working_again_clears_once() {
        let mut r = rules();
        r.observe(&[agent("t1", "working", 0)], 0);
        r.observe(&[agent("t1", "blocked", 0)], 0);
        r.due(GRACE);
        assert_eq!(
            r.observe(&[agent("t1", "working", 0)], 40_000),
            vec![cleared("t1")]
        );
        assert_eq!(r.observe(&[agent("t1", "working", 1)], 41_000), vec![]);
        // Blocked again on the next turn: a new alert.
        r.observe(&[agent("t1", "blocked", 1)], 50_000);
        assert_eq!(r.due(50_000 + GRACE), vec![alert("t1", Word::Blocked)]);
    }

    #[test]
    fn a_finish_alerts_after_the_delay_and_working_again_cancels_it() {
        let mut r = rules();
        r.observe(&[agent("t1", "working", 0)], 0);
        r.observe(&[agent("t1", "idle", 1)], 1_000);
        assert_eq!(r.due(1_000 + GRACE), vec![alert("t1", Word::Done)]);

        r.observe(&[agent("t1", "working", 1)], 100_000);
        r.observe(&[agent("t1", "idle", 2)], 101_000);
        // Started working again within the delay: no second finish alert.
        r.observe(&[agent("t1", "working", 2)], 110_000);
        assert_eq!(r.due(200_000), vec![]);
    }

    #[test]
    fn an_error_needs_you_too() {
        let mut r = rules();
        r.observe(&[agent("t1", "working", 0)], 0);
        r.observe(&[agent("t1", "error", 0)], 0);
        assert_eq!(r.due(GRACE), vec![alert("t1", Word::Error)]);
        assert_eq!(
            r.observe(&[agent("t1", "working", 0)], 60_000),
            vec![cleared("t1")]
        );
    }

    #[test]
    fn a_state_that_moves_on_restarts_the_delay() {
        let mut r = rules();
        r.observe(&[agent("t1", "working", 0)], 0);
        r.observe(&[agent("t1", "blocked", 0)], 0);
        r.observe(&[agent("t1", "error", 0)], 20_000);
        assert_eq!(r.due(GRACE), vec![]);
        assert_eq!(r.due(20_000 + GRACE), vec![alert("t1", Word::Error)]);
    }

    #[test]
    fn the_first_snapshot_counts_finished_agents_as_seen() {
        let mut r = rules();
        assert_eq!(r.observe(&[agent("t1", "idle", 5)], 0), vec![]);
        assert_eq!(r.due(GRACE), vec![]);
    }

    #[test]
    fn sub_agents_and_unplaced_terminals_stay_quiet() {
        let mut sub = agent("t2", "blocked", 0);
        sub["parent"] = json!("t1");
        let mut r = rules();
        r.set_layout(Layout::Placed(BTreeSet::from(["t1".to_owned()])));
        r.observe(
            &[
                agent("t1", "working", 0),
                sub.clone(),
                agent("t3", "working", 0),
            ],
            0,
        );
        r.observe(
            &[agent("t1", "blocked", 0), sub, agent("t3", "blocked", 0)],
            0,
        );
        assert_eq!(r.due(GRACE), vec![alert("t1", Word::Blocked)]);
    }

    #[test]
    fn an_unreadable_layout_lets_every_agent_alert() {
        let mut r = rules();
        r.set_layout(Layout::Unreadable("layout unreadable".into()));
        r.observe(&[agent("t3", "working", 0)], 0);
        r.observe(&[agent("t3", "blocked", 0)], 0);
        assert_eq!(r.due(GRACE), vec![alert("t3", Word::Blocked)]);
    }

    #[test]
    fn a_terminal_unplaced_before_its_alert_is_due_stays_quiet() {
        let mut r = rules();
        r.set_layout(Layout::Placed(BTreeSet::from(["t1".to_owned()])));
        r.observe(&[agent("t1", "working", 0)], 0);
        r.observe(&[agent("t1", "blocked", 0)], 0);
        r.set_layout(Layout::Placed(BTreeSet::new()));
        assert_eq!(r.due(GRACE), vec![]);
    }

    #[test]
    fn a_restart_alerts_only_what_was_never_alerted() {
        let mut before = rules();
        before.observe(&[agent("t1", "working", 0), agent("t3", "working", 0)], 0);
        before.observe(&[agent("t1", "blocked", 0), agent("t3", "blocked", 2)], 0);
        before.due(GRACE);
        let saved = before.record();
        // Survives a round trip through the state file.
        let saved: Value = serde_json::from_str(&saved.to_string()).unwrap();

        // During the restart t2 became blocked and t3 was answered.
        let mut after = Rules::new(GRACE, &saved);
        let now = [
            agent("t1", "blocked", 0),
            agent("t2", "blocked", 0),
            agent("t3", "working", 2),
        ];
        assert_eq!(after.observe(&now, 100_000), vec![cleared("t3")]);
        assert_eq!(after.due(100_000 + GRACE), vec![alert("t2", Word::Blocked)]);
    }

    #[test]
    fn a_missed_snapshot_is_diffed_against_the_record() {
        let mut r = rules();
        r.observe(&[agent("t1", "working", 0), agent("t2", "working", 0)], 0);
        r.observe(&[agent("t1", "blocked", 0), agent("t2", "working", 0)], 0);
        r.due(GRACE);
        // Events were missed: t1 was answered, worked, and blocked again on a
        // new turn; t2 finished a turn.
        let fresh = [agent("t1", "blocked", 1), agent("t2", "idle", 1)];
        assert_eq!(r.observe(&fresh, 100_000), vec![]);
        let mut due = r.due(100_000 + GRACE);
        due.sort_by_key(|a| format!("{a:?}"));
        assert_eq!(
            due,
            vec![alert("t1", Word::Blocked), alert("t2", Word::Done)]
        );
    }

    #[test]
    fn a_terminal_that_goes_away_clears_its_alert_and_its_record() {
        let mut r = rules();
        r.observe(&[agent("t1", "working", 0)], 0);
        r.observe(&[agent("t1", "blocked", 0)], 0);
        r.due(GRACE);
        assert_eq!(r.observe(&[], 40_000), vec![cleared("t1")]);
        assert_eq!(r.record(), json!({"terminals": {}}));
    }

    #[test]
    fn the_record_keeps_state_turn_and_whether_it_was_cleared() {
        let mut r = rules();
        r.observe(&[agent("t1", "working", 0)], 0);
        r.observe(&[agent("t1", "blocked", 3)], 0);
        r.due(GRACE);
        assert_eq!(
            r.record(),
            json!({"terminals": {"t1": {"state": "blocked", "turn": 3, "cleared": false}}})
        );
        // A garbled record is ignored entry by entry, not fatal.
        let r = Rules::new(
            GRACE,
            &json!({"terminals": {"t1": {"state": "odd"}, "t2": 4}}),
        );
        assert_eq!(r.record(), json!({"terminals": {}}));
    }
}
