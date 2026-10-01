package main

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/metacubex/mihomo/component/age"
)

func TestShouldDefaultTunStack(t *testing.T) {
	tests := []struct {
		name         string
		subscription string
		override     string
		want         bool
	}{
		{"no stack set", "rules: [MATCH,DIRECT]\n", `{ "tun": { "enable": true } }`, true},
		{"subscription stack", "tun:\n  stack: system\n", `{ "tun": { "enable": true } }`, false},
		{"user stack", "rules: [MATCH,DIRECT]\n", `{ "tun": { "stack": "gvisor" } }`, false},
		{"both stacks", "tun:\n  stack: system\n", `{ "tun": { "stack": "gvisor" } }`, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "override.json")
			if err := os.WriteFile(path, []byte(tt.override), 0600); err != nil {
				t.Fatal(err)
			}
			got, err := shouldDefaultTunStack([]byte(tt.subscription), path, "")
			if err != nil {
				t.Fatal(err)
			}
			if got != tt.want {
				t.Fatalf("shouldDefaultTunStack() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestShouldDefaultTunStackWithEncryptedSubscription(t *testing.T) {
	secretKey, publicKey, err := age.GenX25519KeyPair()
	if err != nil {
		t.Fatal(err)
	}
	data, err := age.EncryptBytes([]byte("tun:\n  stack: mixed\n"), publicKey)
	if err != nil {
		t.Fatal(err)
	}
	got, err := shouldDefaultTunStack(data, "", secretKey)
	if err != nil {
		t.Fatal(err)
	}
	if got {
		t.Fatal("encrypted subscription's explicit stack was ignored")
	}
}
