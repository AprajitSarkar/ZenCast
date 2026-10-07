package main

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	scid                = "01234567"
	unixSocket          = "@scrcpy_" + scid
	tcpDiscoveryPort    = ":27182"
	videoPort           = ":27183"
	controlPort         = ":27184"
	audioPort           = ":27185"
	fileTransferPort    = ":27186"
	localShareProxyPort = "127.0.0.1:27188"
	beaconPort          = 38888
	serverJar           = "/data/adb/modules/mercor_zen_host/bin/scrcpy-server.jar"
)

var (
	sessionMutex     sync.Mutex
	currentSessionID int64
	scrcpyCmd        *exec.Cmd
	videoActive      bool

	controlMutex        sync.Mutex
	scrcpyControlConn   net.Conn
	activeControlClient net.Conn

	audioMutex         sync.Mutex
	activeAudioClient  net.Conn
	cachedAudioHeader  []byte
	audioStreamRunning bool

	clientIPMutex sync.Mutex
	lastClientIP  string
)

func recordClientIP(remoteAddr string) {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err == nil && host != "127.0.0.1" && host != "::1" && host != "" {
		clientIPMutex.Lock()
		lastClientIP = host
		clientIPMutex.Unlock()
		_ = os.WriteFile("/data/local/tmp/last_client_ip.txt", []byte(host+"\n"), 0666)
		_ = exec.Command("chmod", "666", "/data/local/tmp/last_client_ip.txt").Run()
	}
}

func getDeviceModel() string {
	out, err := exec.Command("getprop", "ro.product.model").Output()
	if err == nil {
		s := strings.TrimSpace(string(out))
		if len(s) > 0 {
			return s
		}
	}
	return "ZenFone Max Pro M1"
}

func getWlanIP() string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return "127.0.0.1"
	}
	for _, iface := range ifaces {
		if strings.HasPrefix(iface.Name, "wlan") {
			addrs, err := iface.Addrs()
			if err != nil {
				continue
			}
			for _, addr := range addrs {
				if ipnet, ok := addr.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
					if ip4 := ipnet.IP.To4(); ip4 != nil {
						return ip4.String()
					}
				}
			}
		}
	}
	return "127.0.0.1"
}

func killScrcpyLocked() {
	if scrcpyCmd != nil && scrcpyCmd.Process != nil {
		log.Println("[ZenHost] Terminating previous scrcpy-server instance...")
		_ = scrcpyCmd.Process.Kill()
		_ = scrcpyCmd.Wait()
		scrcpyCmd = nil
	}
	videoActive = false
	audioStreamRunning = false

	controlMutex.Lock()
	if scrcpyControlConn != nil {
		_ = scrcpyControlConn.Close()
		scrcpyControlConn = nil
	}
	if activeControlClient != nil {
		_ = activeControlClient.Close()
		activeControlClient = nil
	}
	controlMutex.Unlock()

	audioMutex.Lock()
	if activeAudioClient != nil {
		_ = activeAudioClient.Close()
		activeAudioClient = nil
	}
	cachedAudioHeader = nil
	audioMutex.Unlock()

	// Ensure any orphan app_process scrcpy instances are killed
	_ = exec.Command("pkill", "-9", "-f", "com.genymobile.scrcpy.Server").Run()
}

func dialWithRetry(network, address string, timeout time.Duration) (net.Conn, error) {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		conn, err := net.Dial(network, address)
		if err == nil {
			return conn, nil
		}
		time.Sleep(40 * time.Millisecond)
	}
	return nil, fmt.Errorf("timeout dialing %s", address)
}

func setBacklight(val string) {
	val = strings.TrimSpace(val)
	_ = os.WriteFile("/sys/class/leds/lcd-backlight/brightness", []byte(val+"\n"), 0644)
	_ = exec.Command("settings", "put", "system", "screen_brightness", val).Run()
	log.Printf("[ZenHost] Set screen backlight to %s", val)
}

