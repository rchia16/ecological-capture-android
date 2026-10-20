package ecocrypto

import (
	"archive/tar"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"filippo.io/age"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestBothRecipientsAndIntegrity(t *testing.T) {
	dir := t.TempDir()
	source := filepath.Join(dir, "source")
	os.Mkdir(source, 0700)
	expected := map[string]string{}
	for _, name := range []string{"event.json", "manifest.json", "video.mp4"} {
		data := []byte("synthetic " + name)
		os.WriteFile(filepath.Join(source, name), data, 0600)
		h := sha256.Sum256(data)
		expected[name] = hex.EncodeToString(h[:])
	}
	encoded, _ := json.Marshal(expected)
	a, _ := age.GenerateX25519Identity()
	b, _ := age.GenerateX25519Identity()
	keys := a.Recipient().String() + "\n" + b.Recipient().String()
	output := filepath.Join(dir, "encrypted.age")
	if e := Encrypt(source, output, keys, string(encoded)); e != nil {
		t.Fatal(e)
	}
	for _, identity := range []*age.X25519Identity{a, b} {
		f, _ := os.Open(output)
		reader, e := age.Decrypt(f, identity)
		if e != nil {
			t.Fatal(e)
		}
		tr := tar.NewReader(reader)
		n := 0
		for {
			header, e := tr.Next()
			if e == io.EOF {
				break
			}
			if e != nil {
				t.Fatal(e)
			}
			data, _ := io.ReadAll(tr)
			h := sha256.Sum256(data)
			if expected[header.Name] != hex.EncodeToString(h[:]) {
				t.Fatal("hash mismatch")
			}
			n++
		}
		// Consume the authenticated stream to its end, including the final age tag.
		if _, e := io.Copy(io.Discard, reader); e != nil {
			t.Fatal(e)
		}
		f.Close()
		if n != 3 {
			t.Fatal(n)
		}
	}
	if Encrypt(source, output, keys, string(encoded)) == nil {
		t.Fatal("overwrote artifact")
	}
	os.WriteFile(filepath.Join(source, "video.mp4"), []byte("changed"), 0600)
	partial := filepath.Join(dir, "failed.age")
	if Encrypt(source, partial, keys, string(encoded)) == nil {
		t.Fatal("accepted changed source")
	}
	if _, e := os.Stat(partial); !os.IsNotExist(e) {
		t.Fatal("retained partial")
	}
	wrong, _ := age.GenerateX25519Identity()
	f, _ := os.Open(output)
	defer f.Close()
	if _, e := age.Decrypt(f, wrong); e == nil {
		t.Fatal("wrong key accepted")
	}
	if Encrypt(source, partial, strings.Split(keys, "\n")[0], string(encoded)) == nil {
		t.Fatal("missing recovery key accepted")
	}
}
