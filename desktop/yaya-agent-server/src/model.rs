//! 桌面模型后端。
//!
//! 默认 STUB（AGENTS.md R4）；`--features full-llama` 编译 llama.cpp FFI 全量推理。
//! FFI 面向的 llama.cpp API 版本同 AGENTS.md R4（b4100 前后），**UNVERIFIED**：
//! 参数/batch 结构按「前缀字段 + 尾部填充」布局规避 ABI 尺寸风险，但字段顺序
//! 与函数签名需在首次带源码编译时按 R4 流程登记确认。

pub const STUB_NOTICE: &str =
    "[STUB] 当前为桩实现，推理结果不可用（未编译 full-llama，cargo build --release --features full-llama）";

pub fn is_stub() -> bool {
    !cfg!(feature = "full-llama")
}

/// 流式生成：每产生一段文本回调一次 `on_token`；回调返回 Err 则中止。
/// STUB 时只回调一次 STUB_NOTICE 后返回。
pub fn generate(
    model: Option<&str>,
    prompt: &str,
    max_tokens: u32,
    on_token: &mut dyn FnMut(&str) -> Result<(), String>,
) -> Result<(), String> {
    if is_stub() {
        return on_token(STUB_NOTICE);
    }
    #[cfg(feature = "full-llama")]
    {
        llama::generate(model, prompt, max_tokens, on_token)
    }
    #[cfg(not(feature = "full-llama"))]
    {
        let _ = (model, prompt, max_tokens, on_token);
        Ok(())
    }
}

#[cfg(feature = "full-llama")]
mod llama {
    use std::ffi::CString;
    use std::os::raw::{c_char, c_int};
    use std::ptr;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Mutex;

    type Tok = i32;
    type SeqId = i32;

    #[repr(C)]
    struct Model {
        _private: [u8; 0],
    }

    #[repr(C)]
    struct Ctx {
        _private: [u8; 0],
    }

    #[repr(C)]
    struct Sampler {
        _private: [u8; 0],
    }

    /// llama_model_params 的前缀 + 尾部填充：返回值缓冲只增不减（见模块注释）。
    #[repr(C)]
    #[derive(Clone, Copy)]
    struct ModelParams {
        _pad: [u64; 16],
    }

    /// llama_context_params 的前缀 + 尾部填充。
    #[repr(C)]
    #[derive(Clone, Copy)]
    struct ContextParams {
        _pad: [u64; 24],
    }

    /// llama_batch：n_tokens/token/embd/pos/n_seq_id/seq_id/logits 前缀 + 尾部填充。
    #[repr(C)]
    struct Batch {
        n_tokens: i32,
        token: *mut Tok,
        embd: *mut f32,
        pos: *mut i32,
        n_seq_id: *mut i32,
        seq_id: *mut *mut SeqId,
        logits: *mut i8,
        _pad: [u64; 16],
    }

    unsafe extern "C" {
        fn llama_backend_init(numa: bool);
        fn llama_version() -> c_int;
        fn llama_model_default_params() -> ModelParams;
        fn llama_context_default_params() -> ContextParams;
        fn llama_load_model_from_file(path: *const c_char, params: ModelParams) -> *mut Model;
        fn llama_free_model(model: *mut Model);
        fn llama_new_context_with_model(model: *mut Model, params: ContextParams) -> *mut Ctx;
        fn llama_free(ctx: *mut Ctx);
        fn llama_tokenize(
            model: *const Model,
            text: *const c_char,
            text_len: c_int,
            tokens: *mut Tok,
            n_tokens_max: c_int,
            add_bos: bool,
            parse_special: bool,
        ) -> c_int;
        fn llama_token_to_piece(
            model: *const Model,
            token: Tok,
            buf: *mut c_char,
            n_buf: c_int,
            lstrip: c_int,
            parse_special: bool,
        ) -> c_int;
        fn llama_token_is_eog(model: *const Model, token: Tok) -> bool;
        fn llama_batch_init(n_tokens: c_int, embd: *mut f32, n_seq: c_int) -> Batch;
        fn llama_batch_free(batch: Batch);
        fn llama_decode(ctx: *mut Ctx, batch: Batch) -> c_int;
        fn llama_sampler_init_greedy() -> *mut Sampler;
        fn llama_sampler_sample(sampler: *mut Sampler, ctx: *mut Ctx, idx: c_int) -> Tok;
        fn llama_sampler_accept(sampler: *mut Sampler, token: Tok) -> c_int;
        fn llama_sampler_free(sampler: *mut Sampler);
    }