func applyHardwareStabilityFixes() {
	// 1. Permanently disable Qualcomm LPM deep sleep (prevents Sleep of Death on Asus X00TD)
	_ = os.WriteFile("/sys/module/lpm_levels/parameters/sleep_disabled", []byte("Y\n"), 0644)
	_ = os.WriteFile("/sys/kernel/power_suspend/power_suspend_mode", []byte("0\n"), 0644)
	_ = os.WriteFile("/sys/module/mdss_dsi/parameters/dsi_status_disable", []byte("1\n"), 0644)

	// 2. Ensure kernel wake lock is active
	_ = os.WriteFile("/sys/power/wake_lock", []byte("zen_headless_wakelock\n"), 0644)

	// 3. Keep display power awake on battery and USB
	_ = exec.Command("svc", "power", "stayon", "true").Run()
	_ = exec.Command("settings", "put", "global", "stay_on_while_plugged_in", "7").Run()
	_ = exec.Command("settings", "put", "system", "screen_off_timeout", "2147483647").Run()
	_ = exec.Command("device_config", "put", "attention_manager_service", "enable_flip_to_screen_off", "false").Run()
	_ = exec.Command("device_config", "set_sync_disabled_for_tests", "persistent").Run()
	_ = exec.Command("settings", "put", "secure", "wake_gesture_enabled", "1").Run()
	_ = exec.Command("dumpsys", "deviceidle", "disable").Run()
	_ = exec.Command("wm", "dismiss-keyguard").Run()

	// 4. Whitelist dual camera packages for secondary/depth camera access
	_ = exec.Command("setprop", "vendor.camera.aux.packagelist", "org.codeaurora.snapcam,net.sourceforge.opencamera,com.google.android.apps.googlecamera.fishfood,com.android.camera2,com.asus.camera").Run()
	_ = exec.Command("setprop", "persist.vendor.camera.expose.aux", "1").Run()

	// 5. Ensure media volume is high so scrcpy audio capture gets full amplitude
	_ = exec.Command("cmd", "media_session", "volume", "--stream", "3", "--set", "15").Run()

	// 6. GPU Performance Lock (Eliminate Adreno 509 160MHz underclocking)
	_ = os.WriteFile("/sys/class/kgsl/kgsl-3d0/min_clock_mhz", []byte("370\n"), 0644)
	_ = os.WriteFile("/sys/class/kgsl/kgsl-3d0/devfreq/min_freq", []byte("370000000\n"), 0644)
	_ = os.WriteFile("/sys/class/kgsl/kgsl-3d0/idle_timer", []byte("10000\n"), 0644)
	_ = os.WriteFile("/sys/class/kgsl/kgsl-3d0/force_bus_on", []byte("1\n"), 0644)
	_ = os.WriteFile("/sys/class/kgsl/kgsl-3d0/force_clk_on", []byte("1\n"), 0644)
	_ = os.WriteFile("/sys/class/kgsl/kgsl-3d0/force_rail_on", []byte("1\n"), 0644)

	// 7. CPU Schedutil Responsiveness Tuning (Little: 1.4GHz, Big: 1.4GHz minimum)
	_ = os.WriteFile("/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq", []byte("1401600\n"), 0644)
	_ = os.WriteFile("/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq", []byte("1401600\n"), 0644)
	_ = os.WriteFile("/sys/devices/system/cpu/cpufreq/policy0/schedutil/up_rate_limit_us", []byte("500\n"), 0644)
	_ = os.WriteFile("/sys/devices/system/cpu/cpufreq/policy0/schedutil/down_rate_limit_us", []byte("30000\n"), 0644)
	_ = os.WriteFile("/sys/devices/system/cpu/cpufreq/policy4/schedutil/up_rate_limit_us", []byte("500\n"), 0644)
	_ = os.WriteFile("/sys/devices/system/cpu/cpufreq/policy4/schedutil/down_rate_limit_us", []byte("30000\n"), 0644)

	// 8. SurfaceFlinger & Window Blur Elimination (Massive GPU fillrate gain)
	_ = exec.Command("resetprop", "persist.sys.sf.disable_blurs", "1").Run()
	_ = exec.Command("resetprop", "ro.surface_flinger.supports_background_blur", "0").Run()
	_ = exec.Command("resetprop", "ro.sf.blurs_are_expensive", "1").Run()
	_ = exec.Command("setprop", "debug.sf.disable_client_composition_cache", "0").Run()
	_ = exec.Command("setprop", "debug.sf.predict_hwc_composition_strategy", "1").Run()
	_ = exec.Command("setprop", "debug.sf.latch_unsignaled", "1").Run()
	_ = exec.Command("setprop", "debug.sf.enable_gl_backpressure", "0").Run()

	// 9. RAM & Virtual Memory Optimization for 3GB Devices
	_ = os.WriteFile("/proc/sys/vm/swappiness", []byte("60\n"), 0644)
	_ = os.WriteFile("/proc/sys/vm/vfs_cache_pressure", []byte("100\n"), 0644)
	_ = os.WriteFile("/proc/sys/vm/dirty_ratio", []byte("20\n"), 0644)
	_ = os.WriteFile("/proc/sys/vm/dirty_background_ratio", []byte("10\n"), 0644)
	_ = os.WriteFile("/proc/sys/vm/compact_memory", []byte("1\n"), 0644)

	// 10. Disable Heavy Background UI loops in CherishOS
	_ = exec.Command("settings", "put", "system", "network_traffic_enabled", "0").Run()
	_ = exec.Command("settings", "put", "system", "qs_tile_animation_style", "0").Run()
	_ = exec.Command("settings", "put", "system", "qs_panel_style", "0").Run()
	_ = exec.Command("settings", "put", "system", "qs_battery_style", "0").Run()
}

