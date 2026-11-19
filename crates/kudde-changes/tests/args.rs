//! Task 2.1: the argument lists (design D2).

use kudde_changes::call::{ENV, GREP_ARGS_MAX, PREFIX};
use kudde_changes::{Base, Call, ObjectFormat, Path, Shape, build, parse_status};

const TOP: &str = "/work/website";

/// Paths as git prints them, so the builder gets them the only way it can.
fn paths(names: &[&[u8]]) -> Vec<Path> {
    let mut out = Vec::new();
    for n in names {
        out.extend_from_slice(b"?? ");
        out.extend_from_slice(n);
        out.push(0);
    }
    parse_status(&out).into_iter().map(|e| e.path).collect()
}

fn one(shape: Shape, cwd: &str) -> Call {
    let mut calls = build(shape, cwd);
    assert_eq!(calls.len(), 1, "{shape:?}");
    calls.remove(0)
}

fn args(call: &Call) -> Vec<&str> {
    assert_eq!(call.argv[0], "git");
    assert_eq!(&call.argv[1..=PREFIX.len()], PREFIX);
    call.argv[1 + PREFIX.len()..]
        .iter()
        .map(String::as_str)
        .collect()
}

#[test]
fn prefix_holds_every_guard() {
    for flag in [
        "--no-pager",
        "--literal-pathspecs",
        "core.fsmonitor=false",
        "diff.autoRefreshIndex=false",
        "core.quotePath=false",
        "color.ui=never",
    ] {
        assert!(PREFIX.contains(&flag), "{flag}");
    }
    let env: Vec<(&str, &str)> = ENV.to_vec();
    for kv in [
        ("GIT_OPTIONAL_LOCKS", "0"),
        ("GIT_LITERAL_PATHSPECS", "1"),
        ("GIT_NO_LAZY_FETCH", "1"),
        ("LC_ALL", "C"),
        ("LANGUAGE", "C"),
    ] {
        assert!(env.contains(&kv), "{kv:?}");
    }
}

#[test]
fn top_runs_from_the_agents_folder() {
    let c = one(Shape::Top, "/work/website/app");
    assert_eq!(args(&c), ["rev-parse", "--show-toplevel", "--"]);
    assert_eq!(c.cwd, "/work/website/app");
    assert_eq!(c.timeout_s, 20);
}

#[test]
fn head() {
    let c = one(Shape::Head, TOP);
    assert_eq!(
        args(&c),
        [
            "rev-parse",
            "--show-object-format",
            "--verify",
            "--quiet",
            "HEAD",
            "--"
        ]
    );
    assert_eq!(c.cwd, TOP);
    assert_eq!(c.timeout_s, 20);
}

#[test]
fn status() {
    let c = one(Shape::Status, TOP);
    assert_eq!(
        args(&c),
        [
            "status",
            "--porcelain=v1",
            "-z",
            "--no-renames",
            "--untracked-files=all",
            "--ignore-submodules=dirty",
            "--"
        ]
    );
    assert_eq!(c.timeout_s, 30);
}

#[test]
fn counts_against_head_and_the_empty_trees() {
    for (base, id) in [
        (Base::Head, "HEAD"),
        (
            Base::EmptyTree(ObjectFormat::Sha1),
            "4b825dc642cb6eb9a060e54bf8d69288fbee4904",
        ),
        (
            Base::EmptyTree(ObjectFormat::Sha256),
            "6ef19b41225c5369f1c104d45d8d85efa9b057b53b14b4b9b939dd74decc5321",
        ),
    ] {
        let c = one(Shape::Counts(base), TOP);
        assert_eq!(
            args(&c),
            [
                "diff",
                id,
                "--raw",
                "--numstat",
                "-z",
                "--no-renames",
                "--ignore-submodules=dirty",
                "--no-ext-diff",
                "--no-textconv",
                "--"
            ]
        );
        assert_eq!(c.timeout_s, 30);
    }
}

#[test]
fn diff_puts_the_path_after_the_options() {
    for name in [
        &b"src/main.rs"[..],
        b"HEAD",
        b"--output=x",
        b":(glob)*",
        b"-n",
        b"with space",
        b"line\nbreak",
    ] {
        let p = &paths(&[name])[0];
        let c = one(Shape::Diff(Base::Head, p), TOP);
        let want = std::str::from_utf8(name).unwrap();
        assert_eq!(
            args(&c),
            [
                "diff",
                "HEAD",
                "--no-ext-diff",
                "--no-textconv",
                "--no-color",
                "--submodule=short",
                "--ignore-submodules=dirty",
                "--",
                want
            ]
        );
        assert_eq!(c.cwd, TOP);
        assert_eq!(c.timeout_s, 30);
    }
}

#[test]
fn new_file_diffs_against_dev_null() {
    let p = &paths(&[b"--output=x"])[0];
    let c = one(Shape::NewFile(p), TOP);
    assert_eq!(
        args(&c),
        [
            "diff",
            "--no-index",
            "--no-ext-diff",
            "--no-textconv",
            "--no-color",
            "--",
            "/dev/null",
            "--output=x"
        ]
    );
    assert_eq!(c.timeout_s, 30);
}

#[test]
fn new_file_counts() {
    let ps = paths(&[b"a.txt", b"-n", b"docs/b c.md"]);
    let refs: Vec<&Path> = ps.iter().collect();
    let c = one(Shape::NewFileCounts(&refs), TOP);
    assert_eq!(
        args(&c),
        [
            "grep",
            "--untracked",
            "-I",
            "-c",
            "-z",
            "--no-color",
            "-e",
            "",
            "--",
            "a.txt",
            "-n",
            "docs/b c.md"
        ]
    );
    assert_eq!(c.timeout_s, 30);
}

