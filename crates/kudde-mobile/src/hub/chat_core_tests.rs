//! Phone-only held-chat cache tests.

use super::*;

#[test]
fn the_ninth_terminal_drops_the_one_viewed_longest_ago() {
    let mut h: Held<u32> = Held::new();
    for i in 0..8 {
        h.open(&format!("m/t{i}"), || i);
    }
    assert_eq!(h.open("m/t0", || 99), 0, "held, not made again");
    h.open("m/t8", || 8);
    let keys = h.keys();
    assert_eq!(keys.len(), HELD);
    assert!(
        !keys.contains(&"m/t1".to_string()),
        "t1 was viewed longest ago"
    );
    assert!(keys.contains(&"m/t0".to_string()));
    assert_eq!(h.open("m/t1", || 11), 11, "read again when next opened");
}

#[test]
fn a_memory_trim_keeps_only_the_chat_on_screen() {
    let mut h: Held<u32> = Held::new();
    for i in 0..4 {
        h.open(&format!("m/t{i}"), || i);
    }
    h.trim(Some("m/t2"));
    assert_eq!(h.keys(), ["m/t2"]);
    h.trim(None);
    assert!(h.keys().is_empty());
}
