use std::sync::{Mutex, OnceLock};

use jni::{
    objects::{GlobalRef, JObject, JString, JValue},
    sys::{jint, JNI_FALSE, JNI_TRUE},
    JNIEnv, JavaVM,
};
use openvpn_connect::{Client, Config, DnsOptions, Event, EventHandler, TunBuilder};

static CLIENT: OnceLock<Mutex<Option<Client>>> = OnceLock::new();

fn client_slot() -> &'static Mutex<Option<Client>> {
    CLIENT.get_or_init(|| Mutex::new(None))
}

#[derive(Clone)]
struct JavaBridge {
    vm: JavaVM,
    service: GlobalRef,
}

impl JavaBridge {
    fn with_env<T>(&self, f: impl FnOnce(&mut JNIEnv) -> T) -> Option<T> {
        let mut env = self.vm.attach_current_thread().ok()?;
        Some(f(&mut env))
    }

    fn call_bool0(&self, name: &str) -> bool {
        self.with_env(|env| {
            env.call_method(self.service.as_obj(), name, "()Z", &[])
                .ok()
                .and_then(|v| v.z().ok())
                .unwrap_or(false)
        }).unwrap_or(false)
    }

    fn call_bool1_i(&self, name: &str, value: i32) -> bool {
        self.with_env(|env| {
            env.call_method(
                self.service.as_obj(),
                name,
                "(I)Z",
                &[JValue::Int(value)],
            )
            .ok()
            .and_then(|v| v.z().ok())
            .unwrap_or(false)
        }).unwrap_or(false)
    }

    fn call_bool1_s(&self, name: &str, text: &str) -> bool {
        self.with_env(|env| {
            let Ok(s) = env.new_string(text) else { return false; };
            env.call_method(
                self.service.as_obj(),
                name,
                "(Ljava/lang/String;)Z",
                &[JValue::Object(&s)],
            )
            .ok()
            .and_then(|v| v.z().ok())
            .unwrap_or(false)
        }).unwrap_or(false)
    }

    fn add_address(&self, address: &str, prefix: i32) -> bool {
        self.with_env(|env| {
            let Ok(s) = env.new_string(address) else { return false; };
            env.call_method(
                self.service.as_obj(),
                "tunAddAddress",
                "(Ljava/lang/String;I)Z",
                &[JValue::Object(&s), JValue::Int(prefix)],
            )
            .ok()
            .and_then(|v| v.z().ok())
            .unwrap_or(false)
        }).unwrap_or(false)
    }

    fn add_route(&self, address: &str, prefix: i32) -> bool {
        self.with_env(|env| {
            let Ok(s) = env.new_string(address) else { return false; };
            env.call_method(
                self.service.as_obj(),
                "tunAddRoute",
                "(Ljava/lang/String;I)Z",
                &[JValue::Object(&s), JValue::Int(prefix)],
            )
            .ok()
            .and_then(|v| v.z().ok())
            .unwrap_or(false)
        }).unwrap_or(false)
    }

    fn exclude_route(&self, address: &str, prefix: i32) -> bool {
        self.with_env(|env| {
            let Ok(s) = env.new_string(address) else { return false; };
            env.call_method(
                self.service.as_obj(),
                "tunExcludeRoute",
                "(Ljava/lang/String;I)Z",
                &[JValue::Object(&s), JValue::Int(prefix)],
            )
            .ok()
            .and_then(|v| v.z().ok())
            .unwrap_or(false)
        }).unwrap_or(false)
    }

    fn reroute(&self, ipv4: bool, ipv6: bool) -> bool {
        self.with_env(|env| {
            env.call_method(
                self.service.as_obj(),
                "tunReroute",
                "(ZZ)Z",
                &[
                    JValue::Bool(if ipv4 { JNI_TRUE } else { JNI_FALSE }),
                    JValue::Bool(if ipv6 { JNI_TRUE } else { JNI_FALSE }),
                ],
            )
            .ok()
            .and_then(|v| v.z().ok())
            .unwrap_or(false)
        }).unwrap_or(false)
    }

    fn establish(&self) -> i32 {
        self.with_env(|env| {
            env.call_method(self.service.as_obj(), "tunEstablish", "()I", &[])
                .ok()
                .and_then(|v| v.i().ok())
                .unwrap_or(-1)
        }).unwrap_or(-1)
    }

    fn protect(&self, fd: i32) -> bool {
        self.call_bool1_i("protectSocket", fd)
    }

    fn event(&self, name: &str, info: &str, error: bool, fatal: bool) {
        let _ = self.with_env(|env| {
            let Ok(jname) = env.new_string(name) else { return; };
            let Ok(jinfo) = env.new_string(info) else { return; };
            let _ = env.call_method(
                self.service.as_obj(),
                "nativeOnEvent",
                "(Ljava/lang/String;Ljava/lang/String;ZZ)V",
                &[
                    JValue::Object(&jname),
                    JValue::Object(&jinfo),
                    JValue::Bool(if error { JNI_TRUE } else { JNI_FALSE }),
                    JValue::Bool(if fatal { JNI_TRUE } else { JNI_FALSE }),
                ],
            );
        });
    }
}

struct AndroidTunnel {
    bridge: JavaBridge,
}

impl TunBuilder for AndroidTunnel {
    fn new_tunnel(&self) -> bool {
        self.bridge.call_bool0("tunNew")
    }

