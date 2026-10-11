package mantle

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The rename that broke CI for two nights (cmd/nautilus -> cmd/naut) was invisible because the probe that
// missed it fell back silently. These are cheap — no gateway, no broker, no edge — and they are the reason
// the next rename will be a one-line failure instead of fifteen exec errors.
func TestNautilusPackageFindsTheEntrypointUnderEitherName(t *testing.T) {
	for _, tc := range []struct {
		name string
		dirs []string
		want string
	}{
		{"current layout", []string{"cmd/naut"}, "./cmd/naut"},
		{"the layout before the rename", []string{"cmd/nautilus"}, "./cmd/nautilus"},
		{"both present: newest wins", []string{"cmd/naut", "cmd/nautilus"}, "./cmd/naut"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			src := t.TempDir()
			for _, d := range tc.dirs {
				if err := os.MkdirAll(filepath.Join(src, d), 0o755); err != nil {
					t.Fatal(err)
				}
			}
			got, err := nautilusPackage(src)
			if err != nil {
				t.Fatalf("nautilusPackage: %v", err)
			}
			if got != tc.want {
				t.Errorf("got %q, want %q", got, tc.want)
			}
		})
	}
}

// No checkout at all is not an error: an installed CLI is a legitimate setup, and the caller decides.
func TestNautilusPackageIsQuietWhenThereIsNoCheckout(t *testing.T) {
	got, err := nautilusPackage(filepath.Join(t.TempDir(), "nope"))
	if err != nil || got != "" {
		t.Fatalf("got (%q, %v), want (\"\", nil)", got, err)
	}
}

// A checkout that holds none of the known entrypoints is the rename case, and must be loud: returning ""
// here would send the caller down the installed-CLI path and reproduce the original bug exactly.
func TestNautilusPackageSaysSoWhenTheEntrypointMovedAgain(t *testing.T) {
	src := t.TempDir()
	if err := os.MkdirAll(filepath.Join(src, "cmd", "something-else"), 0o755); err != nil {
		t.Fatal(err)
	}
	got, err := nautilusPackage(src)
	if err == nil {
		t.Fatalf("got (%q, nil), want an error naming the entrypoints tried", got)
	}
	for _, want := range nautilusEntrypoints {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("error does not mention %q: %v", want, err)
		}
	}
}