func getStreamOptions() (maxSize string, bitRate string) {
	// Defaults: optimal for Snapdragon 636 (Adreno 509) to guarantee 60fps zero dropped frames
	// Note: on 18:9 screens (1080x2160), max_size limits the long dimension (height), so 1440 = 720x1440 (720p HD)
	maxSize = "1440"
	bitRate = "4000000"

	model := getDeviceModel()
	if strings.Contains(strings.ToLower(model), "lavender") || strings.Contains(strings.ToLower(model), "redmi") {
		maxSize = "2160"
		bitRate = "6000000"
	}

	// Allow user override via stream.conf
	for _, p := range []string{"/data/adb/modules/mercor_zen_host/stream.conf", "/data/local/tmp/zen_stream.conf"} {
		if data, err := os.ReadFile(p); err == nil {
			lines := strings.Split(string(data), "\n")
			for _, l := range lines {
				l = strings.TrimSpace(l)
				if strings.HasPrefix(l, "#") || !strings.Contains(l, "=") {
					continue
				}
				parts := strings.SplitN(l, "=", 2)
				k := strings.TrimSpace(parts[0])
				v := strings.TrimSpace(parts[1])
				switch k {
				case "max_size":
					if v != "" {
						maxSize = v
					}
				case "video_bit_rate":
					if v != "" {
						bitRate = v
					}
				}
			}
			break
		}
	}
	return maxSize, bitRate
}

func startScrcpySessionLocked() (net.Conn, error) {
	killScrcpyLocked()
	applyHardwareStabilityFixes()

	jarPath := serverJar
	if _, err := os.Stat(jarPath); err != nil {
		jarPath = "/data/local/tmp/scrcpy-server.jar"
	}

	maxSize, bitRate := getStreamOptions()
	log.Printf("[ZenHost] Launching scrcpy-server 4.1 (hardware OMX.qcom AVC, 60fps, max_size=%s, bit_rate=%s, zero-latency)...", maxSize, bitRate)

	cmd := exec.Command("app_process", "/",
		"com.genymobile.scrcpy.Server", "4.1",
		"scid="+scid,
		"log_level=info",
		"video_codec=h264",
		"video_encoder=OMX.qcom.video.encoder.avc",
		"max_fps=60",
		"video_bit_rate="+bitRate,
		"max_size="+maxSize,
		"video_codec_options=i-frame-interval=1",
		"audio=true",
		"audio_codec=raw",
		"control=true",
		"tunnel_forward=true",
		"display_id=0",
		"stay_awake=true",
		"cleanup=false",
	)
	cmd.Env = append(os.Environ(), "CLASSPATH="+jarPath)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("failed to start scrcpy-server: %w", err)
	}
	scrcpyCmd = cmd
	videoActive = true

	// Dial Socket 1: Video
	vConn, err := dialWithRetry("unix", unixSocket, 5*time.Second)
	if err != nil {
		killScrcpyLocked()
		return nil, fmt.Errorf("timeout connecting to scrcpy video socket: %w", err)
	}

	// Dial Socket 2: Audio
	aConn, err := dialWithRetry("unix", unixSocket, 5*time.Second)
	if err != nil {
		log.Printf("[ZenHost] Warning: Audio socket connection failed: %v", err)
	} else {
		go runAudioDispatcher(aConn)
	}

	// Dial Socket 3: Control
	cConn, err := dialWithRetry("unix", unixSocket, 5*time.Second)
	if err != nil {
		log.Printf("[ZenHost] Warning: Control socket connection failed: %v", err)
	} else {
		controlMutex.Lock()
		scrcpyControlConn = cConn
		controlMutex.Unlock()
		go runControlDispatcher(cConn)
		log.Printf("[ZenHost] Scrcpy control socket connected successfully")
	}

	return vConn, nil
}

