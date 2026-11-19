//! Phone-only cache of recently viewed chats.

/// Chats held for the terminals viewed most recently.
pub(crate) const HELD: usize = 8;

/// The chats held, most recently viewed last (design D12).
pub(crate) struct Held<T> {
    order: Vec<(String, T)>,
}

impl<T: Clone> Held<T> {
    pub fn new() -> Self {
        Held { order: Vec::new() }
    }

    /// The chat for `key`, made when not held; it becomes the most recent,
    /// and the least recent past [`HELD`] is dropped.
    pub fn open(&mut self, key: &str, make: impl FnOnce() -> T) -> T {
        let v = match self.order.iter().position(|(k, _)| k == key) {
            Some(i) => self.order.remove(i).1,
            None => make(),
        };
        self.order.push((key.to_owned(), v.clone()));
        if self.order.len() > HELD {
            self.order.remove(0);
        }
        v
    }

    /// Android asked to free memory: keep only the one on screen.
    pub fn trim(&mut self, shown: Option<&str>) {
        self.order.retain(|(k, _)| Some(k.as_str()) == shown);
    }

    #[cfg(test)]
    pub fn keys(&self) -> Vec<String> {
        self.order.iter().map(|(k, _)| k.clone()).collect()
    }
}

#[cfg(test)]
#[path = "chat_core_tests.rs"]
mod tests;
