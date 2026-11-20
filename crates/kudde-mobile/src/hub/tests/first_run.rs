//! First-run failure classification and epoch-owned retry grace (D6).
use super::*;
use crate::ssh::ReachKind;

async fn confirmed(name: &str) -> Rig {
    let mut r = rig(name);
    r.hub.add_machine(WS, WS, 22, "dev").unwrap();
    r.hub.resume();
    r.until_state(WS, |s| matches!(s, MachineState::HostKeyUnconfirmed { .. }))
        .await;
    r.hub.confirm_host_key(WS, WS_KEY).unwrap();
    r
}

async fn closed(r: &mut Rig) -> HubSnapshot {
    r.core().await.exit(0, "  \n");
    r.until("startup close", |s| {
        matches!(
            state(s, WS),
            Some(MachineState::Unreachable {
                kind: ReachKind::CoreStartupClosed,
                ..
            })
        ) && view(s, WS).next_retry_ms.is_some()
    })
    .await
}

#[tokio::test(start_paused = true)]
async fn startup_grace_first_settlement_attempting_second_settlement() {
    let mut r = confirmed("startup-grace").await;
    let first = closed(&mut r).await;
    assert!(view(&first, WS).startup_retry_pending);
    let core = r.core().await;
    let running = r.hub.snapshot();
    assert_eq!(state(&first, WS), state(&running, WS));
    assert_eq!(view(&running, WS).next_retry_ms, None);
    assert!(view(&running, WS).startup_retry_pending);
    core.exit(0, "  \n");
    let second = r
        .until("second close", |s| view(s, WS).next_retry_ms.is_some())
        .await;
    assert_eq!(state(&first, WS), state(&second, WS));
    assert!(!view(&second, WS).startup_retry_pending);
    assert!(!view(&r.hub.snapshot(), WS).startup_retry_pending);
}

#[tokio::test(start_paused = true)]
async fn command_exit_eligibility() {
    for (i, status, stderr, expected) in [
        (0, Some(0), "", Some(ReachKind::CoreStartupClosed)),
        (1, None, " \n", Some(ReachKind::CoreStartupClosed)),
        (2, Some(127), "", None),
        (3, Some(0), NOT_INSTALLED, None),
        (4, Some(1), "", Some(ReachKind::Other)),
        (5, Some(0), "diagnostic", Some(ReachKind::Other)),
    ] {
        let mut r = confirmed(&format!("exit-eligibility-{i}")).await;
        let mut core = r.core().await;
        let tx = core.exit.take().unwrap();
        drop(core);
        tx.send(Exit {
            status,
            stderr: stderr.into(),
        })
        .unwrap();
        let s = r
            .until("settled", |s| view(s, WS).next_retry_ms.is_some())
            .await;
        match expected {
            Some(kind) => assert!(
                matches!(state(&s, WS), Some(MachineState::Unreachable { kind: k, .. }) if *k == kind)
            ),
            None => assert_eq!(state(&s, WS), Some(&MachineState::NotInstalled)),
        }
        assert_eq!(
            view(&s, WS).startup_retry_pending,
            expected == Some(ReachKind::CoreStartupClosed)
        );
        assert!(!r.hub.state.lock().unwrap().live[WS].startup_allowance);
    }
}

#[tokio::test(start_paused = true)]
async fn command_exit_missing_result_and_timeout_qualify() {
    for timeout in [false, true] {
        let mut r = confirmed(if timeout {
            "exit-timeout"
        } else {
            "exit-no-result"
        })
        .await;
        let mut core = r.core().await;
        let tx = core.exit.take().unwrap();
        drop(core);
        let keep = if timeout {
            Some(tx)
        } else {
            drop(tx);
            None
        };
        let s = r
            .until("closed without exit", |s| view(s, WS).startup_retry_pending)
            .await;
        assert!(matches!(
            state(&s, WS),
            Some(MachineState::Unreachable {
                kind: ReachKind::CoreStartupClosed,
                ..
            })
        ));
        drop(keep);
    }
}

#[tokio::test(start_paused = true)]
async fn first_dial_failures_show_and_consume_allowance() {
    for (i, fail, expected) in [
        (0, Fail::NetworkOff, MachineState::NetworkOff),
        (1, Fail::Rejected, MachineState::KeyRejected),
        (2, Fail::NeedsUserAuth, MachineState::NeedsUserAuth),
        (
            3,
            Fail::Unreachable,
            MachineState::Unreachable {
                kind: ReachKind::Refused,
                detail: "connection refused".into(),
            },
        ),
    ] {
        let mut r = rig(&format!("first-dial-{i}"));
        r.hub.add_machine(WS, WS, 22, "dev").unwrap();
        r.hub.resume();
        r.until_state(WS, |s| matches!(s, MachineState::HostKeyUnconfirmed { .. }))
            .await;
        r.dialer.fail(WS, Some(fail));
        r.hub.confirm_host_key(WS, WS_KEY).unwrap();
        let s = r.until_state(WS, |s| *s == expected).await;
        assert!(!view(&s, WS).startup_retry_pending);
        if matches!(fail, Fail::NetworkOff | Fail::NeedsUserAuth) {
            assert_eq!(view(&s, WS).next_retry_ms, None);
        } else {
            r.dialer.fail(WS, None);
            let s = closed(&mut r).await;
            assert!(!view(&s, WS).startup_retry_pending);
        }
    }
}

