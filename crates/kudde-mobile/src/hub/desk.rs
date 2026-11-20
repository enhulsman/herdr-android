//! The desk snapshot records, independent of the connection.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct DeskView {
    pub spaces: Vec<DeskSpaceView>,
    pub loose: Vec<String>,
    pub note: Option<DeskNoteView>,
}
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct DeskSpaceView {
    pub id: String,
    pub name: String,
    pub unreadable_tabs: u32,
    pub tabs: Vec<DeskTabView>,
}
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct DeskTabView {
    pub id: String,
    pub name: String,
    pub cells: Vec<DeskCellView>,
}
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct DeskCellView {
    pub pane: DeskPaneView,
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}
#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum DeskPaneView {
    Terminal { address: String },
    Placeholder { label: String },
    Vacant,
}
#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum DeskNoteView {
    NoDocument,
    Unreadable,
}

use herdr_proto::layout::{self, Desk, DeskNote, DeskPane};
use serde_json::Value;

/// Kept outside Live so a lost connection keeps the last seated view.
pub(super) struct HeldDesk {
    pub version: u64,
    pub read: Result<Desk, DeskNote>,
    pub seated: DeskView,
}
impl HeldDesk {
    pub fn new(version: u64, read: Result<Desk, DeskNote>, terminals: &[Value]) -> Self {
        let seated = seated(&read, terminals);
        Self {
            version,
            read,
            seated,
        }
    }
    pub fn reseat(&mut self, terminals: &[Value]) {
        self.seated = seated(&self.read, terminals);
    }
}
fn seated(read: &Result<Desk, DeskNote>, terminals: &[Value]) -> DeskView {
    let empty = Desk { spaces: vec![] };
    let (desk, loose) = layout::seat(read.as_ref().unwrap_or(&empty), terminals);
    let note = read.as_ref().err().map(|n| match n {
        DeskNote::NoDocument => DeskNoteView::NoDocument,
        DeskNote::Unreadable => DeskNoteView::Unreadable,
    });
    DeskView {
        spaces: desk
            .spaces
            .into_iter()
            .map(|s| DeskSpaceView {
                id: s.id,
                name: s.name,
                unreadable_tabs: s.unreadable_tabs,
                tabs: s
                    .tabs
                    .into_iter()
                    .map(|t| DeskTabView {
                        id: t.id,
                        name: t.name,
                        cells: t
                            .cells
                            .into_iter()
                            .map(|c| DeskCellView {
                                pane: match c.pane {
                                    DeskPane::Terminal { address } => {
                                        DeskPaneView::Terminal { address }
                                    }
                                    DeskPane::Placeholder { label } => {
                                        DeskPaneView::Placeholder { label }
                                    }
                                    DeskPane::Vacant => DeskPaneView::Vacant,
                                },
                                x: c.x,
                                y: c.y,
                                w: c.w,
                                h: c.h,
                            })
                            .collect(),
                    })
                    .collect(),
            })
            .collect(),
        loose,
        note,
    }
}
