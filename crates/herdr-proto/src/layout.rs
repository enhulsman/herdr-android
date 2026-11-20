//! Which terminals the `herdr-app` layout places in a pane (design.md §4-5).
//!
//! The document is `{spaces: [{tabs: [{root: Node}]}]}`; a node is a block
//! (`content: {terminal}` or a mod pane) or a container with `children`. It is
//! read leniently: unknown node types are walked for children, a tab whose root
//! is not a node is skipped. Addresses are machine-local; a qualified one
//! (`ws/t7`) names the same terminal as `t7`. Moved here from kudde-push so
//! the phone reads the same document (start-agents design D3).

use std::collections::{BTreeSet, HashSet};

use serde_json::Value;

/// Which terminals sit in a pane of the `herdr-app` layout.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Layout {
    Placed(BTreeSet<String>),
    /// No usable layout: every agent may alert (the note says why).
    Unreadable(String),
}

/// A space as the phone offers it: where a new terminal goes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Space {
    pub id: String,
    /// Empty: called after its folder (`start::space_title`).
    pub name: String,
    /// As the desk wrote it: absolute, `~/...`, or empty for home.
    pub folder: String,
}

/// The spaces a new terminal can join, in the desk's order. No document, an
/// unreadable one and zero spaces all give none; an entry without a string id
/// is skipped.
pub fn spaces(doc: &Value) -> Vec<Space> {
    let Some(list) = doc.get("spaces").and_then(Value::as_array) else {
        return Vec::new();
    };
    let text = |v: &Value, key: &str| v.get(key).and_then(Value::as_str).unwrap_or("").to_owned();
    list.iter()
        .filter_map(|s| {
            let id = s.get("id")?.as_str().filter(|id| !id.is_empty())?;
            Some(Space {
                id: id.to_owned(),
                name: text(s, "name"),
                folder: text(s, "folder"),
            })
        })
        .collect()
}

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

/// The desk's ordered spaces.
#[derive(Clone, Debug, PartialEq)]
pub struct Desk {
    pub spaces: Vec<DeskSpace>,
}
#[derive(Clone, Debug, PartialEq)]
pub struct DeskSpace {
    pub id: String,
    pub name: String,
    pub unreadable_tabs: u32,
    pub tabs: Vec<DeskTab>,
}
#[derive(Clone, Debug, PartialEq)]
pub struct DeskTab {
    pub id: String,
    pub name: String,
    pub cells: Vec<DeskCell>,
}
#[derive(Clone, Debug, PartialEq)]
pub struct DeskCell {
    pub pane: DeskPane,
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}
#[derive(Clone, Debug, PartialEq)]
pub enum DeskPane {
    Terminal { address: String },
    Placeholder { label: String },
    Vacant,
}
#[derive(Clone, Debug, PartialEq)]
pub enum DeskNote {
    NoDocument,
    Unreadable,
}
// Count actual accumulated-address copies and insertions in regression tests.
#[cfg(test)]
thread_local! {
    static DESK_WORK: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}
