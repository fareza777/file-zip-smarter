/// Turns a name from an archive into a relative path that cannot escape the
/// destination folder. Returns `None` for names that cannot be made safe.
pub fn sanitize_entry_path(raw: &str) -> Option<String> {
    if raw.is_empty() {
        return None;
    }
    // NUL and control characters break document providers and shell tools.
    if raw.chars().any(|c| c == '\0' || (c.is_control() && c != '\t')) {
        return None;
    }

    if raw.starts_with('/') || raw.starts_with('\\') {
        return None;
    }
    let normalized = raw.replace('\\', "/");
    let mut out: Vec<String> = Vec::new();
    for part in normalized.split('/') {
        if part.is_empty() || part == "." {
            continue;
        }
        if part == ".." {
            return None;
        }
        // Absolute paths, UNC paths and drive letters.
        if part.contains(':') && out.is_empty() {
            return None;
        }
        let mut segment = part.to_string();
        while segment.ends_with(' ') || segment.ends_with('.') {
            segment.pop();
        }
        if segment.is_empty() {
            continue;
        }
        if segment.len() > 255 {
            return None;
        }
        if is_windows_reserved(&segment) {
            segment = format!("_{segment}");
        }
        out.push(segment);
    }

    if out.is_empty() {
        return None;
    }
    let joined = out.join("/");
    if joined.len() > 3800 {
        return None;
    }
    Some(joined)
}

fn is_windows_reserved(segment: &str) -> bool {
    let stem = segment
        .split('.')
        .next()
        .unwrap_or(segment)
        .trim()
        .to_ascii_uppercase();
    matches!(
        stem.as_str(),
        "CON" | "PRN" | "AUX" | "NUL" | "COM1" | "COM2" | "COM3" | "COM4" | "COM5"
            | "COM6" | "COM7" | "COM8" | "COM9" | "LPT1" | "LPT2" | "LPT3" | "LPT4"
            | "LPT5" | "LPT6" | "LPT7" | "LPT8" | "LPT9"
    )
}

pub fn parent_path(path: &str) -> Option<&str> {
    path.rfind('/').map(|i| &path[..i])
}

pub fn file_name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}

/// Makes a name usable as a single SAF document display name.
pub fn safe_display_name(name: &str) -> String {
    let base = name.rsplit('/').next().unwrap_or(name).replace('\\', "_");
    let cleaned: String = base
        .chars()
        .map(|c| if c.is_control() || c == '/' { '_' } else { c })
        .collect();
    let trimmed = cleaned.trim().trim_matches('.').to_string();
    if trimmed.is_empty() {
        "untitled".to_string()
    } else if trimmed.len() > 200 {
        trimmed.chars().take(200).collect()
    } else {
        trimmed
    }
}

/// Adds ` (n)` before the extension when a document with that name exists.
pub fn unique_name(name: &str, attempt: u32) -> String {
    if attempt == 0 {
        return name.to_string();
    }
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 && i + 1 < name.len() => (&name[..i], &name[i..]),
        _ => (name, ""),
    };
    format!("{stem} ({attempt}){ext}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_traversal() {
        assert!(sanitize_entry_path("../etc/passwd").is_none());
        assert!(sanitize_entry_path("a/../../b").is_none());
        assert!(sanitize_entry_path("/etc/passwd").is_none());
        assert!(sanitize_entry_path("C:\\Windows\\win.ini").is_none());
        assert!(sanitize_entry_path("").is_none());
        assert!(sanitize_entry_path("./").is_none());
    }

    #[test]
    fn normalizes_relative_names() {
        assert_eq!(sanitize_entry_path("./photos/a.jpg").unwrap(), "photos/a.jpg");
        assert_eq!(sanitize_entry_path("a\\b\\c.txt").unwrap(), "a/b/c.txt");
        assert_eq!(sanitize_entry_path("one//two/").unwrap(), "one/two");
    }

    #[test]
    fn handles_reserved_and_odd_names() {
        assert_eq!(sanitize_entry_path("CON.txt").unwrap(), "_CON.txt");
        assert_eq!(sanitize_entry_path("space.txt ").unwrap(), "space.txt");
        assert_eq!(unique_name("movie.mkv", 2), "movie (2).mkv");
        assert_eq!(unique_name("README", 1), "README (1)");
    }
}