    fn set_layer(&self, layer: i32) -> bool {
        layer == 3
    }

    fn set_remote_address(&self, address: &str, _ipv6: bool) -> bool {
        self.bridge.call_bool1_s("tunSetRemote", address)
    }

    fn add_address(
        &self,
        address: &str,
        prefix_length: i32,
        _gateway: &str,
        _ipv6: bool,
        _net30: bool,
    ) -> bool {
        self.bridge.add_address(address, prefix_length)
    }

    fn reroute_gateway(&self, ipv4: bool, ipv6: bool, _flags: u32) -> bool {
        self.bridge.reroute(ipv4, ipv6)
    }

    fn add_route(&self, address: &str, prefix_length: i32, _metric: i32, _ipv6: bool) -> bool {
        self.bridge.add_route(address, prefix_length)
    }

    fn exclude_route(
        &self,
        address: &str,
        prefix_length: i32,
        _metric: i32,
        _ipv6: bool,
    ) -> bool {
        self.bridge.exclude_route(address, prefix_length)
    }

    fn set_dns_options(&self, dns: &DnsOptions) -> bool {
        for server in &dns.servers {
            for addr in &server.addresses {
                if !self.bridge.call_bool1_s("tunAddDns", &addr.address) {
                    return false;
                }
            }
        }
        for domain in &dns.search_domains {
            if !self.bridge.call_bool1_s("tunAddSearchDomain", domain) {
                return false;
            }
        }
        true
    }

    fn set_mtu(&self, mtu: i32) -> bool {
        self.bridge.call_bool1_i("tunSetMtu", mtu)
    }

    fn set_session_name(&self, name: &str) -> bool {
        self.bridge.call_bool1_s("tunSetSessionName", name)
    }

    fn set_allow_family(&self, _address_family: i32, _allow: bool) -> bool {
        true
    }

    fn set_allow_local_dns(&self, _allow: bool) -> bool {
        true
    }

    fn establish(&self) -> i32 {
        self.bridge.establish()
    }

    fn teardown(&self, disconnect: bool) {
        let _ = self.bridge.with_env(|env| {
            let _ = env.call_method(
                self.bridge.service.as_obj(),
                "tunTeardown",
                "(Z)V",
                &[JValue::Bool(if disconnect { JNI_TRUE } else { JNI_FALSE })],
            );
        });
    }
}

struct Handler {
    bridge: JavaBridge,
    tunnel: AndroidTunnel,
}

impl EventHandler for Handler {
    fn event(&self, event: Event) {
        self.bridge.event(&event.name, &event.info, event.error, event.fatal);
    }

    fn log(&self, text: &str) {
        if text.contains("ERROR") || text.contains("FATAL") {
            self.bridge.event("LOG", text, true, false);
        }
    }

    fn socket_protect(&self, socket: isize, _remote: &str, _ipv6: bool) -> bool {
        if socket < 0 || socket > i32::MAX as isize {
            return false;
        }
        self.bridge.protect(socket as i32)
    }

    fn tun_builder(&self) -> Option<&dyn TunBuilder> {
        Some(&self.tunnel)
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ranova_vpnpro_beta_StandaloneVpnService_nativeStart(
    mut env: JNIEnv,
    service: JObject,
    profile: JString,
) -> jint {
    let profile: String = match env.get_string(&profile) {
        Ok(v) => v.into(),
        Err(_) => return -1,
    };

    let vm = match env.get_java_vm() {
        Ok(v) => v,
        Err(_) => return -2,
    };

    let global = match env.new_global_ref(service) {
        Ok(v) => v,
        Err(_) => return -3,
    };

    let bridge = JavaBridge { vm, service: global };
    let handler = Handler {
        tunnel: AndroidTunnel { bridge: bridge.clone() },
        bridge: bridge.clone(),
    };

    let client = match Client::new(handler) {
        Ok(v) => v,
        Err(e) => {
            bridge.event("ERROR", &format!("native client: {e}"), true, true);
            return -4;
        }
    };

    let evaluation = match client.evaluate(
        &Config::new(profile)
            .with_gui_version("RANOVA VPN PRO 0.5")
    ) {
        Ok(v) => v,
        Err(e) => {
            bridge.event("ERROR", &format!("profile: {e}"), true, true);
            return -5;
        }
    };

    if !evaluation.autologin {
        bridge.event("ERROR", "profile requires credentials", true, true);
        return -6;
    }

    let control = client.clone();
    {
        let mut slot = client_slot().lock().unwrap_or_else(|e| e.into_inner());
        if let Some(old) = slot.take() {
            old.stop();
        }
        *slot = Some(control);
    }

    std::thread::spawn(move || {
        match client.connect() {
            Ok(status) => bridge.event("SESSION_END", &format!("{}: {}", status.status, status.message), false, false),
            Err(e) => bridge.event("ERROR", &format!("connect: {e}"), true, true),
        }
        let mut slot = client_slot().lock().unwrap_or_else(|e| e.into_inner());
        *slot = None;
    });

    0
}

#[no_mangle]
pub extern "system" fn Java_com_ranova_vpnpro_beta_StandaloneVpnService_nativeStop(
    _env: JNIEnv,
    _service: JObject,
) {
    let slot = client_slot().lock().unwrap_or_else(|e| e.into_inner());
    if let Some(client) = slot.as_ref() {
        client.stop();
    }
}
