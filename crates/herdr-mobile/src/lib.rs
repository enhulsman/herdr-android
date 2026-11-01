uniffi::setup_scaffolding!();

/// The core's crate version, shown by the app to prove the bridge works.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn core_version_matches_the_repository_version_file() {
        assert_eq!(core_version(), include_str!("../../../VERSION").trim());
    }
}
