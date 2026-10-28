#![allow(non_snake_case)]

use crate::error::{ErrorKind, HatchError, HatchResult};
use crate::model::{Entry, MODE_RECLAIM};
use crate::open::OpenOptions;
use crate::session::Session;
use crate::sink::{EntryOutput, Sink};
use jni::objects::{JClass, JIntArray, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use std::collections::HashMap;
use std::fs::File;
use std::os::fd::FromRawFd;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

static REGISTRY: OnceLock<Mutex<HashMap<i64, Arc<Session>>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);

fn registry() -> &'static Mutex<HashMap<i64, Arc<Session>>> {
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

fn get_session(handle: i64) -> Option<Arc<Session>> {
    registry().lock().ok()?.get(&handle).cloned()
}

fn throw(env: &mut JNIEnv, message: impl AsRef<str>) {
    if !env.exception_check().unwrap_or(false) {
        let _ = env.throw_new("java/io/IOException", message.as_ref());
    }
}

fn throw_hatch(env: &mut JNIEnv, err: &HatchError) {
    throw(env, err.wire());
}

fn read_string(env: &mut JNIEnv, value: &JString) -> HatchResult<String> {
    env.get_string(value)
        .map(|s| s.into())
        .map_err(|_| HatchError::new(ErrorKind::Internal, "Could not read a text argument."))
}

fn optional_string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    if value.is_null() {
        None
    } else {
        env.get_string(value).ok().map(|s| String::from(s))
    }
}

fn read_indices(env: &mut JNIEnv, array: &JIntArray) -> HatchResult<Vec<i32>> {
    let len = env
        .get_array_length(array)
        .map_err(|_| HatchError::new(ErrorKind::Internal, "Could not read selection."))?;
    if len > 2_000_000 {
        return Err(HatchError::new(ErrorKind::Internal, "Selection is too large."));
    }
    let mut buf = vec![0i32; len as usize];
    env.get_int_array_region(array, 0, &mut buf)
        .map_err(|_| HatchError::new(ErrorKind::Internal, "Could not read selection."))?;
    Ok(buf)
}

struct JniSink<'a, 'local> {
    env: &'a mut JNIEnv<'local>,
    obj: JObject<'local>,
}

impl<'a, 'local> JniSink<'a, 'local> {
    fn java_failure(&self) -> HatchError {
        HatchError::new(ErrorKind::Cancelled, "The destination could not be written.")
    }
}

impl Sink for JniSink<'_, '_> {
    fn open_entry(&mut self, entry: &Entry) -> HatchResult<EntryOutput> {
        let jpath = self
            .env
            .new_string(&entry.path)
            .map_err(|_| HatchError::new(ErrorKind::Internal, "Could not create a path string."))?;
        let args = [
            JValue::Int(entry.index as jint),
            JValue::Object(&jpath),
            JValue::Bool(entry.is_directory as jboolean),
        ];
        let result = match self
            .env
            .call_method(&self.obj, "onEntryOpen", "(ILjava/lang/String;Z)I", &args)
        {
            Ok(v) => v,
            Err(_) => return Err(self.java_failure()),
        };
        let fd = result.i().unwrap_or(-3);
        match fd {
            -1 => Ok(EntryOutput::Directory),
            -2 => Ok(EntryOutput::Skip),
            x if x >= 0 => {
                // SAFETY: Kotlin detaches the ParcelFileDescriptor exactly
                // once and hands ownership to this native call.
                let file = unsafe { File::from_raw_fd(x) };
                Ok(EntryOutput::File(file))
            }
            _ => Err(HatchError::new(
                ErrorKind::Io,
                "The destination provider refused to create a file.",
            )),
        }
    }

    fn progress(&mut self, index: usize, written: u64, total: u64) -> HatchResult<bool> {
        let args = [
            JValue::Int(index as jint),
            JValue::Long(written as jlong),
            JValue::Long(total as jlong),
        ];
        let value = match self.env.call_method(
            &self.obj,
            "onEntryProgress",
            "(IJJ)Z",
            &args,
        ) {
            Ok(v) => v,
            Err(_) => return Ok(false),
        };
        Ok(value.z().unwrap_or(false))
    }

