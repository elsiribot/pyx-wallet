use std::any::{Any, TypeId};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

use super::error::AndroidError;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum HandleKind {
    Database,
    Factory,
    Client,
    Parsed,
    Quote,
    Subscription,
    Request,
    Encoder,
    Decoder,
}

#[derive(Debug)]
struct Entry {
    generation: u32,
    kind: HandleKind,
    type_id: TypeId,
    value: Option<Arc<dyn Any + Send + Sync>>,
}

/// Registry for opaque JNI `Long` values.
///
/// The upper 32 bits are a generation and the lower 32 bits are a slot id.
/// Closing is idempotent until a slot is reused; after reuse, the old handle is
/// stale and cannot address the new value (the ABA case).
#[derive(Default, Debug)]
pub(crate) struct HandleRegistry {
    entries: HashMap<u32, Entry>,
    next_slot: u32,
}

static REGISTRY: OnceLock<Mutex<HandleRegistry>> = OnceLock::new();

fn global() -> &'static Mutex<HandleRegistry> {
    REGISTRY.get_or_init(|| Mutex::new(HandleRegistry::default()))
}

pub(crate) fn global_insert<T>(kind: HandleKind, value: T) -> Result<u64, AndroidError>
where
    T: Any + Send + Sync,
{
    global()
        .lock()
        .map_err(|_| AndroidError::internal())
        .map(|mut registry| registry.insert(kind, value))
}

pub(crate) fn global_get<T>(handle: u64, kind: HandleKind) -> Result<Arc<T>, AndroidError>
where
    T: Any + Send + Sync,
{
    global()
        .lock()
        .map_err(|_| AndroidError::internal())?
        .get(handle, kind)
}

pub(crate) fn global_close(handle: u64, kind: HandleKind) -> Result<(), AndroidError> {
    global()
        .lock()
        .map_err(|_| AndroidError::internal())?
        .close(handle, kind)
}

pub(crate) fn global_validate_type<T>(handle: u64, kind: HandleKind) -> Result<(), AndroidError>
where
    T: Any + Send + Sync,
{
    global()
        .lock()
        .map_err(|_| AndroidError::internal())?
        .validate_type::<T>(handle, kind)
}

pub(crate) fn global_handles(kind: HandleKind) -> Result<Vec<u64>, AndroidError> {
    global()
        .lock()
        .map_err(|_| AndroidError::internal())
        .map(|registry| registry.handles(kind))
}

pub(crate) fn global_close_all(kind: HandleKind) -> Result<(), AndroidError> {
    let handles = global_handles(kind)?;
    for handle in handles {
        let _ = global_close(handle, kind);
    }
    Ok(())
}

impl HandleRegistry {
    fn handles(&self, kind: HandleKind) -> Vec<u64> {
        self.entries
            .iter()
            .filter_map(|(slot, entry)| {
                (entry.kind == kind && entry.value.is_some())
                    .then_some(encode(*slot, entry.generation))
            })
            .collect()
    }
    pub(crate) fn insert<T>(&mut self, kind: HandleKind, value: T) -> u64
    where
        T: Any + Send + Sync,
    {
        let slot = self.find_slot();
        let entry = self.entries.entry(slot).or_insert_with(|| Entry {
            generation: 0,
            kind,
            type_id: TypeId::of::<T>(),
            value: None,
        });
        entry.generation = entry.generation.wrapping_add(1).max(1);
        entry.kind = kind;
        entry.type_id = TypeId::of::<T>();
        entry.value = Some(Arc::new(value));
        encode(slot, entry.generation)
    }

    pub(crate) fn get<T>(&self, handle: u64, expected: HandleKind) -> Result<Arc<T>, AndroidError>
    where
        T: Any + Send + Sync,
    {
        let (slot, generation) = decode(handle).ok_or_else(AndroidError::invalid_handle)?;
        let entry = self
            .entries
            .get(&slot)
            .ok_or_else(AndroidError::invalid_handle)?;
        if entry.generation != generation || entry.value.is_none() {
            return Err(AndroidError::invalid_handle());
        }
        if entry.kind != expected {
            return Err(AndroidError::wrong_handle_type());
        }

        Arc::downcast(entry.value.as_ref().expect("checked above").clone())
            .map_err(|_| AndroidError::wrong_handle_type())
    }

    pub(crate) fn close(&mut self, handle: u64, expected: HandleKind) -> Result<(), AndroidError> {
        let (slot, generation) = decode(handle).ok_or_else(AndroidError::invalid_handle)?;
        let entry = self
            .entries
            .get_mut(&slot)
            .ok_or_else(AndroidError::invalid_handle)?;
        if entry.generation != generation {
            return Err(AndroidError::invalid_handle());
        }
        if entry.kind != expected {
            return Err(AndroidError::wrong_handle_type());
        }
        entry.value = None;
        Ok(())
    }

