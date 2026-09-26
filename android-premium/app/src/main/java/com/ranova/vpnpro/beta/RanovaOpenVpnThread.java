package com.ranova.vpnpro.beta;

import com.tim.openvpn.OpenVPNThreadv3;
import com.tim.openvpn.service.IOpenVPNService;

import net.openvpn.ovpn3.ClientAPI_Config;
import net.openvpn.ovpn3.ClientAPI_EvalConfig;
import net.openvpn.ovpn3.ClientAPI_Event;
import net.openvpn.ovpn3.ClientAPI_LogInfo;
import net.openvpn.ovpn3.ClientAPI_Status;

public final class RanovaOpenVpnThread extends OpenVPNThreadv3 {
    private final String rawConfig;
    private final IOpenVPNService service;

    public RanovaOpenVpnThread(IOpenVPNService service, String config) {
        super(service, config);
        this.service = service;
        this.rawConfig = config;
    }

    @Override
    public void run() {
        try {
            ClientAPI_Config config = new ClientAPI_Config();
            config.setContent(rawConfig);
            config.setTunPersist(true);
            config.setExternalPkiAlias("extpki");
            config.setCompressionMode("asym");
            config.setInfo(true);
            config.setAllowLocalLanAccess(false);
            config.setRetryOnAuthFailed(false);

            // VPN Gate/SoftEther relays still commonly use AES-CBC.
            // OpenVPN3 defaults to preferred AEAD-only data-channel algorithms.
            config.setEnableNonPreferredDCAlgorithms(true);
            config.setEnableLegacyAlgorithms(false);
            config.setEnableRouteEmulation(false);

            ClientAPI_EvalConfig evaluated = eval_config(config);
            if (evaluated.getError()) {
                RanovaVpnBus.error("Config error: " + evaluated.getMessage());
                RanovaVpnBus.state("DISCONNECTED");
                service.openvpnStopped();
                return;
            }

            RanovaVpnBus.state("CONNECTING");
            ClientAPI_Status result = connect();

            if (result != null && result.getError()) {
                String msg = result.getMessage();
                RanovaVpnBus.error(
                        msg == null || msg.isEmpty()
                                ? "Server connection failed"
                                : msg
                );
            }
        } catch (Throwable t) {
            RanovaVpnBus.error("VPN engine error: " + t.getClass().getSimpleName());
        } finally {
            RanovaVpnBus.state("DISCONNECTED");
            try {
                service.openvpnStopped();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void event(ClientAPI_Event event) {
        super.event(event);

        if (event == null) return;

        String name = event.getName();
        if (name != null) {
            if ("CONNECTED".equals(name)) {
                RanovaVpnBus.state("CONNECTED");
            } else if ("DISCONNECTED".equals(name)) {
                RanovaVpnBus.state("DISCONNECTED");
            } else if ("RESOLVE".equals(name)
                    || "WAIT".equals(name)
                    || "CONNECTING".equals(name)
                    || "GET_CONFIG".equals(name)
                    || "ASSIGN_IP".equals(name)
                    || "RECONNECTING".equals(name)) {
                RanovaVpnBus.state("CONNECTING");
            }
        }

        if (event.getError()) {
            String info = event.getInfo();
            RanovaVpnBus.error(
                    (name == null ? "VPN error" : name)
                            + (info == null || info.isEmpty() ? "" : ": " + info)
            );
        }
    }

    @Override
    public void log(ClientAPI_LogInfo info) {
        // Keep the upstream behavior but do not expose OpenVPN branding in RANOVA UI.
        super.log(info);
    }
}