    fn committed(&mut self, index: usize, written: u64) -> HatchResult<bool> {
        let args = [JValue::Int(index as jint), JValue::Long(written as jlong)];
        let value = match self.env.call_method(
            &self.obj,
            "onEntryCommitted",
            "(IJ)Z",
            &args,
        ) {
            Ok(v) => v,
            Err(_) => return Ok(false),
        };
        Ok(value.z().unwrap_or(false))
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hatchstudio_archive_core_NativeEngine_nativeOpen(
    mut env: JNIEnv,
    _class: JClass,
    fd: jint,
    path: JString,
    display_name: JString,
    password: JString,
    temp_dir: JString,
    plan_path: JString,
    mode: jint,
    multipart: jboolean,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| -> HatchResult<Arc<Session>> {
        if fd < 0 {
            return Err(HatchError::new(ErrorKind::Internal, "No archive file descriptor."));
        }
        // Take ownership immediately so every early return closes the fd.
        let file = unsafe { File::from_raw_fd(fd) };
        let display = read_string(&mut env, &display_name)?;
        let pwd = optional_string(&mut env, &password);
        let temp = read_string(&mut env, &temp_dir)?;
        let plan = optional_string(&mut env, &plan_path).map(PathBuf::from);
        let staged = optional_string(&mut env, &path).map(PathBuf::from);
        let rar_path = staged.unwrap_or_else(|| PathBuf::from(format!("/proc/self/fd/{fd}")));
        let options = OpenOptions {
            display_name: display,
            password: pwd,
            temp_dir: PathBuf::from(temp),
            rar_path,
            multipart: multipart != 0,
            mode,
            plan_path: plan,
        };
        let id = NEXT.fetch_add(1, Ordering::SeqCst);
        let session = Session::open(id, file, options)?;
        registry()
            .lock()
            .map_err(|_| HatchError::new(ErrorKind::Internal, "Session registry was locked."))?
            .insert(id, Arc::new(session));
        Ok(get_session(id).unwrap())
    }));
    match result {
        Ok(Ok(session)) => session.id as jlong,
        Ok(Err(err)) => {
            throw_hatch(&mut env, &err);
            0
        }
        Err(_) => {
            throw(&mut env, "E_INTERNAL|The archive engine stopped unexpectedly.");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hatchstudio_archive_core_NativeEngine_nativeClose(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut map) = registry().lock() {
            map.remove(&handle);
        }
    }));
    let _ = env;
}

#[no_mangle]
pub extern "system" fn Java_com_hatchstudio_archive_core_NativeEngine_nativeEntries(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> HatchResult<String> {
        let session = get_session(handle)
            .ok_or_else(|| HatchError::new(ErrorKind::Internal, "Archive session is closed."))?;
        session.entries_json()
    }));
    match result {
        Ok(Ok(json)) => env
            .new_string(json)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Ok(Err(err)) => {
            throw_hatch(&mut env, &err);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(&mut env, "E_INTERNAL|The archive engine stopped unexpectedly.");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hatchstudio_archive_core_NativeEngine_nativeInfo(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> HatchResult<String> {
        let session = get_session(handle)
            .ok_or_else(|| HatchError::new(ErrorKind::Internal, "Archive session is closed."))?;
        session.info_json()
    }));
    match result {
        Ok(Ok(json)) => env
            .new_string(json)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Ok(Err(err)) => {
            throw_hatch(&mut env, &err);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(&mut env, "E_INTERNAL|The archive engine stopped unexpectedly.");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hatchstudio_archive_core_NativeEngine_nativePause(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    paused: jboolean,
) {
    if let Some(session) = get_session(handle) {
        session.control.set_paused(paused != 0);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hatchstudio_archive_core_NativeEngine_nativeCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(session) = get_session(handle) {
        session.control.set_cancelled(true);
        session.control.set_paused(false);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hatchstudio_archive_core_NativeEngine_nativeExtract<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    selected: JIntArray<'local>,
    completed: JIntArray<'local>,
    password: JString<'local>,
    sink: JObject<'local>,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| -> HatchResult<jint> {
        let session = get_session(handle)
            .ok_or_else(|| HatchError::new(ErrorKind::Internal, "Archive session is closed."))?;
        let selected_indices = read_indices(&mut env, &selected)?;
        let completed_indices = read_indices(&mut env, &completed)?;
        let pwd = optional_string(&mut env, &password);
        let entries = session.entries()?;
        let mut selected_map = vec![false; entries.len()];
        let mut completed_map = vec![false; entries.len()];
        for index in selected_indices {
            if index >= 0 && (index as usize) < entries.len() {
                selected_map[index as usize] = true;
            }
        }
        for index in completed_indices {
            if index >= 0 && (index as usize) < entries.len() {
                completed_map[index as usize] = true;
            }
        }
        let mut jni_sink = JniSink { env: &mut env, obj: sink };
        match session.extract(&selected_map, &completed_map, pwd.as_deref(), &mut jni_sink) {
            Ok(()) => Ok(0),
            Err(err) if err.kind == ErrorKind::Cancelled => Ok(2),
            Err(err) => Err(err),
        }
    }));
    match result {
        Ok(Ok(code)) => code,
        Ok(Err(err)) => {
            throw_hatch(&mut env, &err);
            -1
        }
        Err(_) => {
            throw(&mut env, "E_INTERNAL|The archive engine stopped unexpectedly.");
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hatchstudio_archive_core_NativeEngine_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let version = format!(
        "hatch-core {} (zip {}, 7z, tar, rar)",
        env!("CARGO_PKG_VERSION"),
        "9"
    );
    env.new_string(version)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

const _: i32 = MODE_RECLAIM;

/// Keeps a compilation-time check for the reclaim mode constant used by Kotlin.
#[allow(dead_code)]
fn reclaim_mode() -> i32 {
    MODE_RECLAIM
}
