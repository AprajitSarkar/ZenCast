package com.mercor.zencast;

import java.util.Objects;

public class DiscoveredDevice {
    private final String ip;
    private final String model;
    private final String deviceName;
    private final int videoPort;
    private final int controlPort;
    private final int audioPort;
    private long lastSeen;

    public DiscoveredDevice(String ip, String model, String deviceName, int videoPort, int controlPort, int audioPort) {
        this.ip = ip;
        this.model = model != null && !model.isEmpty() ? model : "ZenFone Max Pro M1";
        this.deviceName = deviceName != null && !deviceName.isEmpty() ? deviceName : this.model;
        this.videoPort = videoPort > 0 ? videoPort : 27183;
        this.controlPort = controlPort > 0 ? controlPort : 27184;
        this.audioPort = audioPort > 0 ? audioPort : 27185;
        this.lastSeen = System.currentTimeMillis();
    }

    public String getIp() { return ip; }
    public String getModel() { return model; }
    public String getDeviceName() { return deviceName; }
    public int getVideoPort() { return videoPort; }
    public int getControlPort() { return controlPort; }
    public int getAudioPort() { return audioPort; }
    public long getLastSeen() { return lastSeen; }

    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DiscoveredDevice that = (DiscoveredDevice) o;
        return Objects.equals(ip, that.ip);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ip);
    }

    @Override
    public String toString() {
        return deviceName + " (" + ip + ")";
    }
}
