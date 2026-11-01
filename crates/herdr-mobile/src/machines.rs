//! The app's own machine list (machine-connection spec, "Machines are an
//! app-local list"): kept as JSON in app storage, exported and imported
//! without host keys. The phone key never appears here; it lives in the
//! Android Keystore.

use serde::{Deserialize, Serialize};
use std::path::Path;

/// The file format; a newer one is refused rather than half read.
const FORMAT: u32 = 1;
/// The export's marker, so an unrelated JSON file is not taken for a list.
const EXPORT_KIND: &str = "herdr-android machines";
const NAME_MAX: usize = 64;
/// A DNS name's limit; Tailscale names and addresses fit well within it.
const HOST_MAX: usize = 253;
const USER_MAX: usize = 64;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Machine {
    /// What the app shows; unique, and how rows and seen turns refer to it.
    pub name: String,
    pub host: String,
    pub port: u16,
    pub user: String,
    /// The confirmed host key (`SHA256:...`); none until the person confirms one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub host_key: Option<String>,
    /// The last moment the app was connected, ms since the epoch.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_connected_ms: Option<u64>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ListError {
    /// A field the person typed is not usable; the message says which and why.
    Invalid(String),
    /// A machine with this name is already listed.
    Duplicate(String),
    NotFound(String),
    /// The file or import is not a machine list this app reads.
    Unreadable(String),
}

impl std::fmt::Display for ListError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ListError::Invalid(why) => f.write_str(why),
            ListError::Duplicate(name) => write!(f, "a machine named \"{name}\" is already listed"),
            ListError::NotFound(name) => write!(f, "no machine named \"{name}\""),
            ListError::Unreadable(why) => write!(f, "not a machine list: {why}"),
        }
    }
}

impl std::error::Error for ListError {}

#[derive(Default, Serialize, Deserialize)]
struct Stored {
    version: u32,
    machines: Vec<Machine>,
}

/// One entry of an export: where a machine is, nothing it trusts.
#[derive(Serialize, Deserialize)]
struct Exported {
    name: String,
    host: String,
    port: u16,
    user: String,
}

#[derive(Serialize, Deserialize)]
struct Export {
    kind: String,
    version: u32,
    machines: Vec<Exported>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MachineList {
    machines: Vec<Machine>,
}

fn printable(s: &str) -> bool {
    !s.chars().any(char::is_control)
}

/// Checks what the person typed for a machine.
pub fn validate(name: &str, host: &str, port: u16, user: &str) -> Result<(), ListError> {
    let invalid = |why: &str| Err(ListError::Invalid(why.to_owned()));
    if name.trim().is_empty() || name.chars().count() > NAME_MAX || !printable(name) {
        return invalid("the name must be 1 to 64 characters on one line");
    }
    if name != name.trim() {
        return invalid("the name must not start or end with a space");
    }
    if host.is_empty()
        || host.len() > HOST_MAX
        || host.chars().any(|c| c.is_whitespace() || c.is_control())
        || host.starts_with('-')
    {
        return invalid("the host must be a name or address without spaces");
    }
    if port == 0 {
        return invalid("the port must be 1 to 65535");
    }
    if user.is_empty()
        || user.len() > USER_MAX
        || user
            .chars()
            .any(|c| c.is_whitespace() || c.is_control() || c == '@')
        || user.starts_with('-')
    {
        return invalid("the user must be a login name without spaces or @");
    }
    Ok(())
}

impl MachineList {
    /// Reads the list; a missing file is an empty list.
    pub fn load(path: &Path) -> Result<MachineList, ListError> {
        let text = match std::fs::read_to_string(path) {
            Ok(text) => text,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Self::default()),
            Err(e) => return Err(ListError::Unreadable(e.to_string())),
        };
        let stored: Stored =
            serde_json::from_str(&text).map_err(|e| ListError::Unreadable(e.to_string()))?;
        if stored.version != FORMAT {
            return Err(ListError::Unreadable(format!(
                "format {} (this app reads {FORMAT})",
                stored.version
            )));
        }
        let mut list = MachineList::default();
        for m in stored.machines {
            validate(&m.name, &m.host, m.port, &m.user)?;
            if list.get(&m.name).is_some() {
                return Err(ListError::Duplicate(m.name));
            }
            list.machines.push(m);
        }
        Ok(list)
    }