func runControlDispatcher(cConn net.Conn) {
	defer cConn.Close()
	buf := make([]byte, 8192)
	for {
		sessionMutex.Lock()
		active := videoActive
		sessionMutex.Unlock()
		if !active {
			return
		}

		n, err := cConn.Read(buf)
		if err != nil {
			return
		}

		controlMutex.Lock()
		client := activeControlClient
		controlMutex.Unlock()

		if client != nil && n > 0 {
			_ = client.SetWriteDeadline(time.Now().Add(500 * time.Millisecond))
			_, _ = client.Write(buf[:n])
		}
	}
}

func runAudioDispatcher(aConn net.Conn) {
	defer aConn.Close()

	// Read 4-byte audio header: 4 bytes codec ID (scrcpy audio socket sends no dummy byte)
	hdr := make([]byte, 4)
	if _, err := io.ReadFull(aConn, hdr); err != nil {
		log.Printf("[ZenHost] Failed to read audio header: %v", err)
		return
	}

	audioMutex.Lock()
	cachedAudioHeader = hdr
	audioStreamRunning = true
	audioMutex.Unlock()
	log.Printf("[ZenHost] Audio initialized: codec 0x%08X (%s)",
		binary.BigEndian.Uint32(hdr), string(hdr))

	frameHdr := make([]byte, 12) // 8-byte PTS + 4-byte size
	payloadBuf := make([]byte, 64*1024)

	for {
		sessionMutex.Lock()
		active := videoActive
		sessionMutex.Unlock()
		if !active {
			return
		}

		// Read 12-byte header: 8 bytes PTS + 4 bytes size
		_, err := io.ReadFull(aConn, frameHdr)
		if err != nil {
			return
		}

		size := int(binary.BigEndian.Uint32(frameHdr[8:12]))
		if size < 0 || size > 1024*1024 {
			log.Printf("[ZenHost] Invalid audio frame size: %d, aborting dispatcher", size)
			return
		}

		if size > len(payloadBuf) {
			payloadBuf = make([]byte, size+4096)
		}

		if size > 0 {
			_, err = io.ReadFull(aConn, payloadBuf[:size])
			if err != nil {
				return
			}
		}

		// Forward to active audio TCP client if connected
		audioMutex.Lock()
		client := activeAudioClient
		audioMutex.Unlock()

		if client != nil {
			_ = client.SetWriteDeadline(time.Now().Add(300 * time.Millisecond))
			if _, err := client.Write(frameHdr); err != nil {
				audioMutex.Lock()
				if activeAudioClient == client {
					_ = client.Close()
					activeAudioClient = nil
				}
				audioMutex.Unlock()
				continue
			}
			if size > 0 {
				if _, err := client.Write(payloadBuf[:size]); err != nil {
					audioMutex.Lock()
					if activeAudioClient == client {
						_ = client.Close()
						activeAudioClient = nil
					}
					audioMutex.Unlock()
				}
			}
		}
	}
}

