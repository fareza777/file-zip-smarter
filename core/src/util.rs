use crate::error::{ErrorKind, HatchError, HatchResult};
use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub fn zip_time_millis(dt: zip::DateTime) -> i64 {
    // DateTime is local time; encode without pulling in a timezone database.
    let (year, month, day) = (dt.year() as i64, dt.month() as i64, dt.day() as i64);
    let (hour, minute, second) = (dt.hour() as i64, dt.minute() as i64, dt.second() as i64);
    let days = days_from_civil(year, month, day);
    (days * 86_400 + hour * 3600 + minute * 60 + second) * 1000
}

pub fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = (month + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

pub fn time_millis_from_dos(dos_time: u32) -> i64 {
    let seconds = ((dos_time & 0x1f) as i64) * 2;
    let minutes = ((dos_time >> 5) & 0x3f) as i64;
    let hours = ((dos_time >> 11) & 0x1f) as i64;
    let day = ((dos_time >> 16) & 0x1f) as i64;
    let month = ((dos_time >> 21) & 0x0f) as i64;
    let year = (((dos_time >> 25) & 0x7f) as i64) + 1980;
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        return 0;
    }
    let days = days_from_civil(year, month, day);
    (days * 86_400 + hours * 3600 + minutes * 60 + seconds) * 1000
}

pub fn format_size(bytes: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1000.0 && unit < UNITS.len() - 1 {
        value /= 1000.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{} {}", bytes, UNITS[unit])
    } else if value >= 100.0 {
        format!("{value:.0} {}", UNITS[unit])
    } else if value >= 10.0 {
        format!("{value:.1} {}", UNITS[unit])
    } else {
        format!("{value:.2} {}", UNITS[unit])
    }
}

#[derive(Debug, Default)]
pub struct Control {
    paused: AtomicBool,
    cancelled: AtomicBool,
}

impl Control {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn set_paused(&self, value: bool) {
        self.paused.store(value, Ordering::SeqCst);
    }

    pub fn is_paused(&self) -> bool {
        self.paused.load(Ordering::SeqCst)
    }

    pub fn set_cancelled(&self, value: bool) {
        self.cancelled.store(value, Ordering::SeqCst);
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::SeqCst)
    }

    pub fn reset(&self) {
        self.paused.store(false, Ordering::SeqCst);
        self.cancelled.store(false, Ordering::SeqCst);
    }

    /// Blocks while paused. `tick` is called regularly so the UI can show a
    /// paused progress state and cancellation stays responsive.
    pub fn wait_if_paused(&self, mut tick: impl FnMut() -> HatchResult<bool>) -> HatchResult<()> {
        if !self.is_paused() {
            if self.is_cancelled() {
                return Err(HatchError::new(ErrorKind::Cancelled, "Extraction cancelled."));
            }
            return Ok(());
        }
        let mut last_tick = Instant::now() - Duration::from_secs(2);
        loop {
            if self.is_cancelled() {
                return Err(HatchError::new(ErrorKind::Cancelled, "Extraction cancelled."));
            }
            if !self.is_paused() {
                return Ok(());
            }
            if last_tick.elapsed() >= Duration::from_millis(400) {
                last_tick = Instant::now();
                if !tick()? {
                    self.set_cancelled(true);
                    return Err(HatchError::new(ErrorKind::Cancelled, "Extraction cancelled."));
                }
            }
            std::thread::sleep(Duration::from_millis(80));
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StopReason {
    Paused,
    Cancelled,
}

impl From<StopReason> for i32 {
    fn from(value: StopReason) -> Self {
        match value {
            StopReason::Paused => 1,
            StopReason::Cancelled => 2,
        }
    }
}

pub fn file_len(file: &File) -> HatchResult<u64> {
    Ok(file.metadata()?.len())
}

pub fn read_at(file: &mut File, offset: u64, buf: &mut [u8]) -> HatchResult<usize> {
    file.seek(SeekFrom::Start(offset))?;
    let mut read = 0;
    while read < buf.len() {
        let n = file.read(&mut buf[read..])?;
        if n == 0 {
            break;
        }
        read += n;
    }
    Ok(read)
}

pub fn copy_with_progress<R: Read + ?Sized>(
    reader: &mut R,
    writer: &mut File,
    total: u64,
    mut tick: impl FnMut(u64) -> HatchResult<bool>,
    control: &Control,
) -> HatchResult<u64> {
    let mut buf = vec![0u8; 1024 * 1024];
    let mut written = 0u64;
    let mut last_tick = Instant::now() - Duration::from_secs(2);
    loop {
        control.wait_if_paused(|| tick(written))?;
        if control.is_cancelled() {
            return Err(HatchError::new(ErrorKind::Cancelled, "Extraction cancelled."));
        }
        let n = reader.read(&mut buf)?;
        if n == 0 {
            break;
        }
        writer.write_all(&buf[..n])?;
        written += n as u64;
        if last_tick.elapsed() >= Duration::from_millis(220) {
            last_tick = Instant::now();
            if !tick(written)? {
                control.set_cancelled(true);
                return Err(HatchError::new(ErrorKind::Cancelled, "Extraction cancelled."));
            }
        }
        if total > 0 && written > total.saturating_add(1 << 20) {
            return Err(HatchError::corrupt(
                "An entry is larger than its header says; extraction stopped to protect storage.",
            ));
        }
    }
    if !tick(written)? {
        return Err(HatchError::new(ErrorKind::Cancelled, "Extraction cancelled."));
    }
    writer.flush()?;
    Ok(written)
}

pub fn copy_exact<R: Read + ?Sized>(
    reader: &mut R,
    writer: &mut File,
    expected: u64,
) -> io::Result<u64> {
    let mut buf = vec![0u8; 1024 * 1024];
    let mut written = 0u64;
    while written < expected {
        let want = (expected - written).min(buf.len() as u64) as usize;
        let n = reader.read(&mut buf[..want])?;
        if n == 0 {
            break;
        }
        writer.write_all(&buf[..n])?;
        written += n as u64;
    }
    writer.flush()?;
    Ok(written)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn size_formatting() {
        assert_eq!(format_size(999), "999 B");
        assert_eq!(format_size(1000), "1.00 KB");
        assert_eq!(format_size(1_500_000), "1.50 MB");
    }

    #[test]
    fn zip_time_roundtrip_is_sane() {
        let dt = zip::DateTime::from_date_and_time(2024, 6, 2, 12, 30, 0).unwrap();
        assert!(zip_time_millis(dt) > 1_700_000_000_000);
    }
}