#[tokio::test(start_paused = true)]
async fn first_mismatch_and_unconfirmed_show_without_retry() {
    for forget in [false, true] {
        let mut r = rig(if forget {
            "unconfirmed-first"
        } else {
            "mismatch-first"
        });
        r.hub.add_machine(WS, WS, 22, "dev").unwrap();
        r.hub.resume();
        r.until_state(WS, |s| matches!(s, MachineState::HostKeyUnconfirmed { .. }))
            .await;
        r.dialer.present(WS, SRV_KEY);
        if forget {
            r.dialer.fail(WS, Some(Fail::Unconfirmed));
        }
        r.hub.confirm_host_key(WS, WS_KEY).unwrap();
        r.until_state(WS, |s| {
            matches!(
                s,
                MachineState::HostKeyMismatch { .. } | MachineState::HostKeyUnconfirmed { .. }
            )
        })
        .await;
        let s = r.hub.snapshot();
        assert!(!view(&s, WS).startup_retry_pending);
        assert_eq!(view(&s, WS).next_retry_ms, None);
        if forget {
            assert!(matches!(
                state(&s, WS),
                Some(MachineState::HostKeyUnconfirmed { .. })
            ));
        } else {
            assert!(
                matches!(state(&s, WS), Some(MachineState::HostKeyMismatch { confirmed, presented, .. }) if confirmed == WS_KEY && presented == SRV_KEY)
            );
        }
    }
}

#[tokio::test(start_paused = true)]
async fn handshake_failure_versions_and_post_connect_drop_are_ineligible() {
    for i in 0..6 {
        let mut r = confirmed(&format!("handshake-ineligible-{i}")).await;
        let mut core = r.core().await;
        match i {
            0 => {
                core.w
                    .write_all(&vec![b'x'; handshake::NOISE_LIMIT + 1])
                    .await
                    .unwrap();
            }
            1 => core.hello("0.1.14").await,
            2 => {
                core.hello_as(json!({"generation": 2, "version": "1.0.0", "methods": []}))
                    .await
            }
            4 => {
                core.w
                    .write_all(
                        b"{\"jsonrpc\":\"2.0\",\"id\":0,\"error\":{\"message\":\"bad hello\"}}\n",
                    )
                    .await
                    .unwrap();
            }
            5 => { /* Hold the stream open until the hello times out. */ }
            _ => {
                core.connect("0.1.15", vec![]).await;
                let s = r.until_state(WS, connected).await;
                assert!(!view(&s, WS).startup_retry_pending);
                drop(core);
            }
        }
        let s = r
            .until("ineligible result", |s| {
                !matches!(
                    state(s, WS),
                    Some(MachineState::Connecting | MachineState::Connected { .. })
                )
            })
            .await;
        assert!(!view(&s, WS).startup_retry_pending);
        assert!(!r.hub.state.lock().unwrap().live[WS].startup_allowance);
        match i {
            1 => assert!(matches!(state(&s, WS), Some(MachineState::TooOld { .. }))),
            2 => assert_eq!(state(&s, WS), Some(&MachineState::AppTooOld)),
            _ => assert!(matches!(
                state(&s, WS),
                Some(MachineState::Unreachable {
                    kind: ReachKind::Other,
                    ..
                })
            )),
        }
    }
}