    /// Writes the list so a crash leaves either the old or the new file.
    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        use std::io::Write;
        let stored = Stored {
            version: FORMAT,
            machines: self.machines.clone(),
        };
        let text = serde_json::to_vec_pretty(&stored).map_err(std::io::Error::other)?;
        let tmp = path.with_extension("tmp");
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(&text)?;
        f.sync_all()?;
        drop(f);
        std::fs::rename(&tmp, path)
    }

    pub fn machines(&self) -> &[Machine] {
        &self.machines
    }

    pub fn get(&self, name: &str) -> Option<&Machine> {
        self.machines.iter().find(|m| m.name == name)
    }

    pub fn get_mut(&mut self, name: &str) -> Option<&mut Machine> {
        self.machines.iter_mut().find(|m| m.name == name)
    }

    pub fn add(&mut self, name: &str, host: &str, port: u16, user: &str) -> Result<(), ListError> {
        validate(name, host, port, user)?;
        if self.get(name).is_some() {
            return Err(ListError::Duplicate(name.to_owned()));
        }
        self.machines.push(Machine {
            name: name.to_owned(),
            host: host.to_owned(),
            port,
            user: user.to_owned(),
            host_key: None,
            last_connected_ms: None,
        });
        Ok(())
    }

    /// Changes where a machine is. A new host or port drops the confirmed
    /// host key: it belonged to the old address.
    pub fn update(
        &mut self,
        name: &str,
        host: &str,
        port: u16,
        user: &str,
    ) -> Result<(), ListError> {
        validate(name, host, port, user)?;
        let m = self
            .get_mut(name)
            .ok_or_else(|| ListError::NotFound(name.to_owned()))?;
        if m.host != host || m.port != port {
            m.host_key = None;
        }
        m.host = host.to_owned();
        m.port = port;
        m.user = user.to_owned();
        Ok(())
    }

    pub fn remove(&mut self, name: &str) -> Result<Machine, ListError> {
        let at = self
            .machines
            .iter()
            .position(|m| m.name == name)
            .ok_or_else(|| ListError::NotFound(name.to_owned()))?;
        Ok(self.machines.remove(at))
    }

    /// The list to save elsewhere: names and addresses, no host keys.
    pub fn export(&self) -> String {
        let export = Export {
            kind: EXPORT_KIND.to_owned(),
            version: FORMAT,
            machines: self
                .machines
                .iter()
                .map(|m| Exported {
                    name: m.name.clone(),
                    host: m.host.clone(),
                    port: m.port,
                    user: m.user.clone(),
                })
                .collect(),
        };
        serde_json::to_string_pretty(&export).expect("an export always serialises")
    }

    /// Adds the exported machines not listed yet, each without a host key so
    /// it asks for setup; machines already listed by name are left as they
    /// are. Nothing is added unless the whole import is valid. Returns how
    /// many were added.
    pub fn import(&mut self, text: &str) -> Result<usize, ListError> {
        let export: Export =
            serde_json::from_str(text).map_err(|e| ListError::Unreadable(e.to_string()))?;
        if export.kind != EXPORT_KIND {
            return Err(ListError::Unreadable("not a herdr-android export".into()));
        }
        if export.version != FORMAT {
            return Err(ListError::Unreadable(format!(
                "format {} (this app reads {FORMAT})",
                export.version
            )));
        }
        let mut next = self.clone();
        let mut added = 0;
        for m in &export.machines {
            validate(&m.name, &m.host, m.port, &m.user)?;
            if next.get(&m.name).is_some() {
                // Listed here already, or twice in the import: keep the first.
                continue;
            }
            next.add(&m.name, &m.host, m.port, &m.user)?;
            added += 1;
        }
        *self = next;
        Ok(added)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("hml-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir(&dir).unwrap();
        dir
    }

    fn two() -> MachineList {
        let mut list = MachineList::default();
        list.add("workstation", "workstation.example.org", 22, "dev")
            .unwrap();
        list.add("server", "192.0.2.9", 2200, "ops").unwrap();
        list
    }

    #[test]
    fn a_missing_file_is_an_empty_list() {
        let dir = scratch("missing");
        assert_eq!(
            MachineList::load(&dir.join("machines.json")).unwrap(),
            MachineList::default()
        );
    }

    #[test]
    fn saved_lists_load_back_with_host_keys_and_last_connected() {
        let dir = scratch("roundtrip");
        let path = dir.join("machines.json");
        let mut list = two();
        let m = list.get_mut("workstation").unwrap();
        m.host_key = Some("SHA256:abc".into());
        m.last_connected_ms = Some(1_700_000_000_000);
        list.save(&path).unwrap();
        assert_eq!(MachineList::load(&path).unwrap(), list);
        assert!(!dir.join("machines.tmp").exists());
    }

    #[test]
    fn unreadable_files_are_errors_not_empty_lists() {
        let dir = scratch("broken");
        let path = dir.join("machines.json");
        for text in [
            "not json",
            r#"{"version": 2, "machines": []}"#,
            r#"{"version": 1, "machines": [{"name": "", "host": "h", "port": 22, "user": "u"}]}"#,
            r#"{"version": 1, "machines": [
                {"name": "a", "host": "h", "port": 22, "user": "u"},
                {"name": "a", "host": "i", "port": 22, "user": "u"}]}"#,
        ] {
            std::fs::write(&path, text).unwrap();
            assert!(MachineList::load(&path).is_err(), "{text}");
        }
    }

    #[test]
    fn names_are_unique() {
        let mut list = two();
        assert_eq!(
            list.add("server", "other", 22, "u"),
            Err(ListError::Duplicate("server".into()))
        );
        assert_eq!(list.machines().len(), 2);
    }

    #[test]
    fn typed_fields_are_checked() {
        let ok = ("workstation", "workstation.example.org", 22, "dev");
        assert!(validate(ok.0, ok.1, ok.2, ok.3).is_ok());
        let long = "x".repeat(65);
        for (name, host, port, user) in [
            ("", ok.1, ok.2, ok.3),
            ("  ", ok.1, ok.2, ok.3),
            (" padded", ok.1, ok.2, ok.3),
            ("two\nlines", ok.1, ok.2, ok.3),
            (long.as_str(), ok.1, ok.2, ok.3),
            (ok.0, "", ok.2, ok.3),
            (ok.0, "has space", ok.2, ok.3),
            (ok.0, "-oProxyCommand=x", ok.2, ok.3),
            (ok.0, ok.1, 0, ok.3),
            (ok.0, ok.1, ok.2, ""),
            (ok.0, ok.1, ok.2, "dev@host"),
            (ok.0, ok.1, ok.2, "-l"),
        ] {
            assert!(
                matches!(validate(name, host, port, user), Err(ListError::Invalid(_))),
                "{name:?} {host:?} {port} {user:?}"
            );
        }
    }

    #[test]
    fn moving_a_machine_drops_its_host_key_but_a_new_user_does_not() {
        let mut list = two();
        list.get_mut("server").unwrap().host_key = Some("SHA256:abc".into());
        list.update("server", "192.0.2.9", 2200, "admin").unwrap();
        assert_eq!(
            list.get("server").unwrap().host_key.as_deref(),
            Some("SHA256:abc")
        );
        list.update("server", "192.0.2.10", 2200, "admin").unwrap();
        assert_eq!(list.get("server").unwrap().host_key, None);
        assert_eq!(
            list.update("nowhere", "h", 22, "u"),
            Err(ListError::NotFound("nowhere".into()))
        );
    }

    // Scenario "Restoring the list on a new install".
    #[test]
    fn an_export_restores_machines_without_host_keys() {
        let mut list = two();
        for m in ["workstation", "server"] {
            let m = list.get_mut(m).unwrap();
            m.host_key = Some("SHA256:secret-ish".into());
            m.last_connected_ms = Some(5);
        }
        let export = list.export();
        assert!(!export.contains("SHA256"), "{export}");
        assert!(!export.contains("last_connected"), "{export}");

        let mut fresh = MachineList::default();
        assert_eq!(fresh.import(&export).unwrap(), 2);
        let names: Vec<&str> = fresh.machines().iter().map(|m| m.name.as_str()).collect();
        assert_eq!(names, ["workstation", "server"]);
        for m in fresh.machines() {
            assert_eq!(m.host_key, None, "{} must ask for setup", m.name);
            assert_eq!(m.last_connected_ms, None);
        }
        assert_eq!(fresh.get("server").unwrap().port, 2200);
    }

    #[test]
    fn importing_keeps_machines_already_listed() {
        let mut list = MachineList::default();
        list.add("server", "kept.example.org", 22, "me").unwrap();
        list.get_mut("server").unwrap().host_key = Some("SHA256:kept".into());
        assert_eq!(list.import(&two().export()).unwrap(), 1);
        let server = list.get("server").unwrap();
        assert_eq!(server.host, "kept.example.org");
        assert_eq!(server.host_key.as_deref(), Some("SHA256:kept"));
        assert!(list.get("workstation").is_some());
    }

    #[test]
    fn a_bad_import_adds_nothing() {
        let mut list = MachineList::default();
        let bad = two().export().replace("\"ops\"", "\"bad user\"");
        assert!(list.import(&bad).is_err());
        assert!(list.machines().is_empty());
        for text in ["[]", r#"{"version": 1, "machines": []}"#, "nonsense"] {
            assert!(
                matches!(list.import(text), Err(ListError::Unreadable(_))),
                "{text}"
            );
        }
    }
}
