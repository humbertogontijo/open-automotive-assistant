// Package oaartc is the car end of the WebRTC media plane (ADR-0003), built on
// Pion and bound to Kotlin with gomobile. It answers one viewer offer, sends
// pre-encoded H.264 AccessUnits on a single video track and carries the
// `oaa-media` data channel.
//
// Pion is pure Go, so the binding has no native dependencies beyond the Go
// runtime and does not share the org.webrtc namespace that GeckoView embeds.
package oaartc

import (
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/pion/interceptor"
	"github.com/pion/rtcp"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
)

// Listener receives session events. Calls arrive on Go-owned threads.
type Listener interface {
	OnLocalCandidate(candidate string, sdpMid string, sdpMLineIndex int)
	OnConnectionState(state string)
	OnKeyFrameRequest()
	OnDataOpen()
	OnDataMessage(data []byte, binary bool)
	OnDataClose()
}

// Session wraps one PeerConnection.
type Session struct {
	pc       *webrtc.PeerConnection
	listener Listener
	label    string
	track    *webrtc.TrackLocalStaticSample

	mu sync.Mutex
	dc *webrtc.DataChannel
}

type iceServer struct {
	URLs       any    `json:"urls"`
	Username   string `json:"username"`
	Credential string `json:"credential"`
}

// H264 constrained baseline 3.1, the mosaic encoder's profile.
const h264Fmtp = "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f"

// NewSession creates a PeerConnection. iceServersJSON is the RTCIceServer array the
// hub injects into the offer; dataLabel is the only data channel label accepted.
func NewSession(iceServersJSON string, dataLabel string, listener Listener) (*Session, error) {
	if listener == nil {
		return nil, errors.New("listener required")
	}
	servers, err := parseIceServers(iceServersJSON)
	if err != nil {
		return nil, err
	}
	me := &webrtc.MediaEngine{}
	if err := me.RegisterCodec(webrtc.RTPCodecParameters{
		RTPCodecCapability: webrtc.RTPCodecCapability{
			MimeType:    webrtc.MimeTypeH264,
			ClockRate:   90000,
			SDPFmtpLine: h264Fmtp,
			RTCPFeedback: []webrtc.RTCPFeedback{
				{Type: "nack"}, {Type: "nack", Parameter: "pli"}, {Type: "ccm", Parameter: "fir"},
			},
		},
		PayloadType: 102,
	}, webrtc.RTPCodecTypeVideo); err != nil {
		return nil, err
	}
	ir := &interceptor.Registry{}
	if err := webrtc.RegisterDefaultInterceptors(me, ir); err != nil {
		return nil, err
	}
	api := webrtc.NewAPI(webrtc.WithMediaEngine(me), webrtc.WithInterceptorRegistry(ir))
	pc, err := api.NewPeerConnection(webrtc.Configuration{
		ICEServers:    servers,
		BundlePolicy:  webrtc.BundlePolicyMaxBundle,
		RTCPMuxPolicy: webrtc.RTCPMuxPolicyRequire,
	})
	if err != nil {
		return nil, err
	}
	s := &Session{pc: pc, listener: listener, label: dataLabel}
	pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		if c == nil {
			return
		}
		init := c.ToJSON()
		mid := ""
		if init.SDPMid != nil {
			mid = *init.SDPMid
		}
		idx := 0
		if init.SDPMLineIndex != nil {
			idx = int(*init.SDPMLineIndex)
		}
		listener.OnLocalCandidate(init.Candidate, mid, idx)
	})
	pc.OnICEConnectionStateChange(func(st webrtc.ICEConnectionState) {
		listener.OnConnectionState(st.String())
	})
	pc.OnDataChannel(s.onDataChannel)
	return s, nil
}

func parseIceServers(raw string) ([]webrtc.ICEServer, error) {
	if raw == "" {
		return nil, nil
	}
	var in []iceServer
	if err := json.Unmarshal([]byte(raw), &in); err != nil {
		return nil, err
	}
	out := make([]webrtc.ICEServer, 0, len(in))
	for _, s := range in {
		var urls []string
		switch u := s.URLs.(type) {
		case string:
			urls = []string{u}
		case []any:
			for _, v := range u {
				if str, ok := v.(string); ok && str != "" {
					urls = append(urls, str)
				}
			}
		}
		if len(urls) == 0 {
			continue
		}
		srv := webrtc.ICEServer{URLs: urls}
		if s.Username != "" {
			srv.Username = s.Username
			srv.Credential = s.Credential
		}
		out = append(out, srv)
	}
	return out, nil
}

