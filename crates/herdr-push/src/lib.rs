//! The push watcher (design.md §5, "herdr-push"): watches one existing
//! herdr-core and pushes alerts to the phones registered there.

pub mod config;
pub mod deliver;
pub mod phones;
pub mod rules;
pub mod status;
pub mod watch;
pub mod webpush;
