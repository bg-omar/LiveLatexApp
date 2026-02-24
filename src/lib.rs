use jni::objects::{JClass, JString};
use jni::sys::jboolean;
use jni::JNIEnv;
use std::fs::File;
use std::io::Write;

/// JNI entry: compile LaTeX source to PDF file.
/// Package must be com.omariskandarani.livelatexapp, class LatexCompiler.
#[no_mangle]
pub extern "system" fn Java_com_omariskandarani_livelatexapp_LatexCompiler_compilePdf(
    mut env: JNIEnv,
    _class: JClass,
    latex_src: JString,
    output_path: JString,
    _cache_dir: JString,
) -> jboolean {
    let result = (|| -> Result<(), Box<dyn std::error::Error>> {
        let latex: String = env.get_string(&latex_src)?.into();
        let out_path: String = env.get_string(&output_path)?.into();

        let pdf_bytes = tectonic::latex_to_pdf(&latex).map_err(|e| e.to_string())?;

        let mut f = File::create(&out_path)?;
        f.write_all(&pdf_bytes)?;
        Ok(())
    })();

    match result {
        Ok(()) => 1, // JNI true
        Err(_) => 0, // JNI false
    }
}
