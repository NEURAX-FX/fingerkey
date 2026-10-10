package main

import (
	"bytes"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func startManualPairServer(t *testing.T, reply string) {
	t.Helper()
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, reply)
	})
	srv := httptest.NewUnstartedServer(handler)
	ln, err := net.Listen("unix", filepath.Join(t.TempDir(), "daemon.sock"))
	if err != nil {
		t.Fatal(err)
	}
	srv.Listener = ln
	srv.Start()
	prev := daemonSocket
	daemonSocket = ln.Addr().String()
	t.Cleanup(func() {
		daemonSocket = prev
		srv.Close()
	})
}

func TestPairCodePrintsManualFields(t *testing.T) {
	startManualPairServer(t, `{"token":"abc123","fp":"beef","host":"frp-arm.com","port":19230}`)
	var out bytes.Buffer
	if err := pairCode("phone", &out); err != nil {
		t.Fatal(err)
	}
	got := out.String()
	for _, want := range []string{"frp-arm.com", "19230", "beef", "abc123", "Pair this computer"} {
		if !strings.Contains(got, want) {
			t.Errorf("output %q does not contain %q", got, want)
		}
	}
}