    static INIT: AtomicBool = AtomicBool::new(false);
    static GEN_LOCK: Mutex<()> = Mutex::new(());
    static mut MODEL: *mut Model = ptr::null_mut();
    static mut MODEL_PATH: Option<String> = None;

    fn model_path(model: Option<&str>) -> Result<String, String> {
        model
            .filter(|s| !s.trim().is_empty())
            .map(|s| s.to_string())
            .or_else(|| std::env::var("YAYA_MODEL_PATH").ok())
            .filter(|s| !s.trim().is_empty())
            .ok_or_else(|| {
                "未指定模型：GenerateRequest.model 或环境变量 YAYA_MODEL_PATH".to_string()
            })
    }

    pub fn generate(
        model: Option<&str>,
        prompt: &str,
        max_tokens: u32,
        on_token: &mut dyn FnMut(&str) -> Result<(), String>,
    ) -> Result<(), String> {
        let _guard = GEN_LOCK.lock().map_err(|e| format!("生成锁失效: {e}"))?;
        if !INIT.swap(true, Ordering::SeqCst) {
            unsafe { llama_backend_init(false) };
        }
        let path = model_path(model)?;

        unsafe {
            if MODEL.is_null() || MODEL_PATH.as_deref() != Some(path.as_str()) {
                if !MODEL.is_null() {
                    llama_free_model(MODEL);
                    MODEL = ptr::null_mut();
                }
                let cpath = CString::new(path.as_str()).map_err(|_| "模型路径含非法字符".to_string())?;
                let params = llama_model_default_params();
                MODEL = llama_load_model_from_file(cpath.as_ptr(), params);
                if MODEL.is_null() {
                    return Err(format!("模型加载失败: {path}"));
                }
                MODEL_PATH = Some(path.clone());
                eprintln!("[model] loaded {path} (llama.cpp build {})", llama_version());
            }

            let ctx = llama_new_context_with_model(MODEL, llama_context_default_params());
            if ctx.is_null() {
                return Err("上下文创建失败".to_string());
            }
            let smpl = llama_sampler_init_greedy();

            let bytes = prompt.as_bytes();
            let need = llama_tokenize(
                MODEL,
                bytes.as_ptr() as *const c_char,
                bytes.len() as c_int,
                ptr::null_mut(),
                0,
                true,
                false,
            );
            if need <= 0 {
                llama_sampler_free(smpl);
                llama_free(ctx);
                return Err(format!("tokenize 失败 ({need})"));
            }
            let mut tokens = vec![0 as Tok; need as usize];
            llama_tokenize(
                MODEL,
                bytes.as_ptr() as *const c_char,
                bytes.len() as c_int,
                tokens.as_mut_ptr(),
                need,
                true,
                false,
            );

            let max_new = max_tokens.min(512) as c_int;
            let mut batch = llama_batch_init(need + max_new, ptr::null_mut(), 1);
            for i in 0..need {
                *batch.token.add(i as usize) = tokens[i as usize];
                *batch.pos.add(i as usize) = i;
                *batch.n_seq_id.add(i as usize) = 1;
                *(*batch.seq_id.add(i as usize)) = 0;
                *batch.logits.add(i as usize) = i == need - 1;
            }
            batch.n_tokens = need;

            let run = (|| -> Result<(), String> {
                let mut idx = need - 1;
                let mut buf = [0 as c_char; 512];
                for step in 0..max_new {
                    if llama_decode(ctx, std::ptr::read(&batch as *const Batch)) != 0 {
                        return Err(format!("llama_decode 失败（step {step}）"));
                    }
                    let t = llama_sampler_sample(smpl, ctx, idx);
                    llama_sampler_accept(smpl, t);
                    if llama_token_is_eog(MODEL, t) {
                        break;
                    }
                    let n = llama_token_to_piece(MODEL, t, buf.as_mut_ptr(), buf.len() as c_int, 0, true);
                    if n > 0 {
                        let s = std::str::from_utf8(&buf[..n as usize])
                            .map_err(|_| "token 解码非 UTF-8".to_string())?;
                        on_token(s)?;
                    }
                    idx = need + step;
                    batch.n_tokens = 1;
                    *batch.token.add(0) = t;
                    *batch.pos.add(0) = need + step;
                    *batch.n_seq_id.add(0) = 1;
                    *(*batch.seq_id.add(0)) = 0;
                    *batch.logits.add(0) = 1;
                }
                Ok(())
            })();

            llama_batch_free(batch);
            llama_sampler_free(smpl);
            llama_free(ctx);
            run
        }
    }
}
