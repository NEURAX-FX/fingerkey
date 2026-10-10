package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"time"
)

// daemonSocket is the root-only unix socket used to mint one-time tokens.
var daemonSocket = "/run/phone-fprint-auth/daemon.sock"

// pairReply is the one-time pairing material returned by the daemon.
type pairReply struct {
	Token string `json:"token"`
	FP    string `json:"fp"`
	Host  string `json:"host"`
	Port  int    `json:"port"`
}

// pairCode prints copy/paste fields for manual pairing. The phone still pins
// the daemon certificate and sends its own Ed25519 public key in hello.
func pairCode(name string, w io.Writer) error {
	if err := validateName(name); err != nil {
		return err
	}
	reply, err := requestPairToken(daemonSocket, name)
	if err != nil {
		return err
	}
	hostname, err := os.Hostname()
	if err != nil {
		return fmt.Errorf("hostname: %v", err)
	}
	fmt.Fprintf(w, "Pair this computer as %q before the token expires (60 seconds):\n", name)
	fmt.Fprintf(w, "Server: %s\nPort: %d\nTLS fingerprint: %s\nPairing token: %s\nComputer name: %s\n", reply.Host, reply.Port, reply.FP, reply.Token, hostname)
	return nil
}

// requestPairToken POSTs {name} to the daemon's root-only unix socket.
func requestPairToken(socketPath, name string) (pairReply, error) {
	client := &http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				return (&net.Dialer{}).DialContext(ctx, "unix", socketPath)
			},
		},
		Timeout: 5 * time.Second,
	}
	body, err := json.Marshal(struct {
		Name string `json:"name"`
	}{Name: name})
	if err != nil {
		return pairReply{}, err
	}
	resp, err := client.Post("http://unix/v1/pair", "application/json", bytes.NewReader(body))
	if err != nil {
		return pairReply{}, fmt.Errorf("cannot reach daemon at %s: %v (is fingerkeyd running?)", socketPath, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		var e struct {
			Error string `json:"error"`
		}
		_ = json.NewDecoder(resp.Body).Decode(&e)
		if e.Error != "" {
			return pairReply{}, fmt.Errorf("daemon refused pairing: %s", e.Error)
		}
		return pairReply{}, fmt.Errorf("daemon refused pairing: HTTP %d", resp.StatusCode)
	}
	var reply pairReply
	if err := json.NewDecoder(resp.Body).Decode(&reply); err != nil {
		return pairReply{}, fmt.Errorf("malformed daemon reply: %v", err)
	}
	if reply.Token == "" || reply.FP == "" || reply.Host == "" || reply.Port == 0 {
		return pairReply{}, fmt.Errorf("malformed daemon reply: token, fp, host and port are all required")
	}
	return reply, nil
}