func handleVideoClient(tcpConn net.Conn) {
	defer tcpConn.Close()
	recordClientIP(tcpConn.RemoteAddr().String())
	log.Printf("[ZenHost] Video client connected from %s", tcpConn.RemoteAddr())

	if tcp, ok := tcpConn.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
		_ = tcp.SetWriteBuffer(256 * 1024)
		_ = tcp.SetReadBuffer(64 * 1024)
	}

	sessionMutex.Lock()
	currentSessionID++
	thisSessionID := currentSessionID
	unixConn, err := startScrcpySessionLocked()
	sessionMutex.Unlock()
	if err != nil {
		log.Printf("[ZenHost] Failed to start scrcpy video: %v", err)
		return
	}
	defer unixConn.Close()

	// Stream video from scrcpy unix socket to TCP client with low-latency buffer.
	// When the client disconnects, Write in CopyBuffer immediately fails and terminates the session cleanly.
	vBuf := make([]byte, 128*1024)
	_, _ = io.CopyBuffer(tcpConn, unixConn, vBuf)

	log.Printf("[ZenHost] Video connection ended for %s", tcpConn.RemoteAddr())

	sessionMutex.Lock()
	if currentSessionID == thisSessionID {
		killScrcpyLocked()
	}
	sessionMutex.Unlock()
}

func handleControlClient(tcpConn net.Conn) {
	recordClientIP(tcpConn.RemoteAddr().String())
	log.Printf("[ZenHost] Control client connected from %s", tcpConn.RemoteAddr())

	if tcp, ok := tcpConn.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
		_ = tcp.SetWriteBuffer(32 * 1024)
		_ = tcp.SetReadBuffer(32 * 1024)
	}

	// Wait up to 5s for control socket to be ready
	deadline := time.Now().Add(5 * time.Second)
	var cConn net.Conn
	for time.Now().Before(deadline) {
		controlMutex.Lock()
		cConn = scrcpyControlConn
		controlMutex.Unlock()
		if cConn != nil {
			break
		}
		time.Sleep(40 * time.Millisecond)
	}

	if cConn == nil {
		log.Printf("[ZenHost] Control session not ready for %s", tcpConn.RemoteAddr())
		_ = tcpConn.Close()
		return
	}

	controlMutex.Lock()
	if activeControlClient != nil {
		log.Printf("[ZenHost] Replacing previous control client %s", activeControlClient.RemoteAddr())
		_ = activeControlClient.Close()
	}
	activeControlClient = tcpConn
	controlMutex.Unlock()

	defer func() {
		controlMutex.Lock()
		if activeControlClient == tcpConn {
			activeControlClient = nil
		}
		controlMutex.Unlock()
		_ = tcpConn.Close()
	}()

	log.Printf("[ZenHost] Control channel active for %s!", tcpConn.RemoteAddr())

	inBuf := make([]byte, 16*1024)
	for {
		n, err := tcpConn.Read(inBuf)
		if err != nil {
			break
		}
		controlMutex.Lock()
		target := scrcpyControlConn
		controlMutex.Unlock()
		if target == nil {
			break
		}
		_, err = target.Write(inBuf[:n])
		if err != nil {
			break
		}
	}
	log.Printf("[ZenHost] Control connection ended for %s", tcpConn.RemoteAddr())
}

func handleAudioClient(tcpConn net.Conn) {
	log.Printf("[ZenHost] Audio client connected from %s", tcpConn.RemoteAddr())

	if tcp, ok := tcpConn.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
		_ = tcp.SetWriteBuffer(64 * 1024)
	}

	// Wait up to 5s for audio stream to initialize and header to be cached
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		audioMutex.Lock()
		hasHdr := len(cachedAudioHeader) == 4
		audioMutex.Unlock()
		if hasHdr {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	audioMutex.Lock()
	if len(cachedAudioHeader) != 4 {
		audioMutex.Unlock()
		log.Printf("[ZenHost] Audio stream not active yet for %s, closing", tcpConn.RemoteAddr())
		_ = tcpConn.Close()
		return
	}

	// Send cached 4-byte header (4-byte codec ID) to this client
	if _, err := tcpConn.Write(cachedAudioHeader); err != nil {
		audioMutex.Unlock()
		_ = tcpConn.Close()
		return
	}
	if activeAudioClient != nil {
		_ = activeAudioClient.Close()
	}
	activeAudioClient = tcpConn
	log.Printf("[ZenHost] Sent audio header (codec 0x%08X) to client %s",
		binary.BigEndian.Uint32(cachedAudioHeader), tcpConn.RemoteAddr())
	audioMutex.Unlock()

	// Wait until client disconnects
	buf := make([]byte, 128)
	for {
		_, err := tcpConn.Read(buf)
		if err != nil {
			break
		}
	}

	audioMutex.Lock()
	if activeAudioClient == tcpConn {
		activeAudioClient = nil
	}
	audioMutex.Unlock()
	_ = tcpConn.Close()
	log.Printf("[ZenHost] Audio client disconnected: %s", tcpConn.RemoteAddr())
}

