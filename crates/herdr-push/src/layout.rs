//! Which terminals the `herdr-app` layout places in a pane (design.md §4-5).
//!
//! The document is `{spaces: [{tabs: [{root: Node}]}]}`; a node is a block
//! (`content: {terminal}` or a mod pane) or a container with `children`. It is
//! read leniently: unknown node types are walked for children, a tab whose root
//! is not a node is skipped. Addresses are machine-local; a qualified one
//! (`ws/t7`) names the same terminal as `t7`.

use std::collections::BTreeSet;

use serde_json::Value;

use crate::rules::Layout;

pub const NO_DOCUMENT: &str = "no layout document";
pub const UNREADABLE: &str = "layout unreadable";

/// The placed terminals, or why every agent may alert.
pub fn placed(doc: &Value) -> Layout {
    let unreadable = || Layout::Unreadable(UNREADABLE.into());
    if doc.is_null() {
        return Layout::Unreadable(NO_DOCUMENT.into());
    }
    let Some(doc) = doc.as_object() else {
        return unreadable();
    };
    let spaces = match doc.get("spaces") {
        None => return Layout::Placed(BTreeSet::new()),
        Some(Value::Array(spaces)) => spaces,
        Some(_) => return unreadable(),
    };
    let mut placed = BTreeSet::new();
    for space in spaces {
        let tabs = match &space["tabs"] {
            Value::Null => continue,
            Value::Array(tabs) => tabs,
            _ => return unreadable(),
        };
        for tab in tabs {
            walk(&tab["root"], &mut placed, 0);
        }
    }
    Layout::Placed(placed)
}

/// Deeper than any real layout; a hostile document cannot overflow the stack.
const MAX_DEPTH: usize = 64;

fn walk(node: &Value, placed: &mut BTreeSet<String>, depth: usize) {
    if depth > MAX_DEPTH || !node.is_object() {
        return;
    }
    if let Some(address) = node.pointer("/content/terminal").and_then(Value::as_str) {
        let local = address.rsplit('/').next().unwrap_or(address);
        if !local.is_empty() {
            placed.insert(local.to_owned());
        }
    }
    for child in node["children"].as_array().into_iter().flatten() {
        walk(child, placed, depth + 1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn block(terminal: &str) -> Value {
        json!({"id": "p1", "type": "block", "content": {"terminal": terminal}})
    }

    #[test]
    fn terminals_in_any_pane_of_any_tab_are_placed() {
        let doc = json!({"spaces": [
            {"id": "s1", "name": "website", "folder": "~/website", "tabs": [
                {"id": "tb1", "name": "main", "root": {"id": "tb1", "type": "row", "children": [
                    block("t1"),
                    {"type": "column", "children": [
                        block("ws/t2"),
                        {"type": "block", "content": {"mod": "notes", "props": {"terminal": "t9"}}},
                    ]},
                    {"type": "stack", "active": "p4", "children": [block("t4")]},
                    {"type": "spiral", "children": [block("t5")]},
                ]}},
                {"id": "tb2", "root": "not a node"},
                {"id": "tb3"},
            ]},
            {"id": "s2", "tabs": [{"root": block("t6")}]},
        ]});
        let want: BTreeSet<String> = ["t1", "t2", "t4", "t5", "t6"].map(String::from).into();
        assert_eq!(placed(&doc), Layout::Placed(want));
    }

    #[test]
    fn an_app_with_no_tabs_places_nothing() {
        assert_eq!(
            placed(&json!({"spaces": []})),
            Layout::Placed(BTreeSet::new())
        );
        assert_eq!(placed(&json!({})), Layout::Placed(BTreeSet::new()));
    }

    #[test]
    fn no_document_or_a_broken_one_is_unreadable() {
        assert_eq!(placed(&Value::Null), Layout::Unreadable(NO_DOCUMENT.into()));
        for broken in [
            json!("text"),
            json!([1]),
            json!({"spaces": 3}),
            json!({"spaces": [{"tabs": 1}]}),
        ] {
            assert_eq!(
                placed(&broken),
                Layout::Unreadable(UNREADABLE.into()),
                "{broken}"
            );
        }
    }
}
