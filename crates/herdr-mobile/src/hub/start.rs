//! The "New" sheet's hub half (start-agents design D1, D2, D5, D7): what a
//! connected machine offers (its places and installed agents), the check that
//! finds the agents, and the start itself with the reasons it can fail.

use std::sync::Arc;
use std::time::Duration;

use herdr_proto::attention::folder_name;
use herdr_proto::layout::{self, Space};
use herdr_proto::rpc::{CallError, Client};
use herdr_proto::start::{self as proto, AGENTS, CHECK_TIMEOUT_S, Folder};
use serde_json::json;

use super::{HubCore, HubError};

/// The desk's layout document.
pub(crate) const LAYOUT_DOC: &str = "herdr-app";

/// What the sheet offers on a connected machine.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct StartView {
    /// Spaces (`from_spaces`), else folders: home first, then where the
    /// machine's live terminals are.
    pub places: Vec<StartPlace>,
    pub from_spaces: bool,
    pub agents: AgentsView,
}

/// Where a new terminal goes; handed back to [`HubCore::start_terminal`].
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct StartPlace {
    /// The space's name, else its folder's (`~` for home).
    pub title: String,
    /// The absolute folder it starts in; None when it needs a home folder
    /// that is not known yet (it starts in herdr-core's own folder).
    pub folder: Option<String>,
    /// The space it joins; None: a new space (a machine without spaces).
    pub space_id: Option<String>,
    pub note: PlaceNote,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum PlaceNote {
    None,
    /// The space's folder is not a full path: home instead.
    HomeInstead,
    /// Home is not known: herdr-core's own folder.
    HomeUnknown,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum AgentsView {
    /// The check has not answered yet: the row keeps its place.
    Checking,
    Ready {
        agents: Vec<StartAgent>,
    },
    /// The check failed: "Terminal" only; it runs again when the sheet opens.
    Unavailable,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct StartAgent {
    /// Icon id, as `AgentRow.program`.
    pub program: String,
    pub name: String,
    /// What a tap starts; passed back to [`HubCore::start_terminal`].
    pub word: String,
}

/// A machine's check, kept for the app's life: a success is not asked again.
#[derive(Clone, Debug, Default)]
pub(crate) struct CheckState {
    pub running: bool,
    /// From the last check that printed it, also a failed one.
    pub home: Option<String>,
    /// Found agents in table order; None until a check succeeds.
    pub found: Option<Vec<String>>,
    /// The last check ended without an answer.
    pub failed: bool,
}

/// Per connection: what the layout and the live terminals say.
#[derive(Clone, Debug, Default)]
pub(crate) struct Places {
    /// None until the layout was read.
    pub spaces: Option<Vec<Space>>,
    /// Live terminals' folders, each once (no home).
    pub folders: Vec<String>,
}

pub(crate) fn view(places: &Places, check: Option<&CheckState>) -> StartView {
    let home = check.and_then(|c| c.home.as_deref());
    let spaces = places.spaces.as_deref().unwrap_or(&[]);
    let from_spaces = !spaces.is_empty();
    let places = if from_spaces {
        spaces
            .iter()
            .map(|s| {
                let (folder, note) = match proto::resolve_folder(&s.folder, home) {
                    Folder::Path(p) => (Some(p), PlaceNote::None),
                    Folder::HomeInstead(p) => (Some(p), PlaceNote::HomeInstead),
                    Folder::HomeUnknown => (None, PlaceNote::HomeUnknown),
                };
                StartPlace {
                    title: proto::space_title(s, home),
                    folder,
                    space_id: Some(s.id.clone()),
                    note,
                }
            })
            .collect()
    } else {
        let mut list: Vec<String> = home.map(str::to_owned).into_iter().collect();
        list.extend(
            places
                .folders
                .iter()
                .filter(|f| Some(f.as_str()) != home)
                .cloned(),
        );
        if list.is_empty() {
            vec![StartPlace {
                title: "~".into(),
                folder: None,
                space_id: None,
                note: PlaceNote::HomeUnknown,
            }]
        } else {
            list.into_iter()
                .map(|f| StartPlace {
                    title: folder_name(&f, home),
                    folder: Some(f),
                    space_id: None,
                    note: PlaceNote::None,
                })
                .collect()
        }
    };
    let agents = match check {
        Some(CheckState {
            found: Some(found), ..
        }) => AgentsView::Ready {
            agents: AGENTS
                .iter()
                .filter(|a| found.iter().any(|w| w == a.word))
                .map(|a| StartAgent {
                    program: a.program.into(),
                    name: a.name.into(),
                    word: a.word.into(),
                })
                .collect(),
        },
        Some(CheckState {
            failed: true,
            running: false,
            ..
        }) => AgentsView::Unavailable,
        _ => AgentsView::Checking,
    };
    StartView {
        places,
        from_spaces,
        agents,
    }
}

fn words() -> Vec<&'static str> {
    AGENTS.iter().map(|a| a.word).collect()
}

/// Reads the desk's layout for the sheet's spaces.
pub(crate) async fn read_layout(hub: Arc<HubCore>, name: String, epoch: u64, client: Client) {
    let spaces = match client.call("doc.get", json!({"name": LAYOUT_DOC})).await {
        Ok(got) => layout::spaces(&got["value"]),
        Err(CallError::Lost) => return,
        Err(e) => {
            hub.note(&format!("{name}: reading {LAYOUT_DOC}: {e}"));
            Vec::new()
        }
    };
    hub.with(|_, st| {
        if let Some(live) = st.live.get_mut(&name).filter(|l| l.epoch == epoch) {
            live.places.spaces = Some(spaces);
        }
    });
}

/// Asks the machine's interactive shell which agents it has, unless a check
/// already answered or one is running.
pub(crate) fn check_if_needed(hub: &Arc<HubCore>, name: &str, epoch: u64, client: Client) {
    let go = hub.with(|_, st| {
        let c = st.checks.entry(name.to_owned()).or_default();
        if c.found.is_some() || c.running {
            return false;
        }
        c.running = true;
        true
    });
    if go {
        hub.runtime
            .spawn(run_check(hub.clone(), name.to_owned(), epoch, client));
    }
}

async fn run_check(hub: Arc<HubCore>, name: String, _epoch: u64, client: Client) {
    let words = words();
    // The core ends the check at CHECK_TIMEOUT_S; this only covers a lost answer.
    let wait = Duration::from_secs(CHECK_TIMEOUT_S + 10);
    let got = client
        .call_timeout("machine.exec", proto::check_params(&words), wait)
        .await;
    let check = match &got {
        Ok(result) => proto::parse_exec_result(result, &words),
        Err(e) => {
            hub.note(&format!("{name}: agents check: {e}"));
            proto::Check {
                home: None,
                found: None,
            }
        }
    };
    hub.with(|_, st| {
        let c = st.checks.entry(name.clone()).or_default();
        c.running = false;
        c.failed = check.found.is_none();
        if check.home.is_some() {
            c.home = check.home;
        }
        if check.found.is_some() {
            c.found = check.found;
        }
    });
}

impl HubCore {
    /// The sheet opened on `machine`: a check that failed runs again.
    pub(crate) fn start_sheet_opened(self: &Arc<Self>, machine: &str) {
        if let Some(conn) = self.live_conn(machine) {
            check_if_needed(self, machine, conn.epoch, conn.client);
        }
    }

    /// Starts a terminal at `place`, running the machine's shell, or the
    /// table agent `agent` (one the check found) in the person's interactive
    /// shell. Returns the new terminal's address, for the app to open.
    pub(crate) async fn start_terminal(
        self: &Arc<Self>,
        machine: &str,
        place: StartPlace,
        agent: Option<String>,
    ) -> Result<String, HubError> {
        let (installed, listed) = {
            let st = self.state.lock().unwrap();
            let found = st.checks.get(machine).and_then(|c| c.found.clone());
            (found, st.list.get(machine).is_some())
        };
        if !listed {
            return Err(HubError::NotFound {
                name: machine.to_owned(),
            });
        }
        let invalid = |detail: String| Err(HubError::Invalid { detail });
        if let Some(word) = &agent {
            let known = AGENTS.iter().find(|a| a.word == word);
            let present = installed.as_ref().is_some_and(|f| f.contains(word));
            match known {
                Some(_) if present => {}
                Some(a) => return invalid(format!("{} is not installed on {machine}", a.name)),
                None => return invalid(format!("{word} is not an agent the app starts")),
            }
        }
        let Some(conn) = self.live_conn(machine) else {
            return invalid(format!("{machine} is not connected"));
        };
        let claimed = self.with(|_, st| match st.live.get_mut(machine) {
            Some(live) if !live.starting => {
                live.starting = true;
                true
            }
            _ => false,
        });
        if !claimed {
            return invalid("A start is already running".into());
        }
        let space = place.space_id.clone().unwrap_or_else(proto::fresh_space_id);
        let params = proto::run_params(place.folder.as_deref(), &space, agent.as_deref());
        let got = conn.client.call("terminal.run", params).await;
        self.with(|_, st| {
            if let Some(live) = st.live.get_mut(machine) {
                live.starting = false;
            }
        });
        match got {
            Ok(rec) => {
                rec["address"]
                    .as_str()
                    .map(str::to_owned)
                    .ok_or_else(|| HubError::Invalid {
                        detail: format!("{machine} started it but did not say where"),
                    })
            }
            Err(e) => invalid(refusal(machine, &place, &e)),
        }
    }
}

/// What the sheet says when a start fails (design D7).
fn refusal(machine: &str, place: &StartPlace, e: &CallError) -> String {
    match e.kind() {
        Some("spawn_failed") => {
            let folder = place.folder.as_deref().unwrap_or("herdr-core's folder");
            match e {
                CallError::Rpc { message, .. }
                    if !message.is_empty() && message != "spawn_failed" =>
                {
                    format!("Could not start in {folder}: {message}")
                }
                _ => format!("Could not start in {folder}"),
            }
        }
        Some("unavailable" | "machine_unknown") => format!("{machine} is not reachable now"),
        _ => match e {
            CallError::Lost => format!("{machine} is not connected"),
            CallError::OutcomeUnknown => {
                format!(
                    "No answer from {machine}: look for the new terminal before starting another"
                )
            }
            CallError::Rpc { .. } => e.to_string(),
        },
    }
}
