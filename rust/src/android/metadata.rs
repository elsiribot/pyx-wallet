use serde::Serialize;

use crate::client::ConduitClient;
use crate::factory::ConduitClientFactory;
use crate::lnurl::{LnurlWrapper, parse_lnurl};

use super::error::{AndroidError, AndroidErrorCode};
use super::handles::{HandleKind, global_get};

const MAX_CONTACT_NAME_BYTES: usize = 128;
const MAX_LNURL_BYTES: usize = 64 * 1024;
const MAX_CONTACTS: usize = 256;
const MAX_GUARDIANS: usize = 256;
const MAX_GUARDIAN_NAME_CHARS: usize = 256;

#[derive(Serialize)]
struct ContactsDto {
    contacts: Vec<ContactDto>,
}

#[derive(Serialize)]
struct ContactDto {
    name: String,
    lnurl: String,
}

#[derive(Serialize)]
struct SavedDto {
    saved: bool,
}

#[derive(Serialize)]
struct DeletedDto {
    deleted: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ConnectionStatusDto {
    guardians: Vec<GuardianDto>,
    online_count: usize,
    total_count: usize,
    required_count: usize,
    state: &'static str,
}

#[derive(Serialize)]
struct GuardianDto {
    name: String,
    connected: bool,
}

pub(crate) async fn list_contacts_async(factory_handle: u64) -> Result<String, AndroidError> {
    let factory = factory(factory_handle)?;
    let contacts = factory
        .list_contacts()
        .await
        .into_iter()
        .filter_map(|contact| {
            let lnurl = contact.lnurl().encode();
            (lnurl.len() <= MAX_LNURL_BYTES).then(|| ContactDto {
                name: contact
                    .name()
                    .chars()
                    .take(MAX_CONTACT_NAME_BYTES)
                    .collect(),
                lnurl,
            })
        })
        .take(MAX_CONTACTS)
        .collect();
    serialize(&ContactsDto { contacts })
}

pub(crate) async fn save_contact_async(
    factory_handle: u64,
    lnurl: String,
    name: String,
) -> Result<String, AndroidError> {
    validate_name(&name)?;
    let lnurl = parse_contact_lnurl(&lnurl)?;
    let factory = factory(factory_handle)?;
    factory.save_contact(&lnurl, &name).await;
    serialize(&SavedDto { saved: true })
}

pub(crate) async fn delete_contact_async(
    factory_handle: u64,
    lnurl: String,
) -> Result<String, AndroidError> {
    let lnurl = parse_contact_lnurl(&lnurl)?;
    let factory = factory(factory_handle)?;
    factory.delete_contact(&lnurl).await;
    serialize(&DeletedDto { deleted: true })
}

pub(crate) async fn connection_status_async(client_handle: u64) -> Result<String, AndroidError> {
    let client = global_get::<ConduitClient>(client_handle, HandleKind::Client)?;
    let statuses = client
        .connection_status_snapshot()
        .await
        .ok_or_else(AndroidError::internal)?;
    let guardians = statuses
        .into_iter()
        .take(MAX_GUARDIANS)
        .map(|(name, connected)| GuardianDto {
            name: name.chars().take(MAX_GUARDIAN_NAME_CHARS).collect(),
            connected,
        })
        .collect::<Vec<_>>();
    let online_count = guardians
        .iter()
        .filter(|guardian| guardian.connected)
        .count();
    let total_count = guardians.len();
    let required_count = quorum(total_count)?;
    let state = connection_state(online_count, total_count)?;
    serialize(&ConnectionStatusDto {
        guardians,
        online_count,
        total_count,
        required_count,
        state,
    })
}

fn factory(handle: u64) -> Result<std::sync::Arc<ConduitClientFactory>, AndroidError> {
    global_get(handle, HandleKind::Factory)
}

fn parse_contact_lnurl(value: &str) -> Result<LnurlWrapper, AndroidError> {
    if value.is_empty() || value.len() > MAX_LNURL_BYTES {
        return Err(invalid_contact());
    }
    let lnurl = parse_lnurl(value).ok_or_else(invalid_contact)?;
    if lnurl.encode().len() > MAX_LNURL_BYTES {
        return Err(invalid_contact());
    }
    Ok(lnurl)
}

fn validate_name(name: &str) -> Result<(), AndroidError> {
    if name.len() > MAX_CONTACT_NAME_BYTES || name.trim().is_empty() {
        return Err(invalid_contact());
    }
    Ok(())
}

fn connection_state(online: usize, total: usize) -> Result<&'static str, AndroidError> {
    let required = quorum(total)?;
    if online > total {
        return Err(AndroidError::internal());
    }
    Ok(if online == 0 {
        "offline"
    } else if online == total {
        "connected"
    } else if online >= required {
        "degraded"
    } else {
        "offline"
    })
}

fn quorum(total: usize) -> Result<usize, AndroidError> {
    if total == 0 {
        return Err(AndroidError::internal());
    }
    Ok(total - (total - 1) / 3)
}

fn invalid_contact() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The contact is invalid.",
        false,
    )
}

fn serialize<T: Serialize>(value: &T) -> Result<String, AndroidError> {
    serde_json::to_string(value).map_err(|_| AndroidError::internal())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn contact_inputs_are_bounded() {
        assert!(validate_name("Alice").is_ok());
        assert!(validate_name("  ").is_err());
        assert!(validate_name(&"x".repeat(MAX_CONTACT_NAME_BYTES + 1)).is_err());
        assert!(parse_contact_lnurl("").is_err());
        assert!(parse_contact_lnurl("alice@example.com").is_ok());
        assert!(parse_contact_lnurl(&"x".repeat(MAX_LNURL_BYTES + 1)).is_err());
    }

    #[test]
    fn connection_states_cover_all_valid_snapshots() {
        assert_eq!(connection_state(0, 3).unwrap(), "offline");
        assert_eq!(connection_state(2, 3).unwrap(), "offline");
        assert_eq!(connection_state(1, 3).unwrap(), "offline");
        assert_eq!(connection_state(3, 3).unwrap(), "connected");
        assert_eq!(connection_state(3, 4).unwrap(), "degraded");
        assert!(connection_state(0, 0).is_err());
        assert!(connection_state(4, 3).is_err());
    }

    #[test]
    fn response_json_matches_contract() {
        let contacts = serialize(&ContactsDto {
            contacts: vec![ContactDto {
                name: "Alice".into(),
                lnurl: "LNURL1EXAMPLE".into(),
            }],
        })
        .unwrap();
        crate::android::assert_read_dto_snapshot("metadata.contacts", &contacts);
        assert_eq!(
            contacts,
            "{\"contacts\":[{\"name\":\"Alice\",\"lnurl\":\"LNURL1EXAMPLE\"}]}"
        );
        assert_eq!(
            serialize(&SavedDto { saved: true }).unwrap(),
            "{\"saved\":true}"
        );
        assert_eq!(
            serialize(&DeletedDto { deleted: true }).unwrap(),
            "{\"deleted\":true}"
        );
        let connection = serialize(&ConnectionStatusDto {
            guardians: vec![GuardianDto {
                name: "Guardian".into(),
                connected: false,
            }],
            online_count: 0,
            total_count: 1,
            required_count: 1,
            state: "offline",
        })
        .unwrap();
        crate::android::assert_read_dto_snapshot("metadata.connectionStatus", &connection);
        assert_eq!(
            connection,
            "{\"guardians\":[{\"name\":\"Guardian\",\"connected\":false}],\"onlineCount\":0,\"totalCount\":1,\"requiredCount\":1,\"state\":\"offline\"}"
        );
    }
}