#[derive(Default)]
struct TerminalSet(HashSet<String>);
impl Clone for TerminalSet {
    fn clone(&self) -> Self {
        #[cfg(test)]
        DESK_WORK.with(|work| work.set(work.get() + self.0.len()));
        Self(self.0.clone())
    }
}
impl TerminalSet {
    fn insert(&mut self, address: String) -> bool {
        #[cfg(test)]
        DESK_WORK.with(|work| work.set(work.get() + 1));
        self.0.insert(address)
    }
}
/// Reads an ordered, bounded layout without changing the document.
pub fn desk(doc: &Value) -> Result<Desk, DeskNote> {
    if doc.is_null() {
        return Err(DeskNote::NoDocument);
    }
    let object = doc.as_object().ok_or(DeskNote::Unreadable)?;
    let list = match object.get("spaces") {
        None => return Ok(Desk { spaces: vec![] }),
        Some(Value::Array(list)) => list,
        _ => return Err(DeskNote::Unreadable),
    };
    let mut out = Desk { spaces: vec![] };
    let (mut space_ids, mut tab_ids, mut terminals) =
        (HashSet::new(), HashSet::new(), TerminalSet::default());
    for (i, s) in list.iter().enumerate() {
        let Some(id) = s["id"].as_str().filter(|id| !id.is_empty()) else {
            continue;
        };
        let space = Space {
            id: id.into(),
            name: text(s, "name").into(),
            folder: text(s, "folder").into(),
        };
        let mut space = DeskSpace {
            id: unique_id(id, i + 1, &mut space_ids),
            name: crate::start::space_title(&space, Some("~")),
            unreadable_tabs: 0,
            tabs: vec![],
        };
        match &s["tabs"] {
            Value::Null => {}
            Value::Array(tabs) => {
                for (j, t) in tabs.iter().enumerate() {
                    let mut cells = vec![];
                    // Validate the tab before committing any of its addresses.
                    if read_node(&t["root"], [0., 0., 1., 1.], 0, &mut cells).is_err() {
                        space.unreadable_tabs += 1;
                        continue;
                    }
                    for cell in &mut cells {
                        if let DeskPane::Terminal { address } = &cell.pane
                            && !terminals.insert(address.clone())
                        {
                            cell.pane = DeskPane::Vacant;
                        }
                    }
                    let name = text(t, "name");
                    space.tabs.push(DeskTab {
                        id: unique_id(text(t, "id"), j + 1, &mut tab_ids),
                        name: if name.trim().is_empty() {
                            (j + 1).to_string()
                        } else {
                            name.into()
                        },
                        cells,
                    });
                }
            }
            _ => space.unreadable_tabs = 1,
        }
        out.spaces.push(space);
    }
    Ok(out)
}
fn text<'a>(v: &'a Value, key: &str) -> &'a str {
    v.get(key).and_then(Value::as_str).unwrap_or("")
}
fn unique_id(id: &str, position: usize, seen: &mut HashSet<String>) -> String {
    let mut key = id.to_owned();
    if key.is_empty() || seen.contains(&key) {
        key = format!("{id}#{position}");
    }
    while !seen.insert(key.clone()) {
        key.push_str(&format!("#{position}"));
    }
    key
}
fn read_node(node: &Value, r: [f64; 4], depth: usize, cells: &mut Vec<DeskCell>) -> Result<(), ()> {
    if depth > MAX_DEPTH || !node.is_object() {
        return Err(());
    }
    let terminal = node
        .pointer("/content/terminal")
        .and_then(Value::as_str)
        .filter(|a| !a.is_empty());
    let pane = if let Some(address) = terminal {
        let address = address.rsplit('/').next().unwrap_or(address).to_owned();
        DeskPane::Terminal { address }
    } else if let Some(label) = node.pointer("/content/mod") {
        DeskPane::Placeholder {
            label: label.as_str().unwrap_or("").into(),
        }
    } else if let Some(children) = node["children"].as_array().filter(|c| !c.is_empty()) {
        let children: Vec<_> = children.iter().filter(|c| c.is_object()).collect();
        let weight = |c: &&Value| {
            c.pointer("/size/weight")
                .and_then(Value::as_f64)
                .filter(|w| w.is_finite() && *w > 0.)
                .unwrap_or(1.)
                .clamp(0.01, 1000.)
        };
        let sum: f64 = children.iter().map(weight).sum();
        let vertical = matches!(text(node, "type"), "column" | "stack");
        let mut offset = 0.;
        for child in &children {
            let share = weight(child) / sum;
            let mut area = r;
            if vertical {
                area[1] += offset * r[3];
                area[3] *= share;
            } else {
                area[0] += offset * r[2];
                area[2] *= share;
            }
            read_node(child, area, depth + 1, cells)?;
            offset += share;
        }
        return Ok(());
    } else {
        DeskPane::Placeholder {
            label: String::new(),
        }
    };
    if cells.len() == 64 {
        return Err(());
    }
    let x = r[0].clamp(0., 1.) as f32;
    let y = r[1].clamp(0., 1.) as f32;
    let w = (r[2].clamp(0., 1.) as f32).min(1. - x);
    let h = (r[3].clamp(0., 1.) as f32).min(1. - y);
    cells.push(DeskCell { pane, x, y, w, h });
    Ok(())
}
/// Resolves pane eligibility from the machine's own records, preserving order.
pub fn seat(desk: &Desk, terminals: &[Value]) -> (Desk, Vec<String>) {
    let eligible: Vec<_> = terminals
        .iter()
        .filter(|t| t["state"] == "live" && t["parent"].is_null())
        .filter_map(|t| t["address"].as_str())
        .collect();
    let mut desk = desk.clone();
    let mut placed = BTreeSet::new();
    for cell in desk
        .spaces
        .iter_mut()
        .flat_map(|s| &mut s.tabs)
        .flat_map(|t| &mut t.cells)
    {
        if let DeskPane::Terminal { address } = &cell.pane {
            if eligible.contains(&address.as_str()) {
                placed.insert(address.clone());
            } else {
                cell.pane = DeskPane::Vacant;
            }
        }
    }
    let loose = eligible
        .into_iter()
        .filter(|a| !placed.contains(*a))
        .map(str::to_owned)
        .collect();
    (desk, loose)
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

    fn document(root: Value) -> Value {
        json!({"spaces":[{"id":"s1","folder":"~/src/website","tabs":[{"id":"tb1","root":root}]}]})
    }
    fn cells(root: Value) -> Vec<DeskCell> {
        desk(&document(root))
            .unwrap()
            .spaces
            .remove(0)
            .tabs
            .remove(0)
            .cells
    }
    fn split(kind: &str, children: Vec<Value>) -> Value {
        json!({"type":kind,"children":children})
    }
    fn rect(cell: &DeskCell, want: [f32; 4]) {
        for (got, want) in [cell.x, cell.y, cell.w, cell.h].into_iter().zip(want) {
            assert!((got - want).abs() < 0.000001, "{got} != {want}");
        }
    }
    fn terminal(cell: &DeskCell, address: &str) {
        assert_eq!(
            cell.pane,
            DeskPane::Terminal {
                address: address.into()
            }
        );
    }
    fn weighted(weights: Vec<Value>, kind: &str) -> Vec<DeskCell> {
        cells(split(
            kind,
            weights
                .into_iter()
                .enumerate()
                .map(|(i, w)| {
                    let mut b = block(&format!("t{i}"));
                    b["size"] = json!({"weight":w});
                    b
                })
                .collect(),
        ))
    }
    #[test]
    fn three_panes_in_two_columns() {
        let c = cells(split(
            "row",
            vec![block("t1"), split("column", vec![block("t2"), block("t3")])],
        ));
        assert_eq!(c.len(), 3);
        for (c, a) in c.iter().zip(["t1", "t2", "t3"]) {
            terminal(c, a);
        }
        rect(&c[0], [0., 0., 0.5, 1.]);
        rect(&c[1], [0.5, 0., 0.5, 0.5]);
        rect(&c[2], [0.5, 0.5, 0.5, 0.5]);
    }
    #[test]
    fn weights() {
        let c = weighted(vec![json!(2), json!(1)], "row");
        rect(&c[0], [0., 0., 2. / 3., 1.]);
        rect(&c[1], [2. / 3., 0., 1. / 3., 1.]);
    }
    #[test]
    fn unusable_weights() {
        let c = weighted(vec![json!(0), json!(-3), json!("wide")], "row");
        for (i, c) in c.iter().enumerate() {
            rect(c, [i as f32 / 3., 0., 1. / 3., 1.]);
        }
    }
    #[test]
    fn all_zero_weights() {
        let c = weighted(vec![json!(0), json!(0)], "column");
        rect(&c[0], [0., 0., 1., 0.5]);
        rect(&c[1], [0., 0.5, 1., 0.5]);
    }
    #[test]
    fn huge_weight() {
        let c = weighted(vec![json!(1e300), json!(1)], "row");
        rect(&c[0], [0., 0., 1000. / 1001., 1.]);
        rect(&c[1], [1000. / 1001., 0., 1. / 1001., 1.]);
    }
    #[test]
    fn qualified_address() {
        terminal(&cells(block("workstation/t7"))[0], "t7");
    }
    #[test]
    fn mod_and_unknown_leaf() {
        let c = cells(split(
            "row",
            vec![
                block("t1"),
                json!({"content":{"mod":"notes"}}),
                json!({"type":"spiral"}),
            ],
        ));
        terminal(&c[0], "t1");
        assert_eq!(
            c[1].pane,
            DeskPane::Placeholder {
                label: "notes".into()
            }
        );
        assert_eq!(c[2].pane, DeskPane::Placeholder { label: "".into() });
        for (i, c) in c.iter().enumerate() {
            rect(c, [i as f32 / 3., 0., 1. / 3., 1.]);
        }
    }
    #[test]
    fn unknown_container() {
        let c = cells(split("spiral", vec![block("t1"), block("t2")]));
        terminal(&c[0], "t1");
        terminal(&c[1], "t2");
        rect(&c[1], [0.5, 0., 0.5, 1.]);
    }
    #[test]
    fn malformed_tab() {
        let d=desk(&json!({"spaces":[{"id":"s","tabs":[{"root":block("t1")},{"root":3},{"root":block("t3")}]}]})).unwrap();
        assert_eq!(d.spaces[0].unreadable_tabs, 1);
        assert_eq!(
            d.spaces[0]
                .tabs
                .iter()
                .map(|t| t.name.as_str())
                .collect::<Vec<_>>(),
            ["1", "3"]
        );
    }
    #[test]
    fn same_terminal_twice() {
        let d=desk(&json!({"spaces":[{"id":"s","tabs":[{"root":block("t4")},{"root":block("workstation/t4")}]}]})).unwrap();
        terminal(&d.spaces[0].tabs[0].cells[0], "t4");
        assert_eq!(d.spaces[0].tabs[1].cells[0].pane, DeskPane::Vacant);
    }
    #[test]
    fn duplicate_ids() {
        let d=desk(&json!({"spaces":[{"id":"s","tabs":[{"id":"tb1","root":block("t1")},{"id":"tb1","root":block("t2")},{"id":"tb1#2","root":block("t3")}]},{"id":"s"},{"id":"s#2"}]})).unwrap();
        let ids: BTreeSet<_> = d.spaces.iter().map(|s| &s.id).collect();
        assert_eq!(ids.len(), 3);
        let ts = &d.spaces[0].tabs;
        assert_eq!(ts.iter().map(|t| &t.id).collect::<BTreeSet<_>>().len(), 3);
        terminal(&ts[0].cells[0], "t1");
        terminal(&ts[1].cells[0], "t2");
    }
    #[test]
    fn no_names() {
        let d=desk(&json!({"spaces":[{"id":"s","name":"","folder":"~/src/website","tabs":[{"root":{}},{"name":"  ","root":{}}]}]})).unwrap();
        assert_eq!(d.spaces[0].name, "website");
        assert_eq!(d.spaces[0].tabs[1].name, "2");
    }
    #[test]
    fn stack_is_column() {
        let c = cells(split("stack", vec![block("t1"), block("t2")]));
        rect(&c[1], [0., 0.5, 1., 0.5]);
    }
    #[test]
    fn other_sizes_and_hidden() {
        for size in [
            json!({"px":500}),
            json!({"weight":"NaN"}),
            json!({"weight":null}),
            json!({"weight":{}}),
            json!({"weight":true}),
        ] {
            let mut b = block("t1");
            b["size"] = size;
            b["hidden"] = json!(true);
            let c = cells(split("row", vec![b, block("t2")]));
            terminal(&c[0], "t1");
            rect(&c[0], [0., 0., 0.5, 1.]);
        }
        let c = weighted(vec![json!(1e-300), json!(1)], "row");
        rect(&c[0], [0., 0., 0.01 / 1.01, 1.]);
    }
    #[test]
    fn depth_and_cell_limits() {
        let mut root = block("t1");
        for _ in 0..64 {
            root = split("row", vec![root]);
        }
        assert_eq!(cells(root.clone()).len(), 1);
        root = split("row", vec![root]);
        for root in [
            root,
            split("row", (0..65).map(|i| block(&format!("t{i}"))).collect()),
        ] {
            let d = desk(&document(root)).unwrap();
            assert_eq!(d.spaces[0].unreadable_tabs, 1);
            assert!(d.spaces[0].tabs.is_empty());
        }
        assert_eq!(
            cells(split(
                "row",
                (0..64).map(|i| block(&format!("t{i}"))).collect()
            ))
            .len(),
            64
        );
    }
    #[test]
    fn malformed_spaces_and_documents() {
        assert_eq!(desk(&Value::Null), Err(DeskNote::NoDocument));
        for v in [json!("text"), json!([]), json!({"spaces":3})] {
            assert_eq!(desk(&v), Err(DeskNote::Unreadable));
        }
        assert!(desk(&json!({})).unwrap().spaces.is_empty());
        let d=desk(&json!({"spaces":[{},{"id":""},{"id":"s","tabs":3},{"id":"s2","tabs":null},{"id":"s3"}]})).unwrap();
        assert_eq!(d.spaces.len(), 3);
        assert_eq!(d.spaces[0].unreadable_tabs, 1);
        assert_eq!(d.spaces[1].unreadable_tabs, 0);
    }
    #[test]
    fn odd_documents_have_bounded_rectangles() {
        let mut nested = block("t1");
        for i in 0..64 {
            nested["size"] = json!({"weight":if i%2==0 {1e300} else {1e-300}});
            nested = split(
                if i % 2 == 0 { "row" } else { "column" },
                vec![block(&format!("n{i}")), nested],
            );
        }
        for root in [
            nested,
            split("row", vec![json!(false), json!({}), json!({"children":[]})]),
            split("column", vec![block("t1"), block("t2")]),
        ] {
            let d = desk(&document(root)).unwrap();
            for c in d.spaces.iter().flat_map(|s| &s.tabs).flat_map(|t| &t.cells) {
                for f in [c.x, c.y, c.w, c.h] {
                    assert!(f.is_finite() && f >= 0.);
                }
                assert!(c.x + c.w <= 1. && c.y + c.h <= 1.);
            }
        }
    }
    #[test]
    fn representative_document() {
        let d=desk(&json!({"spaces":[
            {"id":"website","name":"website","tabs":[{"id":"main","name":"main","root":split("row",vec![block("t1"),split("column",vec![block("t2"),json!({"content":{"mod":"notes"}})])])},{"id":"build","root":block("t3")}]},
            {"id":"api","name":"api","tabs":[{"id":"test","root":split("column",vec![block("t4"),split("row",vec![block("t5"),block("t6")])])},{"id":"docs","root":block("t7")}]}
        ]})).unwrap();
        assert_eq!(
            d.spaces.iter().map(|s| s.name.as_str()).collect::<Vec<_>>(),
            ["website", "api"]
        );
        assert_eq!(
            d.spaces.iter().map(|s| s.tabs.len()).collect::<Vec<_>>(),
            [2, 2]
        );
        terminal(&d.spaces[1].tabs[0].cells[2], "t6");
    }
    fn record(address: &str, state: &str, parent: Value, reported: bool) -> Value {
        json!({"address":address,"state":state,"parent":parent,"reported":reported,"status":null})
    }
    #[test]
    fn live_top_level_is_seated() {
        let d = desk(&document(block("t1"))).unwrap();
        let (d, loose) = seat(&d, &[record("t1", "live", Value::Null, false)]);
        terminal(&d.spaces[0].tabs[0].cells[0], "t1");
        assert!(loose.is_empty());
    }
    #[test]
    fn sub_agent_in_a_pane() {
        for reported in [false, true] {
            let d = desk(&document(block("t9"))).unwrap();
            let (d, loose) = seat(&d, &[record("t9", "live", json!("t3"), reported)]);
            assert_eq!(d.spaces[0].tabs[0].cells[0].pane, DeskPane::Vacant);
            assert!(loose.is_empty());
        }
    }
    #[test]
    fn terminal_that_has_ended_or_is_missing() {
        for records in [vec![], vec![record("t1", "finished", Value::Null, true)]] {
            let (d, loose) = seat(&desk(&document(block("t1"))).unwrap(), &records);
            assert_eq!(d.spaces[0].tabs[0].cells[0].pane, DeskPane::Vacant);
            assert!(loose.is_empty());
        }
    }
    #[test]
    fn loose_records_order_and_no_children() {
        let (d, loose) = seat(
            &desk(&document(block("t1"))).unwrap(),
            &[
                record("t3", "live", Value::Null, true),
                record("t9", "live", json!("t3"), false),
                json!({"address":"t2","state":"live"}),
                record("t1", "live", Value::Null, false),
                record("t4", "finished", Value::Null, true),
            ],
        );
        terminal(&d.spaces[0].tabs[0].cells[0], "t1");
        assert_eq!(loose, ["t3", "t2"]);
    }
    #[test]
    fn ten_thousand_tabs_have_linear_deduplication_work() {
        let count = 10_000;
        let tabs: Vec<_> = (0..count)
            .map(|i| {
                json!({
                    "id": format!("tab{i}"), "root": block(&format!("t{i}"))
                })
            })
            .collect();
        let document = json!({"spaces":[{"id":"s1","tabs":tabs}]});
        DESK_WORK.with(|work| work.set(0));
        let desk = desk(&document).unwrap();
        assert_eq!(desk.spaces[0].tabs.len(), count);
        terminal(&desk.spaces[0].tabs[count - 1].cells[0], "t9999");
        let work = DESK_WORK.with(|work| work.get());
        assert!(
            work <= count * 2,
            "{work} address copies/insertions for {count} tabs"
        );
    }
    #[test]
    fn unreadable_tabs_do_not_reserve_terminal_addresses() {
        let mut d = document(block("t1"));
        let oversized = split("row", (0..65).map(|i| block(&format!("t{i}"))).collect());
        d["spaces"][0]["tabs"] = json!([{"root":oversized},{"root":block("t1")}]);
        let d = desk(&d).unwrap();
        assert_eq!(d.spaces[0].unreadable_tabs, 1);
        terminal(&d.spaces[0].tabs[0].cells[0], "t1");
    }
}
