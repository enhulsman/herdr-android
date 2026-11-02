//! Protocol code shared by herdr-mobile and herdr-push (design.md, "Shared crate").
//!
//! Parts are adapted from herdr (herdr-app `src/hc.rs` and `src/model.rs`),
//! licensed under the Apache License 2.0; see `LICENSE-herdr-Apache-2.0` in
//! this crate. Each adapted module says what it took and what changed.

pub mod attention;
pub mod compat;
pub mod events;
pub mod handshake;
pub mod rpc;
pub mod viewer;
pub mod wire;