func (s *Session) onDataChannel(dc *webrtc.DataChannel) {
	s.mu.Lock()
	if dc.Label() != s.label || s.dc != nil {
		s.mu.Unlock()
		_ = dc.Close()
		return
	}
	s.dc = dc
	s.mu.Unlock()
	dc.OnOpen(s.listener.OnDataOpen)
	dc.OnClose(s.listener.OnDataClose)
	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		s.listener.OnDataMessage(msg.Data, !msg.IsString)
	})
}

// Answer applies the viewer's offer and returns the local answer SDP. Candidates
// trickle through Listener.OnLocalCandidate. With live set, a send-only H.264 track
// answers the offer's video m-line.
func (s *Session) Answer(offerSDP string, live bool) (string, error) {
	if live {
		track, err := webrtc.NewTrackLocalStaticSample(
			webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeH264, ClockRate: 90000, SDPFmtpLine: h264Fmtp},
			"oaa-mosaic", "oaa",
		)
		if err != nil {
			return "", err
		}
		sender, err := s.pc.AddTrack(track)
		if err != nil {
			return "", err
		}
		s.track = track
		go s.readRTCP(sender)
	}
	if err := s.pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: offerSDP}); err != nil {
		return "", err
	}
	answer, err := s.pc.CreateAnswer(nil)
	if err != nil {
		return "", err
	}
	if err := s.pc.SetLocalDescription(answer); err != nil {
		return "", err
	}
	return answer.SDP, nil
}

func (s *Session) readRTCP(sender *webrtc.RTPSender) {
	for {
		pkts, _, err := sender.ReadRTCP()
		if err != nil {
			return
		}
		for _, p := range pkts {
			switch p.(type) {
			case *rtcp.PictureLossIndication, *rtcp.FullIntraRequest:
				s.listener.OnKeyFrameRequest()
			}
		}
	}
}

// AddRemoteCandidate adds a trickled viewer candidate; call after Answer.
func (s *Session) AddRemoteCandidate(candidate string, sdpMid string, sdpMLineIndex int) error {
	idx := uint16(sdpMLineIndex)
	init := webrtc.ICECandidateInit{Candidate: candidate, SDPMLineIndex: &idx}
	if sdpMid != "" {
		init.SDPMid = &sdpMid
	}
	return s.pc.AddICECandidate(init)
}

// WriteSample sends one Annex-B AccessUnit lasting durationUs on the live track.
func (s *Session) WriteSample(annexB []byte, durationUs int64) error {
	if s.track == nil {
		return errors.New("no live track")
	}
	return s.track.WriteSample(media.Sample{Data: annexB, Duration: time.Duration(durationUs) * time.Microsecond})
}

func (s *Session) channel() (*webrtc.DataChannel, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.dc == nil {
		return nil, errors.New("data channel not open")
	}
	return s.dc, nil
}

// SendText sends a UTF-8 message on the data channel.
func (s *Session) SendText(msg string) error {
	dc, err := s.channel()
	if err != nil {
		return err
	}
	return dc.SendText(msg)
}

// SendBinary sends a binary message on the data channel.
func (s *Session) SendBinary(data []byte) error {
	dc, err := s.channel()
	if err != nil {
		return err
	}
	return dc.Send(data)
}

// BufferedAmount is the data channel's queued byte count, or 0 before it opens.
func (s *Session) BufferedAmount() int64 {
	dc, err := s.channel()
	if err != nil {
		return 0
	}
	return int64(dc.BufferedAmount())
}

// DataOpen reports whether the data channel is open.
func (s *Session) DataOpen() bool {
	dc, err := s.channel()
	return err == nil && dc.ReadyState() == webrtc.DataChannelStateOpen
}

// ConnectionState is the current ICE connection state name.
func (s *Session) ConnectionState() string {
	return s.pc.ICEConnectionState().String()
}

// Close tears down the PeerConnection; the session is unusable afterwards.
func (s *Session) Close() {
	_ = s.pc.Close()
}
