package oaartc

import (
	"sync"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"
)

type events struct {
	mu         sync.Mutex
	candidates []webrtc.ICECandidateInit
	opened     chan struct{}
	messages   chan string
	keyFrames  chan struct{}
}

func (e *events) OnLocalCandidate(candidate string, sdpMid string, sdpMLineIndex int) {
	idx := uint16(sdpMLineIndex)
	e.mu.Lock()
	e.candidates = append(e.candidates, webrtc.ICECandidateInit{Candidate: candidate, SDPMid: &sdpMid, SDPMLineIndex: &idx})
	e.mu.Unlock()
}
func (e *events) OnConnectionState(string) {}
func (e *events) OnKeyFrameRequest() {
	select {
	case e.keyFrames <- struct{}{}:
	default:
	}
}
func (e *events) OnDataOpen() { close(e.opened) }
func (e *events) OnDataMessage(data []byte, binary bool) {
	if !binary {
		e.messages <- string(data)
	}
}
func (e *events) OnDataClose() {}

// Plays the SPA: recvonly H.264 plus the oaa-media channel, offering to the car.
func TestAnswerLiveAndDataChannel(t *testing.T) {
	ev := &events{opened: make(chan struct{}), messages: make(chan string, 4), keyFrames: make(chan struct{}, 1)}
	car, err := NewSession(`[]`, "oaa-media", ev)
	if err != nil {
		t.Fatal(err)
	}
	defer car.Close()

	viewer, err := webrtc.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	defer viewer.Close()
	if _, err := viewer.AddTransceiverFromKind(webrtc.RTPCodecTypeVideo,
		webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionRecvonly}); err != nil {
		t.Fatal(err)
	}
	dc, err := viewer.CreateDataChannel("oaa-media", nil)
	if err != nil {
		t.Fatal(err)
	}
	gotTrack := make(chan string, 1)
	viewer.OnTrack(func(tr *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
		gotTrack <- tr.Codec().MimeType
		for {
			if _, _, err := tr.ReadRTP(); err != nil {
				return
			}
		}
	})
	fromCar := make(chan string, 1)
	dc.OnMessage(func(m webrtc.DataChannelMessage) { fromCar <- string(m.Data) })

	offer, err := viewer.CreateOffer(nil)
	if err != nil {
		t.Fatal(err)
	}
	gathered := webrtc.GatheringCompletePromise(viewer)
	if err := viewer.SetLocalDescription(offer); err != nil {
		t.Fatal(err)
	}
	<-gathered

	answer, err := car.Answer(viewer.LocalDescription().SDP, true)
	if err != nil {
		t.Fatal(err)
	}
	if err := viewer.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: answer}); err != nil {
		t.Fatal(err)
	}
	time.Sleep(500 * time.Millisecond)
	ev.mu.Lock()
	for _, c := range ev.candidates {
		if err := viewer.AddICECandidate(c); err != nil {
			t.Fatal(err)
		}
	}
	ev.mu.Unlock()

	select {
	case <-ev.opened:
	case <-time.After(10 * time.Second):
		t.Fatal("data channel did not open")
	}
	if err := car.SendText("dc_hello"); err != nil {
		t.Fatal(err)
	}
	if err := dc.SendText("playback_open"); err != nil {
		t.Fatal(err)
	}
	// SPS, PPS and an IDR slice, Annex-B.
	idr := []byte{0, 0, 0, 1, 0x67, 0x42, 0xe0, 0x1f, 0, 0, 0, 1, 0x68, 0xce, 0x3c, 0x80, 0, 0, 0, 1, 0x65, 0x88, 0x84}
	deadline := time.After(10 * time.Second)
	var mime string
	for mime == "" {
		if err := car.WriteSample(idr, 33_333); err != nil {
			t.Fatal(err)
		}
		select {
		case mime = <-gotTrack:
		case <-deadline:
			t.Fatal("no video track on viewer")
		case <-time.After(50 * time.Millisecond):
		}
	}
	if mime != webrtc.MimeTypeH264 {
		t.Fatalf("codec %s, want H264", mime)
	}
	select {
	case m := <-fromCar:
		if m != "dc_hello" {
			t.Fatalf("viewer got %q", m)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("viewer got no data message")
	}
	select {
	case m := <-ev.messages:
		if m != "playback_open" {
			t.Fatalf("car got %q", m)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("car got no data message")
	}
}