const (
	fileMagic    = 0x5A434654 // "ZCFT"
	cmdFileStart = 0x01
	cmdFileData  = 0x02
	cmdFileEnd   = 0x03
	cmdBatchDone = 0x04
	cmdAck       = 0x05
)

func handleIncomingFileTransfer(tcpConn net.Conn) {
	defer tcpConn.Close()
	recordClientIP(tcpConn.RemoteAddr().String())
	log.Printf("[ZenHost] File transfer client connected from %s", tcpConn.RemoteAddr())

	var magic uint32
	if err := binary.Read(tcpConn, binary.BigEndian, &magic); err != nil || magic != fileMagic {
		log.Printf("[ZenHost] Invalid file transfer magic: 0x%X", magic)
		return
	}

	targetDir := "/sdcard/Download/ZenCast"
	if err := os.MkdirAll(targetDir, 0777); err != nil {
		log.Printf("[ZenHost] Failed to create %s: %v", targetDir, err)
		return
	}

	var currentFile *os.File
	var currentPath string
	var totalFiles int
	buf := make([]byte, 64*1024)

	for {
		cmdBuf := make([]byte, 1)
		if _, err := io.ReadFull(tcpConn, cmdBuf); err != nil {
			break
		}

		switch cmdBuf[0] {
		case cmdFileStart:
			var nameLen uint32
			if err := binary.Read(tcpConn, binary.BigEndian, &nameLen); err != nil {
				return
			}
			nameBytes := make([]byte, nameLen)
			if _, err := io.ReadFull(tcpConn, nameBytes); err != nil {
				return
			}
			var fileSize int64
			if err := binary.Read(tcpConn, binary.BigEndian, &fileSize); err != nil {
				return
			}

			cleanName := filepath.Base(string(nameBytes))
			if cleanName == "" || cleanName == "." {
				cleanName = fmt.Sprintf("file_%d", time.Now().UnixMilli())
			}
			currentPath = filepath.Join(targetDir, cleanName)
			f, err := os.Create(currentPath)
			if err != nil {
				log.Printf("[ZenHost] Error creating file %s: %v", currentPath, err)
				return
			}
			currentFile = f
			log.Printf("[ZenHost] Receiving file: %s (%d bytes)", cleanName, fileSize)

		case cmdFileData:
			var chunkLen uint32
			if err := binary.Read(tcpConn, binary.BigEndian, &chunkLen); err != nil {
				return
			}
			if int(chunkLen) > len(buf) {
				buf = make([]byte, chunkLen)
			}
			if _, err := io.ReadFull(tcpConn, buf[:chunkLen]); err != nil {
				return
			}
			if currentFile != nil {
				_, _ = currentFile.Write(buf[:chunkLen])
			}

		case cmdFileEnd:
			if currentFile != nil {
				_ = currentFile.Sync()
				_ = currentFile.Close()
				currentFile = nil
				totalFiles++
				_ = os.Chmod(currentPath, 0666)
				// Broadcast media scanner so file appears immediately in Gallery/Files
				_ = exec.Command("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://"+currentPath).Run()
				log.Printf("[ZenHost] File saved and indexed: %s", currentPath)
			}

		case cmdBatchDone:
			// Send ACK
			_, _ = tcpConn.Write([]byte{cmdAck, 0x00})
			log.Printf("[ZenHost] Batch transfer complete! Received %d file(s)", totalFiles)
			return

		default:
			log.Printf("[ZenHost] Unknown transfer command: 0x%X", cmdBuf[0])
			return
		}
	}
}

type DeviceBeacon struct {
	Device      string `json:"device"`
	Model       string `json:"model"`
	IP          string `json:"ip"`
	VideoPort   int    `json:"video_port"`
	ControlPort int    `json:"control_port"`
	AudioPort   int    `json:"audio_port"`
	FilePort    int    `json:"file_port"`
	Status      string `json:"status"`
}

