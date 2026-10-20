// Local synthetic interop harness: pass an existing synthetic bundle and a fresh output directory.
package main

import (
	"crypto/sha256"
	"ecocapture/ecocrypto"
	"encoding/hex"
	"encoding/json"
	"filippo.io/age"
	"fmt"
	"os"
	"path/filepath"
)

func main() {
	if len(os.Args) != 3 {
		panic("EXPECTED_SYNTHETIC_BUNDLE_AND_FRESH_OUTPUT")
	}
	source, output := os.Args[1], os.Args[2]
	if err := os.Mkdir(output, 0700); err != nil {
		panic("FRESH_OUTPUT_REQUIRED")
	}
	expected := map[string]string{}
	for _, name := range []string{"event.json", "manifest.json", "video.mp4"} {
		data, err := os.ReadFile(filepath.Join(source, name))
		if err != nil {
			panic("SOURCE_UNAVAILABLE")
		}
		hash := sha256.Sum256(data)
		expected[name] = hex.EncodeToString(hash[:])
	}
	gb10, _ := age.GenerateX25519Identity()
	recovery, _ := age.GenerateX25519Identity()
	// Disposable test identities only, never printed.
	for name, identity := range map[string]*age.X25519Identity{"gb10.agekey": gb10, "recovery.agekey": recovery} {
		if err := os.WriteFile(filepath.Join(output, name), []byte(identity.String()+"\n"), 0600); err != nil {
			panic("KEY_OUTPUT_FAILED")
		}
	}
	encoded, _ := json.Marshal(expected)
	if err := ecocrypto.Encrypt(source, filepath.Join(output, "approved-event.tar.age"), gb10.Recipient().String()+"\n"+recovery.Recipient().String(), string(encoded)); err != nil {
		panic("ENCRYPTION_FAILED")
	}
	fmt.Println("SYNTHETIC_INTEROP_ENCRYPTED")
}
