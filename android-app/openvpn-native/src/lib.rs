use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use openvpn_connect::{capabilities, platform, Config};

fn to_java_string(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_livrenet_android_OpenVpnNative_nativeCoreInfo(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let caps = capabilities();
    let info = format!(
        "OpenVPN 3 Core carregado | plataforma={} | tun_builder={} | external_tun={}",
        platform(),
        caps.tun_builder,
        caps.external_tun
    );
    to_java_string(&mut env, info)
}

#[no_mangle]
pub extern "system" fn Java_com_livrenet_android_OpenVpnNative_nativeValidateProfile(
    mut env: JNIEnv,
    _class: JClass,
    profile: JString,
) -> jstring {
    let profile: String = match env.get_string(&profile) {
        Ok(value) => value.into(),
        Err(error) => return to_java_string(&mut env, format!("ERRO: perfil inválido para leitura: {error}")),
    };

    if profile.trim().is_empty() {
        return to_java_string(&mut env, "ERRO: perfil OpenVPN vazio".to_string());
    }

    match openvpn_connect::evaluate_config(&Config::new(profile)) {
        Ok(evaluation) => to_java_string(
            &mut env,
            format!(
                "Perfil OpenVPN analisado pelo núcleo nativo | autenticação automática={}",
                evaluation.autologin
            ),
        ),
        Err(error) => to_java_string(&mut env, format!("ERRO DE PERFIL: {error}")),
    }
}