func makeBeaconJSON(ip string) string {
	model := getDeviceModel()
	b := DeviceBeacon{
		Device:      model,
		Model:       model,
		IP:          ip,
		VideoPort:   27183,
		ControlPort: 27184,
		AudioPort:   27185,
		FilePort:    27186,
		Status:      "ready",
	}
	data, _ := json.Marshal(b)
	return string(data)
}

func udpBeaconLoop() {
	// 1. Listen for unicast/broadcast discovery requests
	serverAddr, err := net.ResolveUDPAddr("udp4", fmt.Sprintf("0.0.0.0:%d", beaconPort))
	if err == nil {
		udpServer, err := net.ListenUDP("udp4", serverAddr)
		if err == nil {
			go func() {
				defer udpServer.Close()
				buf := make([]byte, 1024)
				for {
					n, clientAddr, err := udpServer.ReadFromUDP(buf)
					if err != nil {
						return
					}
					msg := string(buf[:n])
					if strings.Contains(msg, "ZENCAST_DISCOVER") {
						ip := getWlanIP()
						beacon := makeBeaconJSON(ip)
						data := []byte(beacon)
						_, _ = udpServer.WriteToUDP(data, clientAddr)
						if clientAddr != nil && clientAddr.IP != nil {
							clientBeaconAddr := &net.UDPAddr{IP: clientAddr.IP, Port: beaconPort}
							_, _ = udpServer.WriteToUDP(data, clientBeaconAddr)
						}
					}
				}
			}()
		}
	}

	// 2. Periodic broadcast ticker
	ticker := time.NewTicker(1500 * time.Millisecond)
	defer ticker.Stop()

	for range ticker.C {
		ip := getWlanIP()
		if ip == "127.0.0.1" {
			continue
		}

		beacon := makeBeaconJSON(ip)
		data := []byte(beacon)

		// Broadcast to 255.255.255.255
		if broadcastAddr, err := net.ResolveUDPAddr("udp4", fmt.Sprintf("255.255.255.255:%d", beaconPort)); err == nil {
			if conn, err := net.DialUDP("udp4", nil, broadcastAddr); err == nil {
				_, _ = conn.Write(data)
				_ = conn.Close()
			}
		}

		// Also broadcast to subnet broadcast (e.g. 192.168.1.255)
		parts := strings.Split(ip, ".")
		if len(parts) == 4 {
			subnetBcast := fmt.Sprintf("%s.%s.%s.255:%d", parts[0], parts[1], parts[2], beaconPort)
			if sAddr, err := net.ResolveUDPAddr("udp4", subnetBcast); err == nil {
				if sConn, err := net.DialUDP("udp4", nil, sAddr); err == nil {
					_, _ = sConn.Write(data)
					_ = sConn.Close()
				}
			}
		}
	}
}