#[tokio::test(start_paused = true)]
async fn resets_and_stale_epoch_callbacks_cannot_rearm_grace() {
    for i in 0..5 {
        let mut r = confirmed(&format!("grace-reset-{i}")).await;
        assert!(view(&closed(&mut r).await, WS).startup_retry_pending);
        let old_epoch = r.hub.state.lock().unwrap().live[WS].epoch;
        match i {
            0 => {
                r.hub.suspend();
                assert!(!view(&r.hub.snapshot(), WS).startup_retry_pending);
                r.hub.resume();
            }
            1 => r.hub.retry(),
            2 => r.hub.update_machine(WS, WS, 22, "other").unwrap(),
            3 => {
                let mut core = r.core().await;
                core.connect("0.1.15", vec![]).await;
                let s = r.until_state(WS, connected).await;
                assert!(!view(&s, WS).startup_retry_pending);
                drop(core);
                let s = r
                    .until_state(WS, |s| matches!(s, MachineState::Unreachable { .. }))
                    .await;
                assert!(!view(&s, WS).startup_retry_pending);
                continue;
            }
            _ => {
                let dir = r.dir.clone();
                r.hub.shutdown();
                let mut restored = rig_in(dir);
                assert!(!view(&restored.hub.snapshot(), WS).startup_retry_pending);
                restored.hub.resume();
                assert!(!view(&closed(&mut restored).await, WS).startup_retry_pending);
                continue;
            }
        }
        assert!(!view(&r.hub.snapshot(), WS).startup_retry_pending);
        let failure = MachineState::Unreachable {
            kind: ReachKind::CoreStartupClosed,
            detail: "old".into(),
        };
        assert!(!r.hub.settle(WS, old_epoch, failure.clone(), Some(1)));
        assert!(!r.hub.attempting(WS, old_epoch));
        assert!(!r.hub.observe(
            WS,
            old_epoch,
            &EventLines::new(&json!({"terminals": []})),
            Some(MachineState::Connected {
                version: "old".into()
            })
        ));
        assert!(!view(&closed(&mut r).await, WS).startup_retry_pending);
        // Old callbacks cannot clear a fresh explicitly armed pending result either.
        r.hub.confirm_host_key(WS, WS_KEY).unwrap();
        assert!(view(&closed(&mut r).await, WS).startup_retry_pending);
        assert!(!r.hub.settle(WS, old_epoch, failure, None));
        assert!(!r.hub.attempting(WS, old_epoch));
        assert!(view(&r.hub.snapshot(), WS).startup_retry_pending);
    }
}

#[tokio::test(start_paused = true)]
async fn pin_survives_user_edits_before_rejection_or_missing_core() {
    for missing in [false, true] {
        let mut r = rig(if missing {
            "pin-missing"
        } else {
            "pin-rejected"
        });
        r.hub.add_machine(WS, WS, 22, "dev").unwrap();
        r.hub.resume();
        r.until_state(WS, |s| matches!(s, MachineState::HostKeyUnconfirmed { .. }))
            .await;
        if !missing {
            r.dialer.fail(WS, Some(Fail::Rejected));
        }
        r.hub.confirm_host_key(WS, WS_KEY).unwrap();
        if missing {
            r.core().await.exit(127, NOT_INSTALLED);
        }
        let expected = if missing {
            MachineState::NotInstalled
        } else {
            MachineState::KeyRejected
        };
        let s = r.until_state(WS, |s| *s == expected).await;
        assert!(!view(&s, WS).startup_retry_pending);
        assert_eq!(view(&s, WS).last_connected_ms, None);
        r.dialer.fail(WS, None);
        r.dialer.present(WS, SRV_KEY);
        r.hub.update_machine(WS, WS, 22, "other").unwrap();
        let s = r
            .until_state(WS, |s| matches!(s, MachineState::HostKeyMismatch { .. }))
            .await;
        assert_eq!(view(&s, WS).host_key.as_deref(), Some(WS_KEY));
        assert_eq!(view(&s, WS).name, WS);
        assert!(
            matches!(state(&s, WS), Some(MachineState::HostKeyMismatch { confirmed, presented, .. }) if confirmed == WS_KEY && presented == SRV_KEY)
        );
        r.hub.update_machine(WS, WS, 23, "other").unwrap();
        assert_eq!(view(&r.hub.snapshot(), WS).host_key, None);
        r.hub.confirm_host_key(WS, WS_KEY).unwrap();
        r.hub.update_machine(WS, SRV, 23, "other").unwrap();
        assert_eq!(view(&r.hub.snapshot(), WS).host_key, None);
    }
}

#[tokio::test(start_paused = true)]
async fn every_dial_kind_reaches_machine_state() {
    for (i, kind) in [
        ReachKind::NoAnswer,
        ReachKind::Refused,
        ReachKind::NameNotFound,
        ReachKind::NoRoute,
        ReachKind::Other,
    ]
    .into_iter()
    .enumerate()
    {
        let mut r = rig(&format!("hub-dial-kind-{i}"));
        r.hub.add_machine(WS, WS, 22, "dev").unwrap();
        r.hub.resume();
        r.until_state(WS, |s| matches!(s, MachineState::HostKeyUnconfirmed { .. }))
            .await;
        r.dialer.fail(WS, Some(Fail::Reach(kind)));
        r.hub.confirm_host_key(WS, WS_KEY).unwrap();
        let expected = MachineState::Unreachable {
            kind,
            detail: "reason".into(),
        };
        let s = r.until_state(WS, |s| *s == expected).await;
        assert!(!view(&s, WS).startup_retry_pending);
        assert!(view(&s, WS).next_retry_ms.is_some());
        r.dialer.fail(WS, None);
        assert!(!view(&closed(&mut r).await, WS).startup_retry_pending);
    }
}

#[tokio::test(start_paused = true)]
async fn confirmation_while_paused_does_not_arm_resume() {
    let mut r = rig("paused-confirmation");
    r.add_trusted(WS, WS_KEY);
    assert!(!view(&r.hub.snapshot(), WS).startup_retry_pending);
    r.hub.resume();
    assert!(!view(&closed(&mut r).await, WS).startup_retry_pending);
}
