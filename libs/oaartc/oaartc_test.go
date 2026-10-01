package oaartc

import (
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/pion/rtcp"
	"github.com/pion/webrtc/v4"
)

type events struct {
	mu         sync.Mutex
	candidates []webrtc.ICECandidateInit
	opened     chan struct{}
	messages   chan string
	keyFrames  chan string
}

func (e *events) OnLocalCandidate(candidate string, sdpMid string, sdpMLineIndex int) {
	idx := uint16(sdpMLineIndex)
	e.mu.Lock()
	e.candidates = append(e.candidates, webrtc.ICECandidateInit{Candidate: candidate, SDPMid: &sdpMid, SDPMLineIndex: &idx})
	e.mu.Unlock()
}
func (e *events) OnConnectionState(string) {}
func (e *events) OnKeyFrameRequest(role string) {
	select {
	case e.keyFrames <- role:
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

type gotTrack struct {
	stream string
	mime   string
	ts     uint32
	ssrc   uint32
}

// Plays the SPA: four recvonly H.264 m-lines plus the oaa-media channel; the car
// sends two cameras.
func TestAnswerPerCameraTracksAndDataChannel(t *testing.T) {
	ev := &events{opened: make(chan struct{}), messages: make(chan string, 4), keyFrames: make(chan string, 4)}
	car, err := NewSession(`[]`, "oaa-media", true, ev)
	if err != nil {
		t.Fatal(err)
	}
	defer car.Close()

	viewer, err := webrtc.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	defer viewer.Close()
	for i := 0; i < 4; i++ {
		if _, err := viewer.AddTransceiverFromKind(webrtc.RTPCodecTypeVideo,
			webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionRecvonly}); err != nil {
			t.Fatal(err)
		}
	}
	dc, err := viewer.CreateDataChannel("oaa-media", nil)
	if err != nil {
		t.Fatal(err)
	}
	tracks := make(chan gotTrack, 4)
	viewer.OnTrack(func(tr *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
		pkt, _, err := tr.ReadRTP()
		if err != nil {
			return
		}
		tracks <- gotTrack{stream: tr.StreamID(), mime: tr.Codec().MimeType, ts: pkt.Timestamp, ssrc: uint32(tr.SSRC())}
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

	for _, role := range []string{"front", "rear"} {
		if err := car.AddVideoTrack(role); err != nil {
			t.Fatal(err)
		}
	}
	if err := car.AddVideoTrack("front"); err == nil {
		t.Fatal("duplicate role accepted")
	}
	answer, err := car.Answer(viewer.LocalDescription().SDP)
	if err != nil {
		t.Fatal(err)
	}
	if n := strings.Count(answer, "a=msid:"+StreamPrefix); n != 2 {
		t.Fatalf("answer has %d camera msids, want 2", n)
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
	if err := dc.SendText("replay_start"); err != nil {
		t.Fatal(err)
	}
	if err := car.WriteSample("left", []byte{0, 0, 0, 1, 0x65}, 1); err == nil {
		t.Fatal("write to a role without a track succeeded")
	}

	// SPS, PPS and an IDR slice, Annex-B.
	idr := []byte{0, 0, 0, 1, 0x67, 0x42, 0xe0, 0x1f, 0, 0, 0, 1, 0x68, 0xce, 0x3c, 0x80, 0, 0, 0, 1, 0x65, 0x88, 0x84}
	const captureMs = int64(1_790_000_000_123)
	got := map[string]gotTrack{}
	deadline := time.After(10 * time.Second)
	for len(got) < 2 {
		for _, role := range []string{"front", "rear"} {
			if err := car.WriteSample(role, idr, captureMs); err != nil {
				t.Fatal(err)
			}
		}
		select {
		case tr := <-tracks:
			got[tr.stream] = tr
		case <-deadline:
			t.Fatalf("got %d camera tracks on viewer, want 2", len(got))
		case <-time.After(50 * time.Millisecond):
		}
	}
	for _, role := range []string{"front", "rear"} {
		tr, ok := got[StreamPrefix+role]
		if !ok {
			t.Fatalf("no stream %s%s (got %v)", StreamPrefix, role, got)
		}
		if tr.mime != webrtc.MimeTypeH264 {
			t.Fatalf("codec %s, want H264", tr.mime)
		}
		if int64(tr.ts) != RTPTimestamp(captureMs) {
			t.Fatalf("%s rtp ts %d, want %d", role, tr.ts, RTPTimestamp(captureMs))
		}
	}

	if err := viewer.WriteRTCP([]rtcp.Packet{&rtcp.PictureLossIndication{MediaSSRC: got[StreamPrefix+"rear"].ssrc}}); err != nil {
		t.Fatal(err)
	}
	select {
	case role := <-ev.keyFrames:
		if role != "rear" {
			t.Fatalf("PLI routed to %q, want rear", role)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no keyframe request for PLI")
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
		if m != "replay_start" {
			t.Fatalf("car got %q", m)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("car got no data message")
	}
}

func TestRTPTimestampWraps(t *testing.T) {
	if RTPTimestamp(0) != 0 {
		t.Fatal("zero")
	}
	if RTPTimestamp(1000) != 90_000 {
		t.Fatalf("1s = %d", RTPTimestamp(1000))
	}
	period := int64(1<<32) / 90
	if d := RTPTimestamp(period+1000) - RTPTimestamp(1000); d > 90 || d < -90 {
		t.Fatalf("not periodic: %d", d)
	}
}
