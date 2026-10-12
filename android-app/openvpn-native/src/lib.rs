use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jint, jstring};
use jni::{JNIEnv, JavaVM};
use openvpn_connect::{Client, Config, Credentials, EventHandler, TunBuilder};
use std::sync::{Mutex, OnceLock};

static ACTIVE_CLIENT: OnceLock<Mutex<Option<Client>>> = OnceLock::new();

fn active_client() -> &'static Mutex<Option<Client>> {
    ACTIVE_CLIENT.get_or_init(|| Mutex::new(None))
}

fn java_string(env: &mut JNIEnv<'_>, value: impl Into<String>) -> jstring {
    env.new_string(value.into())
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

struct AndroidTun {
    vm: JavaVM,
    service: GlobalRef,
}

impl AndroidTun {
    fn call_bool(&self, name: &str, sig: &str, args: &[JValue<'_, '_>]) -> bool {
        let Ok(mut env) = self.vm.attach_current_thread() else { return false };
        env.call_method(self.service.as_obj(), name, sig, args)
            .and_then(|value| value.z())
            .unwrap_or(false)
    }

    fn call_int(&self, name: &str, sig: &str, args: &[JValue<'_, '_>]) -> i32 {
        let Ok(mut env) = self.vm.attach_current_thread() else { return -1 };
        env.call_method(self.service.as_obj(), name, sig, args)
            .and_then(|value| value.i())
            .unwrap_or(-1)
    }

    fn call_void(&self, name: &str, sig: &str, args: &[JValue<'_, '_>]) {
        if let Ok(mut env) = self.vm.attach_current_thread() {
            let _ = env.call_method(self.service.as_obj(), name, sig, args);
        }
    }

    fn call_status(&self, message: &str) {
        let Ok(mut env) = self.vm.attach_current_thread() else { return };
        let Ok(message) = env.new_string(message) else { return };
        let args = [JValue::Object(&message)];
        let _ = env.call_method(self.service.as_obj(), "nativeStatus", "(Ljava/lang/String;)V", &args);
    }

    fn protect_socket(&self, fd: i32) -> bool {
        let args = [JValue::Int(fd)];
        self.call_bool("nativeProtectSocket", "(I)Z", &args)
    }
}

impl TunBuilder for AndroidTun {
    fn new_tunnel(&self) -> bool {
        self.call_bool("nativeNewTunnel", "()Z", &[])
    }

    fn set_remote_address(&self, _address: &str, _ipv6: bool) -> bool {
        true
    }

    fn add_address(&self, address: &str, prefix_length: i32, gateway: &str, ipv6: bool, net30: bool) -> bool {
        let Ok(mut env) = self.vm.attach_current_thread() else { return false };
        let (Ok(address), Ok(gateway)) = (env.new_string(address), env.new_string(gateway)) else { return false };
        let args = [
            JValue::Object(&address),
            JValue::Int(prefix_length),
            JValue::Object(&gateway),
            JValue::Bool(u8::from(ipv6)),
            JValue::Bool(u8::from(net30)),
        ];
        self.call_bool("nativeAddAddress", "(Ljava/lang/String;ILjava/lang/String;ZZ)Z", &args)
    }

    fn reroute_gateway(&self, ipv4: bool, ipv6: bool, flags: u32) -> bool {
        let args = [
            JValue::Bool(u8::from(ipv4)),
            JValue::Bool(u8::from(ipv6)),
            JValue::Int(flags as i32),
        ];
        self.call_bool("nativeRerouteGateway", "(ZZI)Z", &args)
    }

    fn add_route(&self, address: &str, prefix_length: i32, metric: i32, ipv6: bool) -> bool {
        let Ok(mut env) = self.vm.attach_current_thread() else { return false };
        let Ok(address) = env.new_string(address) else { return false };
        let args = [
            JValue::Object(&address),
            JValue::Int(prefix_length),
            JValue::Int(metric),
            JValue::Bool(u8::from(ipv6)),
        ];
        self.call_bool("nativeAddRoute", "(Ljava/lang/String;IIZ)Z", &args)
    }

    fn exclude_route(&self, address: &str, prefix_length: i32, metric: i32, ipv6: bool) -> bool {
        let Ok(mut env) = self.vm.attach_current_thread() else { return false };
        let Ok(address) = env.new_string(address) else { return false };
        let args = [
            JValue::Object(&address),
            JValue::Int(prefix_length),
            JValue::Int(metric),
            JValue::Bool(u8::from(ipv6)),
        ];
        self.call_bool("nativeExcludeRoute", "(Ljava/lang/String;IIZ)Z", &args)
    }

    fn set_dns_options(&self, dns: &openvpn_connect::DnsOptions) -> bool {
        for server in &dns.servers {
            for address in &server.addresses {
                let Ok(mut env) = self.vm.attach_current_thread() else { return false };
                let Ok(value) = env.new_string(&address.address) else { return false };
                let args = [JValue::Object(&value)];
                if !self.call_bool("nativeAddDnsServer", "(Ljava/lang/String;)Z", &args) {
                    return false;
                }
            }
        }
        true
    }

    fn set_mtu(&self, mtu: i32) -> bool {
        self.call_bool("nativeSetMtu", "(I)Z", &[JValue::Int(mtu)])
    }

    fn set_session_name(&self, name: &str) -> bool {
        let Ok(mut env) = self.vm.attach_current_thread() else { return false };
        let Ok(name) = env.new_string(name) else { return false };
        self.call_bool("nativeSetSessionName", "(Ljava/lang/String;)Z", &[JValue::Object(&name)])
    }

    fn establish(&self) -> i32 {
        self.call_int("nativeEstablish", "()I", &[])
    }

    fn teardown(&self, disconnect: bool) {
        self.call_void("nativeTeardown", "(Z)V", &[JValue::Bool(u8::from(disconnect))]);
    }
}

struct AndroidHandler {
    tun: AndroidTun,
}

impl EventHandler for AndroidHandler {
    fn event(&self, event: openvpn_connect::Event) {
        self.tun.call_status(&format!("{}: {}", event.name, event.info));
    }

    fn log(&self, text: &str) {
        self.tun.call_status(text);
    }

    fn socket_protect(&self, socket: isize, _remote: &str, _ipv6: bool) -> bool {
        self.tun.protect_socket(socket as i32)
    }

    fn tun_builder(&self) -> Option<&dyn TunBuilder> {
        Some(&self.tun)
    }
}

#[no_mangle]
pub extern "system" fn Java_com_livrenet_android_OpenVpnNative_nativeCoreInfo(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let caps = openvpn_connect::capabilities();
    java_string(
        &mut env,
        format!(
            "OpenVPN 3 Core | plataforma={} | tun_builder={}",
            openvpn_connect::platform(),
            caps.tun_builder
        ),
    )
}

#[no_mangle]
pub extern "system" fn Java_com_livrenet_android_OpenVpnNative_nativeValidateProfile(
    mut env: JNIEnv,
    _class: JClass,
    profile: JString,
) -> jstring {
    let profile: String = match env.get_string(&profile) {
        Ok(value) => value.into(),
        Err(error) => return java_string(&mut env, format!("ERRO: não foi possível ler o perfil: {error}")),
    };

    if profile.trim().is_empty() {
        return java_string(&mut env, "ERRO: perfil OpenVPN vazio");
    }

    match openvpn_connect::evaluate_config(&Config::new(profile)) {
        Ok(evaluation) => java_string(
            &mut env,
            format!("OK: perfil reconhecido pelo OpenVPN 3 Core; autenticação automática={}", evaluation.autologin),
        ),
        Err(error) => java_string(&mut env, format!("ERRO DE PERFIL: {error}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_livrenet_android_OpenVpnVpnService_nativeConnect(
    mut env: JNIEnv,
    _class: JClass,
    profile: JString,
    service: JObject,
) -> jstring {
    let profile: String = match env.get_string(&profile) {
        Ok(value) => value.into(),
        Err(error) => return java_string(&mut env, format!("ERRO: perfil inválido: {error}")),
    };
    let vm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(error) => return java_string(&mut env, format!("ERRO JNI: {error}")),
    };
    let service = match env.new_global_ref(service) {
        Ok(service) => service,
        Err(error) => return java_string(&mut env, format!("ERRO JNI: {error}")),
    };

    let handler = AndroidHandler {
        tun: AndroidTun { vm, service },
    };
    let client = match Client::new(handler) {
        Ok(client) => client,
        Err(error) => return java_string(&mut env, format!("ERRO AO INICIAR OPENVPN: {error}")),
    };
    let evaluation = match client.evaluate(&Config::new(profile)) {
        Ok(evaluation) => evaluation,
        Err(error) => return java_string(&mut env, format!("ERRO DE PERFIL: {error}")),
    };

    // VPN Gate's published OpenVPN profiles use the public shared credentials
    // "vpn"/"vpn" when username/password authentication is required.
    // Auto-login profiles already carry their own client authentication.
    if !evaluation.autologin {
        if let Err(error) = client.provide_credentials(&Credentials::new("vpn", "vpn")) {
            return java_string(&mut env, format!("ERRO DE AUTENTICAÇÃO: não foi possível fornecer as credenciais públicas do VPN Gate: {error}"));
        }
    }

    if let Ok(mut slot) = active_client().lock() {
        *slot = Some(client.clone());
    } else {
        return java_string(&mut env, "ERRO: não foi possível preparar o controle da conexão");
    }

    let result = client.connect();
    if let Ok(mut slot) = active_client().lock() {
        *slot = None;
    }

    match result {
        Ok(status) => java_string(&mut env, format!("OpenVPN terminou: {} {}", status.status, status.message)),
        Err(error) => java_string(&mut env, format!("OpenVPN desconectado: {error}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_livrenet_android_OpenVpnVpnService_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Ok(slot) = active_client().lock() {
        if let Some(client) = slot.as_ref() {
            client.stop();
        }
    }
}