func main() {
	log.Println("[ZenHost] ZenFone Headless Wireless Host Daemon Starting...")
	applyHardwareStabilityFixes()

	go udpBeaconLoop()

	discoveryListener, err := net.Listen("tcp", tcpDiscoveryPort)
	if err == nil {
		defer discoveryListener.Close()
		go func() {
			for {
				conn, err := discoveryListener.Accept()
				if err != nil {
					return
				}
				go func(c net.Conn) {
					defer c.Close()
					ip := getWlanIP()
					beacon := makeBeaconJSON(ip) + "\n"
					_, _ = c.Write([]byte(beacon))

					_ = c.SetReadDeadline(time.Now().Add(150 * time.Millisecond))
					buf := make([]byte, 256)
					n, _ := c.Read(buf)
					cmdStr := strings.TrimSpace(string(buf[:n]))

					if cmdStr == "GET_CLIENT_IP" {
						clientIPMutex.Lock()
						clientIp := lastClientIP
						clientIPMutex.Unlock()
						_, _ = c.Write([]byte(clientIp + "\n"))
						return
					}

					if strings.HasPrefix(cmdStr, "SET_BACKLIGHT:") {
						val := strings.TrimPrefix(cmdStr, "SET_BACKLIGHT:")
						setBacklight(val)
						_, _ = c.Write([]byte(`{"status":"ok"}` + "\n"))
						return
					}
				}(conn)
			}
		}()
		log.Printf("[ZenHost] TCP Discovery & Control listening on %s...", tcpDiscoveryPort)
	}

	fileListener, err := net.Listen("tcp", fileTransferPort)
	if err != nil {
		log.Printf("[ZenHost] Warning: Failed to listen on file transfer port %s: %v", fileTransferPort, err)
	} else {
		defer fileListener.Close()
		go func() {
			for {
				conn, err := fileListener.Accept()
				if err != nil {
					return
				}
				go handleIncomingFileTransfer(conn)
			}
		}()
		log.Printf("[ZenHost] File Transfer server listening on %s...", fileTransferPort)
	}

	videoListener, err := net.Listen("tcp", videoPort)
	if err != nil {
		log.Fatalf("[ZenHost] Failed to listen on video port %s: %v", videoPort, err)
	}
	defer videoListener.Close()

	controlListener, err := net.Listen("tcp", controlPort)
	if err != nil {
		log.Fatalf("[ZenHost] Failed to listen on control port %s: %v", controlPort, err)
	}
	defer controlListener.Close()

	audioListener, err := net.Listen("tcp", audioPort)
	if err != nil {
		log.Printf("[ZenHost] Warning: Failed to listen on audio port %s: %v", audioPort, err)
	} else {
		defer audioListener.Close()
		go func() {
			for {
				conn, err := audioListener.Accept()
				if err != nil {
					return
				}
				go handleAudioClient(conn)
			}
		}()
	}

	log.Printf("[ZenHost] Listening on %s (Video), %s (Control), %s (Audio), %s (Files)...", videoPort, controlPort, audioPort, fileTransferPort)

	go func() {
		for {
			conn, err := controlListener.Accept()
			if err != nil {
				return
			}
			go handleControlClient(conn)
		}
	}()

	proxyListener, err := net.Listen("tcp", localShareProxyPort)
	if err == nil {
		defer proxyListener.Close()
		go func() {
			for {
				conn, err := proxyListener.Accept()
				if err != nil {
					return
				}
				go handleLocalShareProxy(conn)
			}
		}()
		log.Printf("[ZenHost] Local Share VPN-Bypass Proxy listening on %s...", localShareProxyPort)
	}

	for {
		conn, err := videoListener.Accept()
		if err != nil {
			return
		}
		go handleVideoClient(conn)
	}
}

func handleLocalShareProxy(localConn net.Conn) {
	defer localConn.Close()
	clientIPMutex.Lock()
	targetIP := lastClientIP
	clientIPMutex.Unlock()

	if targetIP == "" || targetIP == "127.0.0.1" {
		data, err := os.ReadFile("/data/local/tmp/last_client_ip.txt")
		if err == nil {
			targetIP = strings.TrimSpace(string(data))
		}
	}

	if targetIP == "" || targetIP == "127.0.0.1" {
		log.Printf("[ZenHost Proxy] Error: No known client IP to share file to")
		return
	}

	dialer := net.Dialer{
		Control: func(network, address string, c syscall.RawConn) error {
			return c.Control(func(fd uintptr) {
				// SO_BINDTODEVICE (25) forces packets out through physical wlan0 radio interface
				_ = syscall.SetsockoptString(int(fd), syscall.SOL_SOCKET, 25, "wlan0")
			})
		},
		Timeout: 5 * time.Second,
	}

	clientTarget := net.JoinHostPort(targetIP, "27187")
	log.Printf("[ZenHost Proxy] Connecting to client receiver at %s over wlan0...", clientTarget)
	clientConn, err := dialer.Dial("tcp", clientTarget)
	if err != nil {
		log.Printf("[ZenHost Proxy] Dial to %s failed: %v", clientTarget, err)
		return
	}
	defer clientConn.Close()

	log.Printf("[ZenHost Proxy] Connected to client! Starting transfer relay...")
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(clientConn, localConn)
		if tc, ok := clientConn.(*net.TCPConn); ok {
			_ = tc.CloseWrite()
		}
	}()
	go func() {
		defer wg.Done()
		_, _ = io.Copy(localConn, clientConn)
		if tc, ok := localConn.(*net.TCPConn); ok {
			_ = tc.CloseWrite()
		}
	}()
	wg.Wait()
	log.Printf("[ZenHost Proxy] Transfer relay completed")
}
