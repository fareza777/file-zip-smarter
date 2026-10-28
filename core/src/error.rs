use std::fmt;
use std::io;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorKind {
    PasswordRequired,
    WrongPassword,
    Unsupported,
    Corrupt,
    Io,
    NoSpace,
    Cancelled,
    Unsafe,
    ReclaimUnavailable,
    Internal,
}

#[derive(Debug)]
pub struct HatchError {
    pub kind: ErrorKind,
    pub message: String,
    pub detail: Option<String>,
}

impl HatchError {
    pub fn new(kind: ErrorKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
            detail: None,
        }
    }

    pub fn with_detail(kind: ErrorKind, message: impl Into<String>, detail: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
            detail: Some(detail.into()),
        }
    }

    pub fn unsupported(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Unsupported, message)
    }

    pub fn corrupt(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Corrupt, message)
    }

    pub fn password_required() -> Self {
        Self::new(
            ErrorKind::PasswordRequired,
            "This archive is locked. Enter its password to continue.",
        )
    }

    pub fn wrong_password() -> Self {
        Self::new(
            ErrorKind::WrongPassword,
            "That password did not unlock the archive.",
        )
    }

    pub fn reclaim(reason: impl Into<String>) -> Self {
        Self::new(ErrorKind::ReclaimUnavailable, reason)
    }

    pub fn code(&self) -> &'static str {
        match self.kind {
            ErrorKind::PasswordRequired => "E_PASSWORD_REQUIRED",
            ErrorKind::WrongPassword => "E_WRONG_PASSWORD",
            ErrorKind::Unsupported => "E_UNSUPPORTED",
            ErrorKind::Corrupt => "E_CORRUPT",
            ErrorKind::Io => "E_IO",
            ErrorKind::NoSpace => "E_NO_SPACE",
            ErrorKind::Cancelled => "E_CANCELLED",
            ErrorKind::Unsafe => "E_UNSAFE",
            ErrorKind::ReclaimUnavailable => "E_RECLAIM_UNAVAILABLE",
            ErrorKind::Internal => "E_INTERNAL",
        }
    }

    /// Message wire format understood by Kotlin.
    pub fn wire(&self) -> String {
        match &self.detail {
            Some(d) => format!("{}|{}|{}", self.code(), self.message, d),
            None => format!("{}|{}", self.code(), self.message),
        }
    }
}

impl fmt::Display for HatchError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.code(), self.message)
    }
}

impl std::error::Error for HatchError {}

impl From<io::Error> for HatchError {
    fn from(value: io::Error) -> Self {
        let lower = value.to_string().to_lowercase();
        if value.raw_os_error() == Some(28)
            || lower.contains("no space left")
            || lower.contains("enospc")
        {
            return Self::with_detail(
                ErrorKind::NoSpace,
                "The destination ran out of space. Free some space, then resume.",
                value.to_string(),
            );
        }
        Self::with_detail(ErrorKind::Io, "The file could not be read or written.", value.to_string())
    }
}

impl From<zip::result::ZipError> for HatchError {
    fn from(value: zip::result::ZipError) -> Self {
        use zip::result::ZipError;
        match value {
            ZipError::InvalidPassword => Self::wrong_password(),
            ZipError::UnsupportedArchive(reason) => Self::unsupported(format!(
                "This ZIP uses a feature Hatch does not support yet ({reason})."
            )),
            ZipError::FileNotFound => Self::corrupt("A file listed in the archive is missing."),
            ZipError::Io(err) => Self::from(err),
            other => Self::with_detail(
                ErrorKind::Corrupt,
                "The ZIP archive is damaged or incomplete.",
                other.to_string(),
            ),
        }
    }
}

impl From<sevenz_rust2::Error> for HatchError {
    fn from(value: sevenz_rust2::Error) -> Self {
        let lower = value.to_string().to_lowercase();
        if lower.contains("password") || lower.contains("aes") {
            if lower.contains("wrong") || lower.contains("crc") || lower.contains("check") {
                return Self::wrong_password();
            }
            return Self::password_required();
        }
        Self::with_detail(
            ErrorKind::Corrupt,
            "The 7z archive could not be decoded.",
            value.to_string(),
        )
    }
}

impl From<unrar::error::UnrarError> for HatchError {
    fn from(value: unrar::error::UnrarError) -> Self {
        let lower = value.to_string().to_lowercase();
        if lower.contains("password") || lower.contains("encrypted") || lower.contains("crypt") {
            if lower.contains("wrong") || lower.contains("crc") {
                return Self::wrong_password();
            }
            return Self::password_required();
        }
        Self::with_detail(
            ErrorKind::Corrupt,
            "The RAR archive could not be read.",
            value.to_string(),
        )
    }
}

impl From<std::string::FromUtf8Error> for HatchError {
    fn from(value: std::string::FromUtf8Error) -> Self {
        Self::with_detail(ErrorKind::Corrupt, "An entry name is not valid text.", value.to_string())
    }
}

pub type HatchResult<T> = Result<T, HatchError>;