    pub(crate) fn validate_type<T>(
        &self,
        handle: u64,
        expected: HandleKind,
    ) -> Result<(), AndroidError>
    where
        T: Any + Send + Sync,
    {
        let (slot, generation) = decode(handle).ok_or_else(AndroidError::invalid_handle)?;
        let entry = self
            .entries
            .get(&slot)
            .ok_or_else(AndroidError::invalid_handle)?;
        if entry.generation != generation {
            return Err(AndroidError::invalid_handle());
        }
        if entry.kind != expected || entry.type_id != TypeId::of::<T>() {
            return Err(AndroidError::wrong_handle_type());
        }
        Ok(())
    }

    fn find_slot(&mut self) -> u32 {
        if let Some((&slot, _)) = self.entries.iter().find(|(_, entry)| entry.value.is_none()) {
            return slot;
        }

        loop {
            self.next_slot = self.next_slot.wrapping_add(1).max(1);
            if !self.entries.contains_key(&self.next_slot) {
                return self.next_slot;
            }
        }
    }
}

fn encode(slot: u32, generation: u32) -> u64 {
    (u64::from(generation) << 32) | u64::from(slot)
}

fn decode(handle: u64) -> Option<(u32, u32)> {
    let slot = handle as u32;
    let generation = (handle >> 32) as u32;
    (slot != 0 && generation != 0).then_some((slot, generation))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn retrieves_only_the_expected_type() {
        let mut registry = HandleRegistry::default();
        let handle = registry.insert(HandleKind::Database, String::from("db"));

        assert_eq!(
            &*registry
                .get::<String>(handle, HandleKind::Database)
                .unwrap(),
            "db"
        );
        assert_eq!(
            registry
                .get::<String>(handle, HandleKind::Client)
                .unwrap_err()
                .code,
            super::super::error::AndroidErrorCode::WrongHandleType
        );
        assert_eq!(
            registry
                .get::<u64>(handle, HandleKind::Database)
                .unwrap_err()
                .code,
            super::super::error::AndroidErrorCode::WrongHandleType
        );
    }

    #[test]
    fn close_is_idempotent_but_reused_slots_reject_stale_handles() {
        let mut registry = HandleRegistry::default();
        let old = registry.insert(HandleKind::Client, 1_u64);
        registry.close(old, HandleKind::Client).unwrap();
        registry.close(old, HandleKind::Client).unwrap();

        let current = registry.insert(HandleKind::Client, 2_u64);
        assert_ne!(old, current);
        assert_eq!(
            registry
                .get::<u64>(old, HandleKind::Client)
                .unwrap_err()
                .code,
            super::super::error::AndroidErrorCode::InvalidHandle
        );
        assert_eq!(
            *registry.get::<u64>(current, HandleKind::Client).unwrap(),
            2
        );
    }

    #[test]
    fn zero_and_unknown_handles_are_invalid() {
        let registry = HandleRegistry::default();
        assert!(registry.get::<u8>(0, HandleKind::Parsed).is_err());
        assert!(
            registry
                .get::<u8>((1_u64 << 32) | 99, HandleKind::Parsed)
                .is_err()
        );
    }

    #[test]
    fn one_hundred_session_graph_cycles_are_generation_safe_and_idempotent() {
        let mut registry = HandleRegistry::default();
        let mut stale = Vec::new();
        for cycle in 0..100_u64 {
            let database = registry.insert(HandleKind::Database, cycle);
            let factory = registry.insert(HandleKind::Factory, cycle);
            let client = registry.insert(HandleKind::Client, cycle);
            let subscription = registry.insert(HandleKind::Subscription, cycle);
            let request = registry.insert(HandleKind::Request, cycle);
            let quote = registry.insert(HandleKind::Quote, cycle);
            for (handle, kind) in [
                (subscription, HandleKind::Subscription),
                (request, HandleKind::Request),
                (quote, HandleKind::Quote),
                (client, HandleKind::Client),
                (factory, HandleKind::Factory),
                (database, HandleKind::Database),
            ] {
                registry.close(handle, kind).unwrap();
                registry.close(handle, kind).unwrap();
                stale.push((handle, kind));
            }
        }
        let current = registry.insert(HandleKind::Database, 101_u64);
        assert!(
            stale
                .into_iter()
                .all(|(handle, kind)| registry.get::<u64>(handle, kind).is_err())
        );
        assert_eq!(
            *registry.get::<u64>(current, HandleKind::Database).unwrap(),
            101
        );
    }
}
