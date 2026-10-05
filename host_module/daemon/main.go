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
	"strings"
	"sync"
	"time"
)

const (
	scid        = "01234567"
	unixSocket  = "@scrcpy_" + scid
	videoPort   = ":27183"
	controlPort = ":27184"
	audioPort   = ":27185"
	beaconPort  = 38888
	serverJar   = "/data/adb/modules/mercor_zen_host/bin/scrcpy-server.jar"
)

var (
	sessionMutex sync.Mutex
	scrcpyCmd    *exec.Cmd
	videoActive  bool

	controlMutex      sync.Mutex
	scrcpyControlConn net.Conn

	audioMutex         sync.Mutex
	activeAudioClient  net.Conn
	cachedAudioHeader  []byte
	audioStreamRunning bool
)

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

func startScrcpySessionLocked() (net.Conn, error) {
	killScrcpyLocked()

	jarPath := serverJar
	if _, err := os.Stat(jarPath); err != nil {
		jarPath = "/data/local/tmp/scrcpy-server.jar"
	}

	log.Printf("[ZenHost] Launching scrcpy-server 4.1 (hardware OMX.qcom AVC, 60fps, 1080p, RAW audio)...")

	cmd := exec.Command("app_process", "/",
		"com.genymobile.scrcpy.Server", "4.1",
		"scid="+scid,
		"log_level=info",
		"video_codec=h264",
		"video_encoder=OMX.qcom.video.encoder.avc",
		"max_fps=60",
		"video_bit_rate=8000000",
		"max_size=1440",
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
		log.Printf("[ZenHost] Scrcpy control socket connected successfully")
	}

	return vConn, nil
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
	log.Printf("[ZenHost] Video client connected from %s", tcpConn.RemoteAddr())

	if tcp, ok := tcpConn.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
		_ = tcp.SetWriteBuffer(256 * 1024)
		_ = tcp.SetReadBuffer(64 * 1024)
	}

	sessionMutex.Lock()
	unixConn, err := startScrcpySessionLocked()
	sessionMutex.Unlock()
	if err != nil {
		log.Printf("[ZenHost] Failed to start scrcpy video: %v", err)
		return
	}
	defer unixConn.Close()

	// Proactively detect when TCP client disconnects
	go func() {
		buf := make([]byte, 128)
		for {
			_, err := tcpConn.Read(buf)
			if err != nil {
				_ = unixConn.Close()
				return
			}
		}
	}()

	// Stream video from scrcpy unix socket to TCP client with low-latency buffer
	vBuf := make([]byte, 64*1024)
	_, _ = io.CopyBuffer(tcpConn, unixConn, vBuf)

	log.Printf("[ZenHost] Video connection ended for %s", tcpConn.RemoteAddr())

	sessionMutex.Lock()
	killScrcpyLocked()
	sessionMutex.Unlock()
}

func handleControlClient(tcpConn net.Conn) {
	defer tcpConn.Close()
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
		return
	}

	log.Printf("[ZenHost] Control channel active with bidirectional support for %s!", tcpConn.RemoteAddr())

	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(cConn, tcpConn)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(tcpConn, cConn)
		done <- struct{}{}
	}()

	<-done
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

type DeviceBeacon struct {
	Device      string `json:"device"`
	Model       string `json:"model"`
	IP          string `json:"ip"`
	VideoPort   int    `json:"video_port"`
	ControlPort int    `json:"control_port"`
	AudioPort   int    `json:"audio_port"`
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
						_, _ = udpServer.WriteToUDP([]byte(beacon), clientAddr)
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

	go udpBeaconLoop()

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

	log.Printf("[ZenHost] Listening on %s (Video), %s (Control), %s (Audio)...", videoPort, controlPort, audioPort)

	go func() {
		for {
			conn, err := controlListener.Accept()
			if err != nil {
				return
			}
			go handleControlClient(conn)
		}
	}()

	for {
		conn, err := videoListener.Accept()
		if err != nil {
			return
		}
		go handleVideoClient(conn)
	}
}