#[test]
fn folders_never_reach_grep_or_new_file() {
    let ps = paths(&[b"vendor/lib/", b"a.txt", b"other/"]);
    assert!(ps[0].is_folder() && ps[2].is_folder());
    assert!(build(Shape::NewFile(&ps[0]), TOP).is_empty());
    assert!(build(Shape::Diff(Base::Head, &ps[0]), TOP).is_empty());
    let refs: Vec<&Path> = ps.iter().collect();
    let c = one(Shape::NewFileCounts(&refs), TOP);
    let after = &args(&c)[9..];
    assert_eq!(after, ["a.txt"]);
    let only_folders = [&ps[0], &ps[2]];
    assert!(build(Shape::NewFileCounts(&only_folders), TOP).is_empty());
}

#[test]
fn paths_not_utf8_are_not_passed() {
    let ps = paths(&[b"caf\xe9.txt", b"ok.txt"]);
    assert_eq!(ps[0].display(), "caf\u{fffd}.txt");
    assert!(build(Shape::NewFile(&ps[0]), TOP).is_empty());
    assert!(build(Shape::Diff(Base::Head, &ps[0]), TOP).is_empty());
    let refs: Vec<&Path> = ps.iter().collect();
    let c = one(Shape::NewFileCounts(&refs), TOP);
    assert_eq!(&args(&c)[9..], ["ok.txt"]);
}

fn arg_bytes(c: &Call) -> usize {
    c.argv.iter().map(|a| a.len() + 1).sum()
}

#[test]
fn grep_is_cut_at_24_kb_of_arguments() {
    let names: Vec<Vec<u8>> = (0..3000)
        .map(|i| format!("src/generated/module_{i:05}.rs").into_bytes())
        .collect();
    let slices: Vec<&[u8]> = names.iter().map(Vec::as_slice).collect();
    let ps = paths(&slices);
    let refs: Vec<&Path> = ps.iter().collect();
    let calls = build(Shape::NewFileCounts(&refs), TOP);
    assert!(calls.len() > 1);
    let mut seen = Vec::new();
    for c in &calls {
        assert!(arg_bytes(c) <= GREP_ARGS_MAX, "{}", arg_bytes(c));
        let a = args(c);
        assert_eq!(
            a[..9],
            [
                "grep",
                "--untracked",
                "-I",
                "-c",
                "-z",
                "--no-color",
                "-e",
                "",
                "--"
            ]
        );
        seen.extend(a[9..].iter().map(|s| s.to_string()));
    }
    let want: Vec<String> = names
        .iter()
        .map(|n| String::from_utf8(n.clone()).unwrap())
        .collect();
    assert_eq!(seen, want, "every path once, in order");
    // Each call but the last is full: the next path would not have fitted.
    for (c, next) in calls.iter().zip(calls.iter().skip(1)) {
        let first_next = &next.argv[1 + PREFIX.len() + 9];
        assert!(arg_bytes(c) + first_next.len() + 1 > GREP_ARGS_MAX);
    }
}

#[test]
fn a_path_longer_than_the_cut_gets_a_call_of_its_own() {
    let long = vec![b'x'; GREP_ARGS_MAX + 10];
    let ps = paths(&[b"a.txt", &long, b"b.txt"]);
    let refs: Vec<&Path> = ps.iter().collect();
    let calls = build(Shape::NewFileCounts(&refs), TOP);
    let lists: Vec<Vec<usize>> = calls
        .iter()
        .map(|c| args(c)[9..].iter().map(|s| s.len()).collect())
        .collect();
    assert_eq!(lists, [vec![5], vec![GREP_ARGS_MAX + 10], vec![5]]);
}

/// Every shape: `--` present, only reading subcommands, no option that
/// writes, and nothing from a path before the `--`.
#[test]
fn no_shape_writes_and_paths_follow_the_double_dash() {
    let ps = paths(&[b"--output=x", b"HEAD", b"a.txt"]);
    let refs: Vec<&Path> = ps.iter().collect();
    let shapes = [
        Shape::Top,
        Shape::Head,
        Shape::Status,
        Shape::Counts(Base::Head),
        Shape::NewFileCounts(&refs),
        Shape::Diff(Base::Head, &ps[0]),
        Shape::Diff(Base::EmptyTree(ObjectFormat::Sha1), &ps[1]),
        Shape::NewFile(&ps[0]),
    ];
    for s in shapes {
        for c in build(s, TOP) {
            let a = args(&c);
            assert!(
                ["rev-parse", "status", "diff", "grep"].contains(&a[0]),
                "{s:?}"
            );
            let dd = a.iter().position(|x| *x == "--").expect("--");
            for opt in &a[..dd] {
                assert!(!opt.starts_with("--output"), "{s:?}: {opt}");
                assert!(
                    ![
                        "-o",
                        "--write",
                        "-w",
                        "--fix",
                        "--refresh",
                        "--open-files-in-pager",
                        "-O"
                    ]
                    .contains(opt),
                    "{s:?}: {opt}"
                );
            }
            for p in ["--output=x", "a.txt"] {
                assert!(!a[..dd].contains(&p), "{s:?}: {p} before --");
            }
            assert_eq!(c.params()["env"]["GIT_OPTIONAL_LOCKS"], "0");
            assert_eq!(c.params()["cwd"], TOP);
            assert!(
                c.params()["line"].is_null(),
                "argv only, never a shell line"
            );
        }
    }
}
